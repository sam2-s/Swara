package com.music.swara.data.sources.qobuz

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

private const val TAG = "Swara"

/**
 * Qobuz as a [MusicSource].
 *
 * The one lossless source whose downloads need no assembly: Qobuz hands back a
 * progressive FLAC URL, so [stream] returns something [com.music.swara.download.Downloader]
 * can fetch in a single request and tag. That is the whole reason this kind
 * sits above [SourceKind.TIDAL] in the walk — when both are configured, the
 * source that answers with one GET is asked first, and the one that needs a
 * manifest parsed and a dozen ranges concatenated is the fallback.
 *
 * Two behaviours here are deliberate and both are about not lying to the
 * caller:
 *
 *  - **A preview is refused, not served.** Qobuz answers a `getFileUrl` for a
 *    non-premium account with a 30-second sample at HTTP 200. It plays, it is
 *    the right song, and it stops. Returning it would be a track that silently
 *    ends, so [stream] returns null and the source chain moves on.
 *  - **The format is reported as asked, not as assumed.** `format_id` 7 on a
 *    CD-quality master returns 16-bit audio rather than an error, so the
 *    [StreamFormat] handed back describes what was requested and the player's
 *    own decoder numbers are the ones that get compared against it — see
 *    [com.music.swara.playback.QualityUpgrade], which is exactly the check that
 *    catches a downgraded master.
 */
class QobuzSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.QOBUZ
    override val displayName: String get() = config.label.ifBlank { SourceKind.QOBUZ.label }

    private val credentials: QobuzCredentials
        get() = QobuzCredentials(
            token = config.qobuzToken,
            appId = config.qobuzAppId,
            appSecret = config.qobuzAppSecret,
        )

    /**
     * Whether the credentials can sign a request at all.
     *
     * The distinction this draws is the whole point of [SourceHealth] as a
     * three-state answer: a Qobuz that is down is worth retrying and worth
     * leaving enabled, and one whose `app_secret` is stale is neither — it will
     * fail identically on every future call until the user pastes a new one.
     * Collapsing both into "not ok" sends someone to re-enter a token that was
     * never wrong.
     */
    override suspend fun health(): SourceHealth {
        if (!credentials.isComplete) {
            return SourceHealth.Rejected("Qobuz needs a user token and an app_id/app_secret pair")
        }
        return try {
            if (QobuzApi.canSign(credentials)) SourceHealth.Ok() else SourceHealth.Rejected("Invalid request signature")
        } catch (e: QobuzAuthException) {
            SourceHealth.Rejected("Qobuz refused the credentials (${e.message})")
        } catch (e: Exception) {
            SourceHealth.Unreachable(e.message ?: "Qobuz did not answer")
        }
    }

    /**
     * Searches the Qobuz catalogue.
     *
     * [request] is honoured in the sense that matters here: a search made on
     * behalf of a lossless stream is the one that has to come back with a
     * hi-res-capable recording, and Qobuz's rows do advertise their tier. The
     * filtering itself happens at [stream] time, where the format is actually
     * chosen — a row that looks right and turns out to be CD-only is a
     * downgrade the caller needs to know about, not a row to hide.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val credentials = credentials
        if (!credentials.isComplete) return emptyList()

        val tracks = runCatching {
            QobuzApi.search(QobuzApi.searchQuery(query, emptyList()), credentials, limit)
        }.getOrNull() ?: return emptyList()

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
                // Carried on the row rather than discovered at stream time
                // because it is the only thing that distinguishes two
                // catalogues holding the same track — see Song.sourceQuality.
                sourceQuality = "LOSSLESS",
                isExplicit = track.isExplicit,
            )
        }
    }

    /**
     * The FLAC URL for this track, or null when Qobuz does not have it.
     *
     * @param trackId this source's own id for the track, as issued by [search].
     * @return null on a miss — which is not an error, and lets
     *   [com.music.swara.data.sources.SourceResolver] step over this source to
     *   the next one without logging a failure.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val credentials = credentials
        if (!credentials.isComplete) return null

        // Asked for the top rung regardless of [request]. Qobuz has no "best
        // available" — format_id 7 on a CD master returns 16-bit rather than an
        // error — so the honest move is to ask for the most and let the format
        // check in the player notice a downgrade. A capped request is the one
        // exception: it is a statement about what the connection can carry, and
        // spending 24/96 on a metered link to have it transcoded downstream is
        // the waste the ceiling exists to prevent.
        val formatId = when (request) {
            is StreamRequest.Capped -> if (request.maxKbps <= 320) QobuzApi.FORMAT_CD else QobuzApi.FORMAT_HI_RES_192
            else -> QobuzApi.FORMAT_HI_RES_192
        }

        val file = runCatching { QobuzApi.fileUrl(trackId, formatId, credentials) }.getOrNull()
        if (file == null) {
            TrackLog.w(TAG, "Qobuz had no file for $trackId")
            return null
        }
        if (file.isPreview) {
            // A sample is a wrong answer that looks like a right one. Refused
            // here so the resolver steps over this source rather than the player
            // starting a track that stops after thirty seconds.
            TrackLog.w(TAG, "Qobuz returned a preview for $trackId — refusing")
            return null
        }

        return SourceStream(
            url = file.url,
            format = formatFor(file.formatId),
            durationSec = null,
            sourceConfigId = config.id,
        )
    }

    /**
     * What a given `format_id` is expected to be.
     *
     * Reported as the *request*, not as a measurement — the bytes have not been
     * looked at yet, and a source claiming 24/192 that the decoder then finds
     * running at 16/48 is exactly the failure the player's own format check
     * exists to catch. Nothing here is inferred beyond the ladder Qobuz
     * publishes.
     */
    private fun formatFor(formatId: Int): StreamFormat = when (formatId) {
        QobuzApi.FORMAT_CD -> StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44100)
        QobuzApi.FORMAT_HI_RES_96 -> StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96000)
        QobuzApi.FORMAT_HI_RES_192 -> StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 192000)
        else -> StreamFormat(codec = "flac")
    }
}
