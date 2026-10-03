package com.music.swara.data.sources.tidal

import com.music.swara.data.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The Tidal HTTP surface: search and the signed stream manifest.
 *
 * Two endpoints, and the split between them is the point:
 *
 *  - **Search needs no credentials.** `tidal.com/v1/search/tracks` answers with
 *    a public token, so [search] works for a signed-out user — which is what
 *    makes the Apple Music catalogue (see [AppleMusicSource]) able to offer
 *    Tidal as a playback target without asking for an account first.
 *  - **Streaming needs an access token.** `api.tidal.com/v1/tracks/{id}/streamUrl`
 *    is authenticated, and it is the one endpoint that returns a manifest
 *    rather than a file.
 *
 * The manifest is base64-encoded JSON carrying a `urls` array and the
 * `mimeType`/`codecs` of the audio inside. Tidal has served that as an
 * MPEG-DASH `.mpd` and as a plain `.flac` URL depending on the track and the
 * tier, so both shapes are handled rather than assumed — see [Manifest].
 */
internal object TidalApi {

    private const val SEARCH_BASE = "https://tidal.com/v1"
    private const val API_BASE = "https://api.tidal.com/v1"

    /**
     * The public token the web player sends.
     *
     * Not a secret and not a user credential — it identifies the *application*
     * to the search endpoint, the same way a User-Agent does. It is sent on
     * every search because the endpoint refuses requests without it.
     */
    private const val PUBLIC_TOKEN = "49YxDN9a2aFV6RTG"

    private const val USER_AGENT = "Swara-Android"

    /** Below this a search hit is not the recording that was asked for. */
    private const val MIN_MATCH_SCORE = 90

