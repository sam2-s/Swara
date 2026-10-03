package com.music.swara.data.sources.qobuz

import com.music.swara.data.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * The Qobuz HTTP surface: search, track lookup and the signed file URL.
 *
 * Two things here are not obvious and both are load-bearing:
 *
 *  - **The signature is an MD5 over a specific concatenation, not a hash of
 *    the request.** `request_sig` covers `trackgetFileUrl` + the sorted
 *    parameters + the request timestamp + the app secret, in that exact order
 *    with no separators. Get the order or the separators wrong and Qobuz answers
 *    `invalid request signature` — a 400 that looks identical to a bad token
 *    from the outside, which is why [fileUrl] reports the distinction rather
 *    than collapsing both into "failed".
 *  - **`format_id` is the whole quality ladder.** 5 is 16/44.1, 6 is 24/≤96 and
 *    7 is 24/>96. There is no "give me your best" — asking for 7 on a CD-quality
 *    master returns a 16-bit file rather than an error, so a caller that wants
 *    the best available has to ask for the top rung and accept the downgrade,
 *    which is what [QobuzSource] does.
 *
 * The `app_id`/`app_secret` pair is not published. It is scraped from Qobuz's
 * own web player bundle on first use and cached, which is why it lives in
 * [QobuzSecrets] rather than in a constant: it rotates, and when it does the
 * fix is one file rather than a release.
 */
internal object QobuzApi {

    private const val API_BASE = "https://www.qobuz.com/api.json/0.2"
    private const val USER_AGENT = "Swara-Android"

    /** 16-bit / 44.1 kHz — CD quality. */
    const val FORMAT_CD = 5

    /** 24-bit / up to 96 kHz. */
    const val FORMAT_HI_RES_96 = 6

    /** 24-bit / up to 192 kHz — the top rung, and the one to ask for. */
    const val FORMAT_HI_RES_192 = 7

