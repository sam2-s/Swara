package com.music.swara.data.sources.apple

import com.music.swara.data.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * The Apple Music catalogue, through the public iTunes Search API.
 *
 * Deliberately not a streaming source. Apple serves ALAC where it serves
 * lossless at all, and its catalogue endpoint answers without credentials, so
 * the useful thing to do with it is search and import — a row found here is
 * played through YouTube Music, which is the only source with a streamable
 * rendition of the same recording.
 *
 * That is a real limitation and not a cosmetic one: an Apple Music row plays at
 * YouTube's Opus ceiling, not at Apple's. It is the trade for needing no Apple
 * credentials at all, and it is why [AppleMusicSource.stream] returns null
 * rather than pretending otherwise.
 *
 * The endpoint is `itunes.apple.com/search`, which is public and rate-limited
 * but needs no token, no app id and no secret. Three entity types are asked
 * for in parallel — songs, albums, artists — because a search for a title that
 * is also an artist's name should return both.
 */
internal object AppleMusicCatalog {

    private const val SEARCH_ENDPOINT = "https://itunes.apple.com/search"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** A catalogue row, whatever entity type it turned out to be. */
    data class Item(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUrl: String?,
        val durationSec: Int?,
        val viewUrl: String?,
        val kind: Kind,
        val trackCount: Int?,
        val releaseYear: String?,
        val genre: String?,
        val isExplicit: Boolean,
    ) {
        enum class Kind { TRACK, ALBUM, ARTIST }

        /** The identity used to dedupe across the three entity responses. */
        val key: String get() = "${kind.name}/$id"
    }

    /**
     * Searches the catalogue.
     *
     * @return null when the endpoint did not answer — a miss, not an error.
     *   This source is one link in a chain, and "Apple did not answer" is
     *   exactly the signal to try the next one.
     */
    fun search(
        query: String,
        limit: Int = 25,
    ): List<Item>? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val entities = listOf("song", "album", "musicArtist")
        val items = entities.mapNotNull { entity ->
            runCatching { request(trimmed, entity, limit) }.getOrNull()
        }.flatMap { it }

        // Deduped across the three responses: a song and its album both match
        // the same query, and showing both is noise rather than choice.
        return items.distinctBy { it.key }
            .sortedByDescending { it.kind.ordinal }
            .take(limit)
            .ifEmpty { null }
    }

    private fun request(
        query: String,
        entity: String,
        limit: Int,
    ): List<Item> {
        val url = SEARCH_ENDPOINT
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", entity)
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request
            .Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val decoded = runCatching { json.decodeFromString<ITunesSearchResponse>(body) }.getOrNull()
                ?: return emptyList()
            return decoded.results.mapNotNull { it.toItem() }
        }
    }

    private fun ITunesResult.toItem(): Item? {
        return when {
            trackId != null && trackName != null -> Item(
                id = trackId.toString(),
                title = trackName,
                artist = artistName.orEmpty(),
                album = collectionName,
                artworkUrl = artworkUrl100?.let(::upscaleArtwork),
                durationSec = trackTimeMillis?.let { (it / 1000).toInt() },
                viewUrl = trackViewUrl,
                kind = Item.Kind.TRACK,
                trackCount = null,
                releaseYear = null,
                genre = null,
                isExplicit = trackExplicitness == "explicit",
            )

            collectionId != null && collectionName != null -> Item(
                id = collectionId.toString(),
                title = collectionName,
                artist = collectionArtistName ?: artistName.orEmpty(),
                album = null,
                artworkUrl = artworkUrl100?.let(::upscaleArtwork),
                durationSec = null,
                viewUrl = collectionViewUrl,
                kind = Item.Kind.ALBUM,
                trackCount = trackCount,
                releaseYear = releaseDate?.take(4),
                genre = null,
                isExplicit = collectionExplicitness == "explicit",
            )

            artistId != null && artistName != null -> Item(
                id = artistId.toString(),
                title = artistName,
                artist = artistName,
                album = null,
                artworkUrl = null,
                durationSec = null,
                viewUrl = artistViewUrl,
                kind = Item.Kind.ARTIST,
                trackCount = null,
                releaseYear = null,
                genre = primaryGenreName,
                isExplicit = false,
            )

            else -> null
        }
    }

    /**
     * A larger cover, when the row carries the small one.
     *
     * The API hands back `100x100bb.jpg`; the same path at `200x200bb.jpg` is
     * the same image at twice the size. Worth doing because the alternative is
     * a cover that looks soft on every screen this app draws it on.
     */
    private fun upscaleArtwork(url: String?): String? =
        url?.replace("/100x100bb.jpg", "/200x200bb.jpg")
}

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesResult> = emptyList(),
)

@Serializable
private data class ITunesResult(
    val wrapperType: String? = null,
    val kind: String? = null,
    val artistId: Long? = null,
    val collectionId: Long? = null,
    val trackId: Long? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackName: String? = null,
    val artistViewUrl: String? = null,
    val collectionViewUrl: String? = null,
    val trackViewUrl: String? = null,
    val previewUrl: String? = null,
    val artworkUrl100: String? = null,
    val releaseDate: String? = null,
    val trackCount: Int? = null,
    val trackTimeMillis: Long? = null,
    val primaryGenreName: String? = null,
    val trackExplicitness: String? = null,
    val collectionExplicitness: String? = null,
    @SerialName("collectionArtistName")
    val collectionArtistName: String? = null,
)
