package com.music.swara.download

import com.music.swara.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Assembles a Tidal DASH stream into a single FLAC file.
 *
 * Tidal serves its FLAC as MPEG-DASH segments rather than a progressive file:
 * an initialisation segment carrying the FLAC metadata inside an MP4 box, then
 * a run of media segments carrying audio frames inside `mdat` boxes. What comes
 * out here is `fLaC`, the metadata blocks verbatim, then every `mdat` payload
 * concatenated — a plain FLAC file that [FlacTagger] can tag and any player
 * can open.
 *
 * Deliberately separate from [OfflineDash] rather than a branch in it. That
 * one saves a DASH manifest *as an HLS package* — same segments, rewritten
 * index — which is the right shape for the fMP4 the module backends serve and
 * the wrong shape for FLAC-in-MP4: an HLS playlist pointing at `.m4s` files
 * full of FLAC frames plays nowhere. Sharing a body between them would put the
 * proven path at risk to save about twenty lines.
 *
 * The box parsing is ported from ArchiveTune's Tidal provider (GPL-3.0 — see
 * NOTICE.md), which is where this segment layout was worked out: the metadata
 * lives in a box whose type reads `dfLa`, and the audio in `mdat` boxes whose
 * headers have to be skipped rather than copied.
 */
internal object TidalDashDownload {