    /** Below this a search hit is not the recording that was asked for. */
    private const val MIN_MATCH_SCORE = 60

    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "feat", "ft", "featuring", "with")

    private val VERSION_TOKENS =
        setOf("remix", "acoustic", "live", "instrumental", "demo", "edit", "mix", "version")

    /** A track search, as rows the matcher can score. */
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Int?,
        val thumbnailUrl: String?,
        val isExplicit: Boolean,
    )

    /** A resolved stream URL and the format it is expected to be. */
    data class AudioFile(
        val url: String,
        val formatId: Int,
        val isPreview: Boolean,
    )

    /**
     * Searches the catalogue.
     *
     * @return null when the request did not succeed, which is a *miss* rather
     *   than an error — the caller is a source in a chain, and "Qobuz did not
     *   answer" is exactly the signal to try the next one.
     */
    fun search(query: String, credentials: QobuzCredentials, limit: Int = 10): List<Track>? {
        val url = "$API_BASE/track/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", credentials.appId)
            .build()
        return request(url.toString(), credentials) { root -> root.findTrackItems()?.toTracks() }
    }

    /**
     * The signed URL for [trackId] at [formatId].
     *
     * @return null when there is no usable URL, and [Preview] when what came
     *   back is a 30-second sample. The distinction matters: a preview is a
     *   *wrong answer that looks like a right one* — it plays, it is the right
     *   song, and it stops after half a minute. Handing it to the player would
     *   be a track that silently ends, so it is refused here and the source
     *   reports that it has nothing rather than serving a sample.
     */
    fun fileUrl(
        trackId: String,
        formatId: Int,
        credentials: QobuzCredentials,
    ): AudioFile? {
        val ts = System.currentTimeMillis() / 1000L
        // The concatenation order and the absence of separators are both part of
        // the scheme — see the class doc.
        val sig = md5("trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$ts${credentials.appSecret}")
        val url = "$API_BASE/track/getFileUrl"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("request_ts", ts.toString())
            .addQueryParameter("request_sig", sig)
            .addQueryParameter("track_id", trackId)
            .addQueryParameter("format_id", formatId.toString())
            .addQueryParameter("intent", "stream")
            .addQueryParameter("app_id", credentials.appId)
            .build()

        return request(url.toString(), credentials) { root ->
            val streamUrl = root.findStreamUrl() ?: return@request null
            val isPreview = root.looksLikePreview()
            AudioFile(url = streamUrl, formatId = formatId, isPreview = isPreview)
        }
    }

    /**
     * Whether these credentials can actually sign a request.
     *
     * Cheaper than it looks and worth more than it costs: a token pasted in
     * from a browser is often valid while the `app_secret` half is stale, and
     * the two failures are indistinguishable from the outside — both answer
     * `invalid request signature`. Probing with a real track id separates them,
     * so the sources screen can say which half is wrong instead of sending the
     * user to re-enter a token that was never the problem.
     */
    fun canSign(credentials: QobuzCredentials): Boolean {
        val ts = System.currentTimeMillis() / 1000L
        val sig = md5("trackgetFileUrlformat_id${FORMAT_CD}intentstreamtrack_id$PROBE_TRACK_ID$ts${credentials.appSecret}")
        val url = "$API_BASE/track/getFileUrl"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("request_ts", ts.toString())
            .addQueryParameter("request_sig", sig)
            .addQueryParameter("track_id", PROBE_TRACK_ID)
            .addQueryParameter("format_id", FORMAT_CD.toString())
            .addQueryParameter("intent", "stream")
            .addQueryParameter("app_id", credentials.appId)
            .build()

        return request(url.toString(), credentials) { true } == true
    }

    /** A track known to exist in the catalogue, used only to test signing. */
    private const val PROBE_TRACK_ID = "5966783"

    /**
     * One authenticated GET, parsed by [parse].
     *
     * Every request goes through here so the two headers Qobuz checks are set
     * in exactly one place. `X-App-Id` names the application the token was
     * issued to; `X-User-Auth-Token` is the subscriber. A request with a valid
     * token and a foreign `app_id` is refused, which is why the pair has to
     * travel together — see [QobuzCredentials].
     */
    private fun <T> request(
        url: String,
        credentials: QobuzCredentials,
        parse: (JSONObject) -> T?,
    ): T? {
        val request = Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-App-Id", credentials.appId)
            .header("X-User-Auth-Token", credentials.token)
            .get()
            .build()

        return Http.client.newCall(request).execute().use { response ->
            // 401/403 mean the credentials are wrong, which is a different
            // problem from the catalogue not having the track. Both are a miss
            // as far as the source chain is concerned, but the health probe
            // needs to tell them apart, so the code is surfaced rather than
            // folded into a null.
            if (response.code == 401 || response.code == 403) {
                throw QobuzAuthException("HTTP ${response.code}")
            }
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
            parse(root)
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ── Response shaping ────────────────────────────────────────────────

    private fun JSONArray.toTracks(): List<Track> =
        (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.toTrack()
        }

    private fun JSONObject.toTrack(): Track? {
        val id = trackId() ?: return null
        val title = stringOrNull("title") ?: return null
        val artist = performerName() ?: albumArtistName() ?: ""
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = albumTitle(),
            durationSec = longOrNull("duration")?.toInt(),
            thumbnailUrl = thumbnailUrl(),
            isExplicit = optBoolean("explicit", false),
        )
    }

    private fun JSONObject.trackId(): String? =
        stringOrNull("id") ?: longOrNull("id")?.toString() ?: stringOrNull("track_id")

    private fun JSONObject.performerName(): String? =
        optJSONObject("performer")?.stringOrNull("name")
            ?: optJSONObject("artist")?.stringOrNull("name")
            ?: stringOrNull("artist")

    private fun JSONObject.albumArtistName(): String? =
        optJSONObject("album")?.optJSONObject("artist")?.stringOrNull("name")

    private fun JSONObject.albumTitle(): String? =
        optJSONObject("album")?.stringOrNull("title")

    /**
     * The largest cover art the row carries.
     *
     * Qobuz nests this three ways depending on which endpoint produced the row
     * — `album.image` as an object with `large`/`medium`/`small`, as an array of
     * the same, or as a bare URL string — so all three are tried rather than the
     * one the search endpoint happens to use.
     */
    private fun JSONObject.thumbnailUrl(): String? {
        optJSONObject("album")?.let { album ->
            (album.opt("image") as? JSONObject)?.let { image ->
                image.stringOrNull("large")?.let { return it }
                image.stringOrNull("medium")?.let { return it }
                image.stringOrNull("small")?.let { return it }
            }
            (album.opt("image") as? JSONArray)?.let { images ->
                for (i in 0 until images.length()) {
                    images.optJSONObject(i)?.stringOrNull("url")?.let { return it }
                }
            }
            album.stringOrNull("cover")?.let { return it }
        }
        return stringOrNull("thumbnail") ?: stringOrNull("cover")
    }

    private fun JSONObject.findTrackItems(): JSONArray? {
        val data = optJSONObject("data") ?: this
        data.optJSONObject("tracks")?.optJSONArray("items")?.let { return it }
        data.optJSONArray("items")?.let { return it }
        data.optJSONArray("tracks")?.let { return it }
        optJSONArray("items")?.let { return it }
        optJSONArray("tracks")?.let { return it }
        return null
    }

    private fun JSONObject.findStreamUrl(): String? {
        for (key in listOf("url", "downloadUrl", "download_url", "stream_url", "streamUrl", "link")) {
            stringOrNull(key)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        optJSONObject("data")?.findStreamUrl()?.let { return it }
        val names = keys()
        while (names.hasNext()) {
            when (val value = opt(names.next())) {
                is JSONObject -> value.findStreamUrl()?.let { return it }
                is String -> if (value.startsWith("http") && value.contains(".flac", true)) return value
            }
        }
        return null
    }

    private fun JSONObject.looksLikePreview(): Boolean {
        val data = optJSONObject("data") ?: this
        if (data.optBoolean("sample", false)) return true
        if (data.optBoolean("preview", false)) return true
        data.stringOrNull("type")?.let { if (it.equals("preview", true) || it.equals("sample", true)) return true }
        return false
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key)) optLong(key).takeIf { it > 0L } else null

    // ── Matching ────────────────────────────────────────────────────────

    /**
     * The best catalogue match for a track the caller already knows.
     *
     * Scored rather than taken on faith. A Qobuz search for a title that has
     * been remixed, re-recorded and covered returns all of them, and the first
     * hit is frequently not the one that was asked for — which plays the wrong
     * recording under the right title, the one failure a listener cannot
     * diagnose from the player.
     *
     * The thresholds are the ones ArchiveTune settled on: a title overlap below
     * half is not the same song, a version token present on one side and absent
     * on the other is a different release however similar the words look, and a
     * duration gap over 45s is a different cut.
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
            // CJK single-character words are meaningful, so they keep their place
            // in the overlap; every other script keeps the length >= 2 floor.
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

    private fun String.normalized(): String =
        lowercase(Locale.US)
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("\\p{Mn}+"), "")
            // Unicode-aware on purpose. The `[^a-z0-9]+` form this replaced
            // deleted every non-Latin character, which blanked Hindi, Bengali,
            // Arabic and CJK titles before the matcher ever saw them.
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun String.titleMatchNormalized(): String =
        normalized()
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** The query actually sent to the catalogue, with the noise trimmed off. */
    fun searchQuery(
        title: String,
        artists: List<String>,
    ): String {
        val artist = artists.firstOrNull().orEmpty().searchArtist()
        val trimmed = title.searchTitle()
        return listOf(artist, trimmed)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { title }
    }

    private fun String.searchTitle(): String =
        trim()
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.searchArtist(): String =
        trim()
            .substringBefore(',')
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Thrown when Qobuz refuses the credentials rather than the request. */
class QobuzAuthException(message: String) : Exception(message)
