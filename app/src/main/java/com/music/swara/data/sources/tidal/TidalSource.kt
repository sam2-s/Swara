package com.music.swara.data.sources.tidal

import com.music.swara.data.TrackLog
import com.music.swara.data.model.Song
import com.music.swara.data.sources.MusicSource
import com.music.swara.data.sources.SourceConfig
import com.music.swara.data.sources.SourceHealth
import com.music.swara.data.sources.SourceKind
import com.music.swara.data.sources.SourceRegistry
import com.music.swara.data.sources.SourceStream
import com.music.swara.data.sources.StreamFormat
import com.music.swara.data.sources.StreamRequest
import com.music.swara.playback.StreamContainer
import java.io.File

private const val TAG = "Swara"

/**
 * Tidal as a [MusicSource].
 *
 * Serves FLAC as MPEG-DASH segments rather than a progressive file, which is
 * what puts this kind below [SourceKind.QOBUZ] in the walk: when both are
 * configured, the source that can answer with one GET is asked first, and the
 * one that needs a manifest decoded and a list of ranges concatenated is the
 * fallback.
 *
 * Two behaviours are deliberate:
 *
 *  - **A DASH manifest is written to a file before it is handed over.** Tidal
 *    answers a stream request with a base64-encoded manifest, not a URL, so
 *    there is nothing to give the player until it exists somewhere. The
 *    `file://` URI is then declared as `dash` via [StreamContainer.declare] —
 *    without that, Media3 builds a progressive source for it and fails on the
 *    only error that path can raise.
 *  - **A preview is refused, not served.** Tidal answers a stream request for
 *    an unsubscribed account with a 30-second sample at HTTP 200. It plays, it
 *    is the right song, and it stops.
 */
class TidalSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TIDAL
    override val displayName: String get() = config.label.ifBlank { SourceKind.TIDAL.label }

    /**
     * Whether the token can reach the streaming endpoint.
     *
     * The distinction this draws is the whole point of [SourceHealth] as a
     * three-state answer: a Tidal that is down is worth retrying and worth
     * leaving enabled, and one whose token has expired is neither — it will
     * fail identically on every future call until the user signs in again.
     * Collapsing both into "not ok" sends someone to re-authenticate when
     * the network was the problem.
     */
    override suspend fun health(): SourceHealth {
        if (config.tidalToken.isBlank()) {
            return SourceHealth.Rejected("Tidal needs an access token")
        }
        return try {
            if (TidalApi.canStream(config.tidalToken)) {
                SourceHealth.Ok()
            } else {
                SourceHealth.Rejected("Tidal refused the token")
            }
        } catch (e: TidalAuthException) {
            SourceHealth.Rejected("Tidal refused the credentials (${e.message})")
        } catch (e: Exception) {
            SourceHealth.Unreachable(e.message ?: "Tidal did not answer")
        }
    }

    /**
     * Searches the Tidal catalogue.
     *
     * Works without credentials — the search endpoint answers with a public
     * token — which is what lets this source be offered as a playback target
     * for rows found elsewhere (see [AppleMusicSource]) without asking the
     * user for an account they may not have.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val tracks = runCatching { TidalApi.search(query, limit) }.getOrNull() ?: return emptyList()

        return tracks.take(limit).map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, track.id),
                title = track.title,
                artist = track.artist,
                albumName = track.album,
                thumbnailUrl = track.thumbnailUrl,
                durationText = track.durationSec?.let { seconds ->
                    "${seconds / 60}:${String.format("%02d", seconds % 60)}"
                },
                sourceQuality = "LOSSLESS",
                isExplicit = track.isExplicit,
            )
        }
    }

    /**
     * The stream for this track, or null when Tidal does not have it.
     *
     * @return null on a miss — which is not an error, and lets
     *   [com.music.swara.data.sources.SourceResolver] step over this source to
     *   the next one without logging a failure.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (config.tidalToken.isBlank()) return null

        // Asked for the top rung. Tidal has no "best available" — a
        // HI_RES_LOSSLESS request for a CD-only master returns 16-bit audio
        // rather than an error — so the honest move is to ask for the most and
        // let the player's own format check notice a downgrade. A capped
        // request is the one exception: it is a statement about what the
        // connection can carry.
        val quality = when (request) {
            is StreamRequest.Capped -> if (request.maxKbps <= 320) "LOSSLESS" else "HI_RES_LOSSLESS"
            else -> "HI_RES_LOSSLESS"
        }

        val stream = runCatching { TidalApi.stream(trackId, quality, config.tidalToken) }.getOrNull()
        if (stream == null) {
            TrackLog.w(TAG, "Tidal had no stream for $trackId")
            return null
        }
        if (stream.isPreview) {
            TrackLog.w(TAG, "Tidal returned a preview for $trackId — refusing")
            return null
        }

        // A DASH manifest is an index of the audio, not the audio. Tidal hands
        // it over base64-encoded, so it has to exist as a file before the player
        // can be pointed at it — and the player has to be *told* it is DASH,
        // because a progressive source handed a manifest fails on the only
        // error that path can raise.
        if (stream.isDash) {
            val manifest = writeManifest(trackId, stream) ?: return null
            val uri = manifest.toURI().toString()
            StreamContainer.declare(uri, "dash")
            return SourceStream(
                url = uri,
                format = formatFor(stream),
                sourceConfigId = config.id,
            )
        }

        return SourceStream(
            url = stream.url,
            format = formatFor(stream),
            sourceConfigId = config.id,
        )
    }

    /**
     * Writes a DASH manifest to a file the player can open.
     *
     * @return null when the manifest could not be written, which is a miss
     *   rather than an error — the source chain moves on.
     */
    private fun writeManifest(
        trackId: String,
        stream: TidalApi.Stream,
    ): File? = runCatching {
        val dir = File(SourceRegistry.cacheDir(), "tidal-manifests")
        dir.mkdirs()
        val file = File(dir, "$trackId.mpd")
        file.writeText(stream.manifestText ?: return@runCatching null)
        file
    }.getOrNull()

    /**
     * What this stream is expected to be.
     *
     * Reported as the *request*, not as a measurement — the bytes have not been
     * looked at yet, and a source claiming 24/192 that the decoder then finds
     * running at 16/48 is exactly the failure the player's own format check
     * exists to catch.
     */
    private fun formatFor(stream: TidalApi.Stream): StreamFormat = when {
        stream.codecs.contains("flac", true) || stream.mimeType.contains("flac", true) ->
            StreamFormat(
                codec = "flac",
                bitDepth = stream.bitDepth,
                sampleRateHz = stream.sampleRateHz,
            )
        stream.codecs.contains("ec-3", true) || stream.codecs.contains("eac3", true) ->
            StreamFormat(codec = "eac3-joc")
        else -> StreamFormat(codec = "mp4a.40.2")
    }
}
