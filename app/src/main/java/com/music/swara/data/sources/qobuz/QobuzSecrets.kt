package com.music.swara.data.sources.qobuz

import com.music.swara.data.Http
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Where the Qobuz `app_id` / `app_secret` pair comes from.
 *
 * Qobuz publishes neither. They are baked into the web player's JavaScript
 * bundle, so they are read from there — which is why this is isolated in one
 * file and why a break here is expected to be a small fix rather than a
 * redesign: Qobuz rotates the pair whenever they ship a new bundle, and the
 * symptom is a 400 `invalid request signature` that looks exactly like a bad
 * token.
 *
 * The scrape is a last resort, not the primary path. A user who already has a
 * pair — from their own browser session, or from a previous scrape — should
 * paste it, because a scraped secret is only as fresh as the bundle that was
 * fetched and there is no way to tell a stale one from a live one without
 * trying it.
 */
internal object QobuzSecrets {

    private const val USER_AGENT = "Swara-Android"

    /** The web player entry point, whose scripts carry the credentials. */
    private const val WEB_PLAYER_URL = "https://play.qobuz.com/login"

    /**
     * A 32-character lowercase hex string standing alone in the bundle.
     *
     * The shape is the only signal available: the secret is not labelled, so it
     * is found by pattern and then *confirmed* by trying it. The md5 of an
     * empty string is skipped because it turns up in bundles as a constant and
     * is not a secret.
     */
    private val SECRET_REGEX = Regex("[^a-fA-F0-9]([a-f0-9]{32})[^a-fA-F0-9]")

    private const val NOT_A_SECRET = "d41d8cd98f00b204e9800998ecf8427e"

    private val client = Http.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * The `app_id` the web player identifies itself with.
     *
     * Read from the same bundle as the secret, and for the same reason: it is
     * not published anywhere, and a token is only valid alongside the `app_id`
     * it was issued to.
     */
    fun scrapeAppId(): String? = bundleScripts().asSequence()
        .mapNotNull { script -> APP_ID_REGEX.find(script)?.groupValues?.getOrNull(1) }
        .firstOrNull { it.length >= 3 }

    /**
     * The `app_secret`, or null when the bundle did not yield one.
     *
     * @return null rather than a guess. A wrong secret is worse than a missing
     *   one: it produces a signature Qobuz refuses, which is indistinguishable
     *   from an expired token and sends the user to re-authenticate when the
     *   token was never the problem.
     */
    fun scrapeAppSecret(): String? = bundleScripts().asSequence()
        .flatMap { SECRET_REGEX.findAll(it).map { match -> match.groupValues[1] } }
        .firstOrNull { it != NOT_A_SECRET }

    /** The player's own bundle URLs, which is where both values live. */
    private fun bundleScripts(): List<String> {
        val page = fetch(WEB_PLAYER_URL) ?: return emptyList()
        val urls = SCRIPT_SRC_REGEX.findAll(page).map { it.groupValues[1] }.toList()
        // Fetched sequentially rather than raced: there are a handful, they are
        // small, and the first script that carries the answer is enough — a
        // thread pool here would spend four connections to save milliseconds on
        // a path that runs once per sign-in.
        return urls.mapNotNull { fetch(it) }
    }

    private fun fetch(url: String): String? = runCatching {
        client.newCall(
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private val SCRIPT_SRC_REGEX = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""", RegexOption.IGNORE_CASE)

    /**
     * `app_id` as the player sets it.
     *
     * It appears in the bundle as a bare assignment rather than a labelled
     * field, so the pattern matches the assignment form and the header form and
     * takes whichever the bundle uses.
     */
    private val APP_ID_REGEX = Regex("""(?:\.app_id\s*=\s*["']([A-Za-z0-9]+)["']|["']app_id["']\s*:\s*["']([A-Za-z0-9]+)["'])""")
}
