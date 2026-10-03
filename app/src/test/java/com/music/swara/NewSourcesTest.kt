package com.music.swara

import com.music.swara.data.model.Song
import com.music.swara.data.sources.MusicSource
import com.music.swara.data.sources.SourceConfig
import com.music.swara.data.sources.SourceHealth
import com.music.swara.data.sources.SourceKind
import com.music.swara.data.sources.SourceRegistry
import com.music.swara.data.sources.SourceStream
import com.music.swara.data.sources.StreamFormat
import com.music.swara.data.sources.StreamRequest
import com.music.swara.data.sources.TrackMatcher
import com.music.swara.data.sources.apple.AppleMusicSource
import com.music.swara.data.sources.qobuz.QobuzApi
import com.music.swara.data.sources.tidal.TidalApi
import com.music.swara.download.TidalDashDownload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The new first-class kinds: where they sit in the walk, what completes them,
 * and how the Tidal download path assembles a file.
 *
 * Everything here runs on the plain JVM with no network — the Tidal assembly
 * is served by a local [MockWebServer], and the matchers and manifest parser
 * are pure functions of their inputs. A test that needs Qobuz's or Tidal's
 * real API is a test that cannot run in CI, so there are none.
 */
class NewSourcesTest {

    // ---- Kind order and shape -------------------------------------------

    @Test
    fun `lossless kinds walk qobuz before tidal before youtube`() {
        assertTrue(SourceKind.QOBUZ.rank < SourceKind.TIDAL.rank)
        assertTrue(SourceKind.TIDAL.rank < SourceKind.JIOSAAVN.rank)
        assertTrue(SourceKind.JIOSAAVN.rank < SourceKind.YOUTUBE.rank)
        assertTrue(SourceKind.APPLE.rank < SourceKind.JIOSAAVN.rank)
    }

    @Test
    fun `only apple music is catalogue-only`() {
        for (kind in SourceKind.entries) {
            assertEquals(kind == SourceKind.APPLE, !kind.servesStreams)
        }
    }

    @Test
    fun `qobuz and tidal advertise lossless`() {
        assertTrue(SourceKind.QOBUZ.canServeLossless)
        assertTrue(SourceKind.TIDAL.canServeLossless)
        assertFalse(SourceKind.APPLE.canServeLossless)
    }

    // ---- Config completeness ------------------------------------------

    @Test
    fun `qobuz is complete only with the whole triple`() {
        assertFalse(SourceConfig(kind = SourceKind.QOBUZ).isComplete)
        assertFalse(
            SourceConfig(kind = SourceKind.QOBUZ, qobuzToken = "t", qobuzAppId = "a").isComplete,
        )
        assertTrue(
            SourceConfig(
                kind = SourceKind.QOBUZ,
                qobuzToken = "t",
                qobuzAppId = "a",
                qobuzAppSecret = "s",
            ).isComplete,
        )
    }

    @Test
    fun `tidal is complete with a token and apple always is`() {
        assertFalse(SourceConfig(kind = SourceKind.TIDAL).isComplete)
        assertTrue(SourceConfig(kind = SourceKind.TIDAL, tidalToken = "t").isComplete)
        assertTrue(SourceConfig(kind = SourceKind.APPLE).isComplete)
    }

