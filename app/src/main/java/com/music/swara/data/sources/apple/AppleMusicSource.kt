package com.music.swara.data.sources.apple

import com.music.swara.data.TrackLog
import com.music.swara.data.model.Song
import com.music.swara.data.sources.MusicSource
import com.music.swara.data.sources.SourceConfig
import com.music.swara.data.sources.SourceHealth
import com.music.swara.data.sources.SourceKind
import com.music.swara.data.sources.SourceRegistry
import com.music.swara.data.sources.SourceStream
import com.music.swara.data.sources.StreamRequest

private const val TAG = "Swara"

/**
 * Apple Music as a *catalogue* only.
 *
 * [stream] returns null, always and by construction. Apple serves ALAC where it
 * serves lossless at all, and the only rendition this app can actually play is
 * YouTube Music's — so an Apple row is a *finding*, not a stream: it tells the
 * listener that the recording exists, what it is called and how long it is, and
 * the play button hands the same title and artist to YouTube.
 *
 * That makes this the one source whose rows are worse than useless if they are
 * mistaken about what they are. A row that claims to be a track and is
 * actually an album plays the wrong thing entirely, so [search] only returns
 * tracks — albums and artists are browsed, not played, and the player has no
 * way to represent either.
 */
class AppleMusicSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.APPLE
    override val displayName: String get() = config.label.ifBlank { SourceKind.APPLE.label }

    /**
     * Always Ok.
     *
     * There is nothing to be unreachable *to*: the iTunes Search API is public,
     * needs no credentials and has no per-account state. A failure here is a
     * network problem, not a service problem, and it will show up on the first
     * search as an empty result rather than as a red dot on a settings row.
     */
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    /**
     * Searches the Apple Music catalogue.
     *
     * Only tracks are returned, and that is a real restriction rather than a
     * shortcut: the player can play a track and has no representation for an
     * album or an artist, so returning either would produce rows that look
     * playable and are not.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val items = runCatching { AppleMusicCatalog.search(query, limit) }.getOrNull() ?: return emptyList()

        return items
            .filter { it.kind == AppleMusicCatalog.Item.Kind.TRACK }
            .take(limit)
            .map { item ->
                Song(
                    videoId = SourceRegistry.trackKey(config.id, item.id),
                    title = item.title,
                    artist = item.artist,
                    albumName = item.album,
                    thumbnailUrl = item.artworkUrl,
                    durationText = item.durationSec?.let { seconds ->
                        "${seconds / 60}:${String.format("%02d", seconds % 60)}"
                    },
                    // Carried on the row rather than discovered at stream time
                    // because it is the only thing that distinguishes two
                    // catalogues holding the same track — see Song.sourceQuality.
                    sourceQuality = "APPLE",
                    isExplicit = item.isExplicit,
                )
            }
    }

    /**
     * Always null.
     *
     * Not a failure and not a miss — a statement. Apple Music has no stream
     * this app can play, so there is nothing to return, and the caller is
     * expected to take the row's title and artist to a source that does.
     *
     * Written out rather than left as a TODO because the distinction matters:
     * a null from a source that *could* have served the track is a reason to
     * try the next one, and that is exactly what should happen here.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "Apple Music is browse-only — no stream for $trackId")
        return null
    }
}