    /** A track search, as rows the matcher can score. */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Int?,
        val thumbnailUrl: String?,
        val isrc: String?,
        val isExplicit: Boolean,
    )

    /**
     * A resolved stream: where the bytes are and what is inside them.
     *
     * [isDash] is the load-bearing flag. A DASH manifest is an *index* of the
     * audio, not the audio — handing it to a progressive extractor fails on the
     * only error that path can raise, so the player has to be told which
     * source to build before the first byte is fetched. See
     * [com.music.swara.playback.StreamContainer].
     */
    data class Stream(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val isDash: Boolean,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val isPreview: Boolean,
        /**
         * The manifest's own text, when it is a DASH manifest.
         *
         * Kept because a DASH manifest has to be written to a file before the
         * player can be pointed at it — see [TidalSource]. Null for a direct
         * file, which needs no assembly.
         */
        val manifestText: String? = null,
        /**
         * The init segment followed by the media segments, in play order.
         *
         * Carried so a download can fetch and concatenate them into a file
         * without re-parsing the manifest — see
         * [com.music.swara.download.TidalDashDownload]. Empty for a direct
         * file, which needs no assembly.
         */
        val segmentUrls: List<String> = emptyList(),
    )

    /**
     * Searches the catalogue.
     *
     * @return null when the endpoint did not answer — a miss, not an error.
     *   This source is one link in a chain, and "Tidal did not answer" is
     *   exactly the signal to try the next one.
     */
    fun search(
        query: String,
        limit: Int = 8,
        countryCode: String = "US",
    ): List<Track>? {
        val url = "$SEARCH_BASE/search/tracks"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("countryCode", countryCode)
            .addQueryParameter("locale", "en_US")
            .addQueryParameter("deviceType", "BROWSER")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", "0")
            .build()

        return request(url.toString()) { root ->
            root.optJSONArray("items")?.toTracks()
        }
    }

    /**
     * The stream manifest for [trackId] at [quality].
     *
     * @return null when there is no usable stream, and the caller is expected
     *   to treat that as a miss rather than a failure.
     */
    fun stream(
        trackId: String,
        quality: String,
        accessToken: String,
        countryCode: String = "US",
    ): Stream? {
        val url = "$API_BASE/tracks/$trackId/streamUrl"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("audioquality", quality)
            .addQueryParameter("manifestType", "MPEG_DASH")
            .addQueryParameter("countryCode", countryCode)
            .build()

        return request(url.toString(), accessToken) { root ->
            val manifest = root.stringOrNull("manifest") ?: return@request null
            val mimeType = root.stringOrNull("manifestMimeType")
            parseManifest(manifest, mimeType)
        }
    }

    /**
     * Whether these credentials can reach the streaming endpoint.
     *
     * A token that has expired answers 401 on the first authenticated call, and
     * the symptom — a track that will not play — is indistinguishable from a
     * catalogue miss. Probing separates them so the sources screen can say
     * which one it is.
     */
    fun canStream(accessToken: String, countryCode: String = "US"): Boolean {
        val url = "$API_BASE/tracks/$PROBE_TRACK_ID/streamUrl"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("audioquality", "LOSSLESS")
            .addQueryParameter("manifestType", "MPEG_DASH")
            .addQueryParameter("countryCode", countryCode)
            .build()

        return request(url.toString(), accessToken) { true } == true
    }

    /** A track known to exist, used only to test the token. */
    private const val PROBE_TRACK_ID = "251380688"

    // ── Manifest ────────────────────────────────────────────────────────

    /**
     * Decodes Tidal's base64 manifest payload.
     *
     * Two shapes, and which one arrives is not something the caller controls:
     * a JSON object with a `urls` array is a direct file, and an XML document
     * with `<MPD` is a DASH manifest. Both have carried FLAC.
     */
    private fun parseManifest(
        manifestB64: String,
        declaredMimeType: String?,
    ): Stream? {
        val text = String(android.util.Base64.decode(manifestB64.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        if (text.isBlank()) return null

        if (text.startsWith("{")) {
            val root = JSONObject(text)
            val urls = root.optJSONArray("urls") ?: return null
            val url = (0 until urls.length())
                .mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                .firstOrNull() ?: return null

            val rawMime = root.stringOrNull("mimeType") ?: "audio/flac"
            val codecs = root.stringOrNull("codecs")
                ?: if (rawMime.contains("flac", true)) "flac" else "mp4a.40.2"

            return Stream(
                url = url,
                mimeType = rawMime,
                codecs = codecs,
                isDash = false,
                sampleRateHz = root.doubleOrNull("sampleRate")?.toInt(),
                bitDepth = null,
                isPreview = false,
            )
        }

        if (text.contains("<MPD", true)) {
            val clean = text.replace(
                Regex("""&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)"""),
                "&amp;",
            )
            return parseDashManifest(clean, declaredMimeType, text)
        }

        return null
    }

    /**
     * Reads a DASH manifest's segment list.
     *
     * The segment URLs are what a download has to fetch and concatenate — see
     * [com.music.swara.download.TidalDashDownload]. For *playback* they are
     * handed to Media3's `DashMediaSource` as the manifest itself, so this
     * only has to establish that the manifest is DASH and what codec it
     * advertises.
     */
    private fun parseDashManifest(
        text: String,
        declaredMimeType: String?,
        rawText: String,
    ): Stream? {
        if (!text.contains("<SegmentTemplate", true) &&
            !text.contains("<SegmentList", true) &&
            !text.contains("<SegmentBase", true) &&
            !text.contains("<BaseURL", true)
        ) {
            return null
        }

        val codecs = attr(text, "codecs").ifBlank { "flac" }
        val mime = attr(text, "mimeType").ifBlank { declaredMimeType ?: "audio/mp4" }
        val sampleRate = attr(text, "audioSamplingRate").toIntOrNull()

        return Stream(
            url = "",
            mimeType = mime,
            codecs = codecs,
            isDash = true,
            sampleRateHz = sampleRate,
            bitDepth = null,
            isPreview = false,
            manifestText = rawText,
            segmentUrls = extractSegmentUrls(text),
        )
    }

    /**
     * The segment URLs a DASH manifest lists, init first.
     *
     * Three shapes, tried in order of explicitness: a `SegmentList` names
     * every segment outright; a `SegmentTemplate` names a pattern plus a
     * timeline to count from; a bare `BaseURL` is a single progressive file
     * wearing a manifest's clothes. Relative references resolve against the
     * manifest's own `BaseURL` — which is why a manifest whose segments are
     * relative and whose `BaseURL` is missing is refused rather than guessed
     * at: the segments it names cannot be fetched.
     */
    internal fun extractSegmentUrls(text: String): List<String> {
        extractSegmentListUrls(text).takeIf { it.isNotEmpty() }?.let { return it }

        val baseUrl = firstTagValue(text, "BaseURL").xmlUnescape()
        val init = attr(text, "initialization").xmlUnescape()
        val mediaTemplate = attr(text, "media").xmlUnescape()
        if (init.isBlank() || mediaTemplate.isBlank()) {
            // No template: the only honest reading left is a bare BaseURL,
            // and only when it is fetchable as-is.
            val direct = baseUrl.takeIf { it.startsWith("http", ignoreCase = true) }
            if (direct != null) return listOf(direct)
            return emptyList()
        }

        // Only `$Number$` is expanded. `$Time$` would need the timeline's
        // running offsets rather than a counter, and nothing served here uses
        // it — refusing it is what keeps a misread template from silently
        // fetching the wrong segments.
        if (!mediaTemplate.contains("\$Number\$")) return emptyList()

        var segmentCount = 0
        Regex("""<S\s+[^>]*d="(\d+)"(?:\s+r="(-?\d+)")?[^>]*/?>""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match ->
                val repeat = match.groupValues.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                // `r` is how many times the entry *repeats*, so r="56" is 57
                // segments. Reading it as a count drops the last one and
                // truncates the file by a few seconds.
                segmentCount += repeat + 1
            }
        if (segmentCount <= 0) return emptyList()

        return buildList {
            add(resolveUrl(baseUrl, init))
            for (index in 1..segmentCount) {
                add(resolveUrl(baseUrl, mediaTemplate.replace("\$Number\$", index.toString())))
            }
        }.filter { it.isNotBlank() }
    }

    private fun extractSegmentListUrls(text: String): List<String> {
        val baseUrl = firstTagValue(text, "BaseURL").xmlUnescape()
        val initialization = Regex(
            """<Initialization\b[^>]*sourceURL="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)?.xmlUnescape()
        val media = Regex(
            """<SegmentURL\b[^>]*media="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.xmlUnescape() }.toList()
        return buildList {
            initialization?.let { add(resolveUrl(baseUrl, it)) }
            media.forEach { add(resolveUrl(baseUrl, it)) }
        }.filter { it.isNotBlank() }
    }

    private fun firstTagValue(
        text: String,
        tag: String,
    ): String =
        Regex("""<$tag[^>]*>([^<]*)</$tag>""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty().trim()

    private fun resolveUrl(
        baseUrl: String,
        path: String,
    ): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }
        if (baseUrl.isBlank()) return path
        val absolute = baseUrl.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        } ?: return path
        return runCatching {
            absolute.toHttpUrlOrNull()?.resolve(path)?.toString()
        }.getOrNull() ?: (absolute.trimEnd('/') + "/" + path.trimStart('/'))
    }

    private fun String.xmlUnescape(): String =
        replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    private fun attr(
        text: String,
        name: String,
    ): String =
        Regex("""$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()

    // ── Transport ────────────────────────────────────────────────────────

    private fun <T> request(
        url: String,
        accessToken: String? = null,
        parse: (JSONObject) -> T?,
    ): T? {
        val request = Request
            .Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (accessToken != null) {
                    header("Authorization", "Bearer $accessToken")
                } else {
                    header("x-tidal-token", PUBLIC_TOKEN)
                }
            }
            .get()
            .build()

        return Http.client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw TidalAuthException("HTTP ${response.code}")
            }
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
            parse(root)
        }
    }

    private fun JSONArray.toTracks(): List<Track> =
        (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.toTrack()
        }

    private fun JSONObject.toTrack(): Track? {
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val artist = optJSONArray("artists")
            ?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.stringOrNull("name") }
                    .joinToString(", ")
            }
            .orEmpty()
            .ifBlank { stringOrNull("artist").orEmpty() }

        return Track(
            id = id,
            title = title,
            artist = artist,
            album = optJSONObject("album")?.stringOrNull("title"),
            durationSec = longOrNull("duration")?.toInt(),
            thumbnailUrl = albumCoverUrl(),
            isrc = stringOrNull("isrc"),
            isExplicit = optBoolean("explicit", false),
        )
    }

    /**
     * The album cover, from the cover id Tidal's rows carry.
     *
     * The id is a UUID with its dashes turned into path segments —
     * `a-b-c-d` becomes `a/b/c/d` — and the size is chosen per row. A missing
     * cover is not a reason to drop the track, so this returns null and the
     * caller falls back to whatever the player shows for artwork-less rows.
     */
    private fun JSONObject.albumCoverUrl(): String? {
        val coverId = stringOrNull("cover") ?: stringOrNull("albumCover") ?: return null
        val normalized = coverId.replace("-", "/")
        return "https://resources.tidal.com/images/$normalized/320x320.jpg"
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key)) optLong(key).takeIf { it > 0L } else null

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key)) optDouble(key).takeIf { it > 0.0 } else null

    // ── Matching ─────────────────────────────────────────────────────────

    /**
     * The best catalogue match for a track the caller already knows.
     *
     * Scored rather than taken on faith, and on the same terms as the Qobuz
     * matcher: a Tidal search for a title that has been remixed and covered
     * returns all of them, and the first hit is frequently not the one that was
     * asked for.
     *
     * The threshold is higher than Qobuz's because Tidal's rows are better
     * labelled — an ISRC is usually present, which is a stronger signal than any
     * amount of title similarity.
     */
    fun bestMatch(
        wantedTitle: String,
        wantedArtists: List<String>,
        wantedDurationSec: Int?,
        tracks: List<Track>,
    ): Track? {
        val title = wantedTitle.titleMatchNormalized()
        val artists = wantedArtists.map { it.normalized() }.filter { it.isNotBlank() }

        var best: Track? = null
        var bestScore = Int.MIN_VALUE

        for (track in tracks) {
            val score = scoreMatch(
                wantedTitle = title,
                wantedArtists = artists,
                candidateTitle = track.title.titleMatchNormalized(),
                candidateArtist = track.artist.normalized(),
                wantedDurationSec = wantedDurationSec,
                candidateDurationSec = track.durationSec,
                candidateIsrc = track.isrc,
            )
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }

        return best?.takeIf { bestScore >= MIN_MATCH_SCORE }
    }

    private fun scoreMatch(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationSec: Int?,
        candidateDurationSec: Int?,
        candidateIsrc: String?,
    ): Int {
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return Int.MIN_VALUE
        if (hasVersionMismatch(" $wantedTitle ", " $candidateTitle ")) return Int.MIN_VALUE

        val titleOverlap = tokenOverlap(significantTokens(wantedTitle), significantTokens(candidateTitle))
        if (titleOverlap < 0.5) return Int.MIN_VALUE

        var score = (titleOverlap * 100).toInt()
        if (wantedArtists.isNotEmpty() && candidateArtist.isNotBlank()) {
            val artistOverlap =
                wantedArtists.maxOf { tokenOverlap(significantTokens(it), significantTokens(candidateArtist)) }
            score += (artistOverlap * 60).toInt()
        }
        if (wantedDurationSec != null && candidateDurationSec != null) {
            if (durationMatches(wantedDurationSec, candidateDurationSec)) score += 30 else score -= 40
        }
        return score
    }

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { (it.length >= 2 || it.any { ch -> ch.code >= 0x2E80 }) && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun durationMatches(
        wantedDurationSec: Int?,
        candidateDurationSec: Int?,
    ): Boolean {
        if (wantedDurationSec == null || candidateDurationSec == null) return true
        return kotlin.math.abs(wantedDurationSec - candidateDurationSec) <= 45
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean = VERSION_TOKENS.any { token ->
        wanted.contains(" $token ") != candidate.contains(" $token ")
    }

    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")

    private val VERSION_TOKENS =
        setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")

    private fun String.normalized(): String =
        lowercase(Locale.US)
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun String.titleMatchNormalized(): String =
        normalized()
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Thrown when Tidal refuses the credentials rather than the request. */
class TidalAuthException(message: String) : Exception(message)