    @Test
    fun `the new kinds seed disabled`() {
        assertFalse(SourceConfig(kind = SourceKind.QOBUZ).enabled)
        assertFalse(SourceConfig(kind = SourceKind.TIDAL).enabled)
        assertFalse(SourceConfig(kind = SourceKind.APPLE).enabled)
        val seeded = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = true)
        for (kind in listOf(SourceKind.QOBUZ, SourceKind.TIDAL, SourceKind.APPLE)) {
            assertFalse(seeded.single { it.kind == kind }.enabled)
        }
        assertTrue(seeded.single { it.kind == SourceKind.YOUTUBE }.enabled)
    }

    @Test
    fun `incomplete credentialed sources stay out of the walk`() {
        val configs = listOf(
            SourceConfig(kind = SourceKind.QOBUZ, enabled = true),
            SourceConfig(kind = SourceKind.TIDAL, enabled = true),
            SourceConfig(kind = SourceKind.YOUTUBE),
        )
        val enabled = SourceRegistry.enabledConfigs(configs)
        assertEquals(listOf(SourceKind.YOUTUBE), enabled.map { it.kind })
    }

    // ---- Apple Music ---------------------------------------------------

    @Test
    fun `apple music never serves a stream`() = runBlocking {
        val source = AppleMusicSource(SourceConfig(kind = SourceKind.APPLE))
        assertNull(source.stream("12345", StreamRequest.Best))
        assertNull(source.stream("12345", StreamRequest.Lossless))
    }

    // ---- Qobuz matching -------------------------------------------------

    private fun qobuzTrack(
        title: String,
        artist: String,
        durationSec: Int? = 210,
        id: String = title.hashCode().toString(),
    ) = QobuzApi.Track(
        id = id,
        title = title,
        artist = artist,
        album = null,
        durationSec = durationSec,
        thumbnailUrl = null,
        isExplicit = false,
    )

    @Test
    fun `qobuz picks the same recording over a same-titled remix`() {
        val tracks = listOf(
            qobuzTrack("Starboy (Remix)", "The Weeknd"),
            qobuzTrack("Starboy", "The Weeknd"),
        )
        val match = QobuzApi.bestMatch("Starboy", listOf("The Weeknd"), 230, tracks)
        assertEquals("Starboy", match?.title)
    }

    @Test
    fun `qobuz prefers the cut whose runtime agrees`() {
        // Duration is scored, not gated: both rows clear the bar, so the one
        // agreeing to the second wins rather than the other being rejected.
        val tracks = listOf(
            qobuzTrack("Starboy", "The Weeknd", durationSec = 400, id = "long"),
            qobuzTrack("Starboy", "The Weeknd", durationSec = 230, id = "right"),
        )
        assertEquals("right", QobuzApi.bestMatch("Starboy", listOf("The Weeknd"), 230, tracks)?.id)
    }

    @Test
    fun `qobuz rejects a title sharing fewer than half its words`() {
        val tracks = listOf(qobuzTrack("Starboy Nights Forever", "The Weeknd"))
        assertNull(QobuzApi.bestMatch("Starboy", listOf("The Weeknd"), 230, tracks))
    }

    @Test
    fun `qobuz search query trims edition noise`() {
        val query = QobuzApi.searchQuery("Starboy - Remastered", listOf("The Weeknd"))
        assertEquals("The Weeknd Starboy", query)
    }

    // ---- Tidal manifest parsing -----------------------------------------

    private fun templateManifest() = """
        <MPD>
          <BaseURL>https://cdn.tidal.com/audio/</BaseURL>
          <SegmentTemplate initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s">
            <SegmentTimeline><S d="100" r="1"/></SegmentTimeline>
          </SegmentTemplate>
        </MPD>
    """.trimIndent()

    private fun segmentListManifest() = """
        <MPD>
          <SegmentList>
            <Initialization sourceURL="https://cdn.tidal.com/audio/init.mp4"/>
            <SegmentURL media="https://cdn.tidal.com/audio/a.m4s"/>
            <SegmentURL media="https://cdn.tidal.com/audio/b.m4s"/>
          </SegmentList>
        </MPD>
    """.trimIndent()

    @Test
    fun `tidal expands a number template against its timeline`() {
        val urls = TidalApi.extractSegmentUrls(templateManifest())
        assertEquals(
            listOf(
                "https://cdn.tidal.com/audio/init.mp4",
                "https://cdn.tidal.com/audio/seg-1.m4s",
                "https://cdn.tidal.com/audio/seg-2.m4s",
            ),
            urls,
        )
    }

    @Test
    fun `tidal reads an explicit segment list`() {
        val urls = TidalApi.extractSegmentUrls(segmentListManifest())
        assertEquals(
            listOf(
                "https://cdn.tidal.com/audio/init.mp4",
                "https://cdn.tidal.com/audio/a.m4s",
                "https://cdn.tidal.com/audio/b.m4s",
            ),
            urls,
        )
    }

    @Test
    fun `tidal refuses a manifest with no segments`() {
        assertTrue(TidalApi.extractSegmentUrls("<MPD></MPD>").isEmpty())
        assertTrue(TidalApi.extractSegmentUrls("not xml at all").isEmpty())
    }

    // ---- Tidal FLAC assembly --------------------------------------------

    /**
     * An init segment: a 50-byte `dfLa` full-box holding STREAMINFO.
     *
     * Four version/flags bytes sit between the box type and the metadata
     * blocks — that is what the assembler's `payloadStart` skips past, and a
     * fixture without them describes a layout the real segments never have.
     */
    private fun initSegment(): ByteArray {
        val streaminfo = ByteArray(34) { it.toByte() }
        val out = ByteArrayOutputStream()
        out.write(intBytes(50))
        out.write("dfLa".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(4))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x22))
        out.write(streaminfo)
        return out.toByteArray()
    }

    /** A media segment: one `mdat` box with [payload] inside. */
    private fun mediaSegment(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBytes(8 + payload.size))
        out.write("mdat".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    @Test
    fun `tidal assembles init metadata plus each mdat payload`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(initSegment())))
            server.enqueue(MockResponse().setBody(Buffer().write(mediaSegment(byteArrayOf(1, 2, 3, 4, 5)))))
            server.enqueue(MockResponse().setBody(Buffer().write(mediaSegment(byteArrayOf(6, 7, 8)))))
            val base = server.url("/audio/").toString()
            val urls = listOf(base + "init", base + "a", base + "b")

            val sink = ByteArrayOutputStream()
            var progress = 0L to 0L
            val written = runBlocking {
                TidalDashDownload.assemble(urls, emptyMap(), expectedDurationSec = null, sink) { w, t ->
                    progress = w to t
                }
            }

            val bytes = sink.toByteArray()
            assertEquals(4 + 38 + 5 + 3, bytes.size)
            assertEquals(written, bytes.size.toLong())
            assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5), bytes.slice(42 until 47))
            assertEquals(listOf<Byte>(6, 7, 8), bytes.slice(47 until 50))
            assertEquals(3L to 3L, progress)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tidal assembly refuses a segment with no audio`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(initSegment())))
            val out = ByteArrayOutputStream()
            out.write(intBytes(8))
            out.write("moov".toByteArray(Charsets.US_ASCII))
            server.enqueue(MockResponse().setBody(Buffer().write(out.toByteArray())))
            val base = server.url("/audio/").toString()
            try {
                runBlocking {
                    TidalDashDownload.assemble(
                        listOf(base + "init", base + "x"),
                        emptyMap(),
                        expectedDurationSec = null,
                        ByteArrayOutputStream(),
                    ) { _, _ -> }
                }
                fail("expected no-audio refusal")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("no audio payload"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tidal assembly refuses a file far too small for its runtime`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(initSegment())))
            server.enqueue(MockResponse().setBody(Buffer().write(mediaSegment(byteArrayOf(9)))))
            val base = server.url("/audio/").toString()
            try {
                runBlocking {
                    TidalDashDownload.assemble(
                        listOf(base + "init", base + "a"),
                        emptyMap(),
                        expectedDurationSec = 240,
                        ByteArrayOutputStream(),
                    ) { _, _ -> }
                }
                fail("expected preview refusal")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("likely a preview"))
            }
        } finally {
            server.shutdown()
        }
    }

    // ---- Catalogue-only kinds never enter a stream race ------------------

    private class RecordingSource(
        override val displayName: String,
        override val kind: SourceKind,
        var asked: Boolean = false,
    ) : MusicSource {
        override val configId = displayName

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            asked = true
            delay(1)
            return listOf(
                Song(
                    videoId = "$displayName-1",
                    title = "Starboy",
                    artist = "The Weeknd",
                    thumbnailUrl = null,
                    durationText = "3:50",
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            SourceStream(
                url = "https://$displayName/$trackId",
                format = StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44100),
            )
    }

    @Test
    fun `bestAcross never asks a catalogue-only source`() = runBlocking {
        val apple = RecordingSource("apple", SourceKind.APPLE)
        val qobuz = RecordingSource("qobuz", SourceKind.QOBUZ)
        val target = TrackMatcher.Target("Starboy", "The Weeknd", durationSec = 230)

        val winner = com.music.swara.data.sources.SourceResolver.bestAcross(
            listOf(apple, qobuz),
            target,
            StreamRequest.Best,
            waitForAll = true,
        )

        assertNotNull(winner)
        assertEquals("qobuz", winner!!.first.configId)
        assertFalse(apple.asked)
        assertTrue(qobuz.asked)
    }
}