    /**
     * Fetches [segmentUrls] — init first, media after — and writes one FLAC
     * file to [sink].
     *
     * @param expectedDurationSec the track's runtime, when the caller knows
     *   it. A file far smaller than even a miserably encoded full track is a
     *   preview wearing a manifest's clothes, and committing it would publish
     *   a file that looks whole and stops halfway through the song.
     * @param onProgress called per segment, with segments completed and
     *   segments total. The byte total is unknowable up front — the manifest
     *   states none — so progress is measured in segments rather than bytes.
     * @return how many bytes were written.
     */
    suspend fun assemble(
        segmentUrls: List<String>,
        headers: Map<String, String>,
        expectedDurationSec: Int?,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        if (segmentUrls.size < 2 || segmentUrls.any { it.isBlank() }) {
            error("Tidal manifest listed no playable segments")
        }

        var written = 0L
        fun counted(bytes: Int) {
            written += bytes
        }

        val initBytes = fetch(segmentUrls.first(), headers, 1, segmentUrls.size)
        val metadata = extractFlacMetadataBlocks(initBytes)
        sink.write(MAGIC)
        sink.write(metadata)
        counted(MAGIC.size + metadata.size)
        onProgress(1, segmentUrls.size.toLong())

        segmentUrls.drop(1).forEachIndexed { index, url ->
            coroutineContext.ensureActive()
            val segment = fetch(url, headers, index + 2, segmentUrls.size)
            val payloads = mdatPayloads(segment)
            sink.write(payloads)
            counted(payloads.size)
            onProgress((index + 2).toLong(), segmentUrls.size.toLong())
        }
        sink.flush()

        // The magic plus an empty STREAMINFO is 42 bytes; anything at or below
        // that is headers with no audio behind them.
        if (written <= EMPTY_FILE_BYTES) {
            error("Tidal download produced no audio")
        }
        if (expectedDurationSec != null) {
            val floor = previewFloorBytes(expectedDurationSec)
            if (written < floor) {
                error(
                    "Tidal returned ${written}B for a ${expectedDurationSec}s track " +
                        "— likely a preview, try again",
                )
            }
        }
        written
    }

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        index: Int,
        total: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Accept", "audio/mp4,audio/*,*/*;q=0.8")
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) error("Tidal segment $index/$total failed (HTTP ${response.code})")
            response.body?.bytes() ?: error("Tidal segment $index/$total was empty")
        }
    }

    /**
     * The FLAC metadata blocks carried in the init segment.
     *
     * They sit inside an MP4 box whose type reads `dfLa`, after the box size
     * and type — so the payload starts eight bytes past the box start, and the
     * first block in it must be a 34-byte `STREAMINFO`. Anything else is not a
     * FLAC init segment, and assembling past it would produce a file whose
     * header lies about the frames behind it.
     */
    private fun extractFlacMetadataBlocks(initBytes: ByteArray): ByteArray {
        val typeOffset = initBytes.indexOfAscii("dfLa")
        if (typeOffset < 4) error("Tidal init segment had no FLAC metadata")
        val boxStart = typeOffset - 4
        val boxSize = initBytes.boxSize(boxStart)
        val boxEnd = boxStart + boxSize
        val payloadStart = typeOffset + 8
        if (boxSize < 16 || payloadStart + 4 > boxEnd || boxEnd > initBytes.size) {
            error("Tidal FLAC metadata box was invalid")
        }
        val metadata = initBytes.copyOfRange(payloadStart, boxEnd)
        val firstType = metadata[0].toInt() and 0x7F
        val firstLength = ((metadata[1].toInt() and 0xFF) shl 16) or
            ((metadata[2].toInt() and 0xFF) shl 8) or
            (metadata[3].toInt() and 0xFF)
        if (firstType != 0 || firstLength != 34 || metadata.size < 4 + firstLength) {
            error("Tidal FLAC STREAMINFO was invalid")
        }
        return metadata
    }

    /**
     * Every `mdat` payload in a media segment, concatenated.
     *
     * The box headers are skipped rather than copied: they describe the
     * segment file, and this file has no segments. A segment with no `mdat`
     * at all is not an audio segment, and silently skipping it would publish
     * a file with a hole in the middle of the song.
     */
    private fun mdatPayloads(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var foundMdat = false
        var offset = 0
        while (offset + BOX_HEADER <= bytes.size) {
            val size = bytes.boxSize(offset)
            val type = bytes.boxType(offset + 4)
            val headerSize = if (bytes.uint32(offset) == 1L) EXTENDED_BOX_HEADER else BOX_HEADER
            val boxEnd = offset + size
            if (size < headerSize || boxEnd > bytes.size) {
                error("Tidal segment had an invalid MP4 box")
            }
            if (type == "mdat") {
                out.write(bytes, offset + headerSize, size - headerSize)
                foundMdat = true
            }
            offset = boxEnd
        }
        if (!foundMdat) error("Tidal segment had no audio payload")
        return out.toByteArray()
    }

    /**
     * The smallest file that could still be the whole track.
     *
     * A full track at a miserly 96kbps, discounted by nearly a third — the
     * same floor ArchiveTune's provider uses. Anything under it for a track
     * over a minute long is a preview, not a bad encode: no real master
     * compresses that far.
     */
    private fun previewFloorBytes(durationSec: Int): Long {
        if (durationSec < 60) return 0L
        return (durationSec * 96_000.0 / 8.0 * 0.70).toLong()
    }

    private val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

    /** Magic plus an empty STREAMINFO: headers with no audio behind them. */
    private const val EMPTY_FILE_BYTES = 42L

    private const val BOX_HEADER = 8
    private const val EXTENDED_BOX_HEADER = 16

    private fun ByteArray.indexOfAscii(value: String): Int {
        val needle = value.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || size < needle.size) return -1
        for (index in 0..size - needle.size) {
            var matches = true
            for (needleIndex in needle.indices) {
                if (this[index + needleIndex] != needle[needleIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun ByteArray.boxSize(offset: Int): Int {
        val size = when (val size32 = uint32(offset)) {
            // Zero means "to the end of the file", and one means the real
            // size follows in the next eight bytes — both straight from the
            // MP4 box header spec, and both shapes Tidal's segments use.
            0L -> (size - offset).toLong()
            1L -> uint64(offset + 8)
            else -> size32
        }
        if (size <= 0L || size > Int.MAX_VALUE) error("Tidal segment had an invalid MP4 box size")
        return size.toInt()
    }

    private fun ByteArray.boxType(offset: Int): String {
        if (offset < 0 || offset + 4 > size) error("Tidal segment had a truncated MP4 type")
        return String(this, offset, 4, Charsets.US_ASCII)
    }

    private fun ByteArray.uint32(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) error("Tidal segment had a truncated MP4 box")
        return ((this[offset].toLong() and 0xFFL) shl 24) or
            ((this[offset + 1].toLong() and 0xFFL) shl 16) or
            ((this[offset + 2].toLong() and 0xFFL) shl 8) or
            (this[offset + 3].toLong() and 0xFFL)
    }

    private fun ByteArray.uint64(offset: Int): Long {
        if (offset < 0 || offset + 8 > size) error("Tidal segment had a truncated large MP4 box")
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (this[offset + index].toLong() and 0xFFL)
        }
        return value
    }
}
