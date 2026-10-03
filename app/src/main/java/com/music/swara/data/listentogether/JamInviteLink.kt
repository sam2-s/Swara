package com.music.swara.data.listentogether

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedJamInvite(
    val code: String,
    val serverUrl: String? = null,
)

/**
 * Relays a Swara web or scheme invite from [com.music.swara.MainActivity] to Compose.
 *
 * The web-invite half is deployment-specific and therefore opt-in. Swara ships
 * no web domain of its own, and an `autoVerify` intent filter can only ever
 * match a host whose `assetlinks.json` names *this* signing certificate — so
 * claiming one would produce a filter that silently never verifies. Rather than
 * hardcode a borrowed domain, the https branch is enabled only when the
 * operator supplies their own origin at build time (see `LISTEN_TOGETHER_ORIGIN`
 * in the app's build script). Unset, the scheme form is the only invite this
 * build accepts, which is the same contract the party server address already
 * has: a blank default is a supported state, not a broken build.
 */
object JamInviteLink {

    /**
     * The public https origin that serves `/invite/<CODE>` links, without a
     * trailing slash. Null — the default — means no web invites in this build.
     */
    @Volatile
    var origin: String? = null
        private set

    /** Configured at startup from the build's `LISTEN_TOGETHER_ORIGIN`. */
    fun setOrigin(value: String?) {
        origin = value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    /** The host an https invite must be addressed to, or null when unset. */
    private val webHost: String? get() = origin?.let { runCatching { URI(it).host }.getOrNull() }

    private const val EXTRA_CONSUMED = "swara.jamInviteConsumed"
    private const val CUSTOM_SCHEME = "swara"
    private const val CUSTOM_HOST = "party"

    private val _pending = MutableStateFlow<ParsedJamInvite?>(null)
    val pending: StateFlow<ParsedJamInvite?> = _pending.asStateFlow()

    /** Reads a web invite from a cold launch or a new intent on the existing task. */
    fun consume(intent: Intent?): Boolean {
        if (
            intent == null ||
            intent.action != Intent.ACTION_VIEW ||
            intent.getBooleanExtra(EXTRA_CONSUMED, false)
        ) return false

        val invite = parseInvite(intent.dataString) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = invite
        return true
    }

    fun handled() {
        _pending.value = null
    }

    /** Returns the normalized party code only for the public invite URL shape. */
    fun parse(value: String?): String? = parseInvite(value)?.code

    /**
     * Parses an incoming invite:
     * 1. swara://party/<CODE>?server=<SERVER>
     * 2. https://<configured origin>/invite/<CODE>?server=<SERVER>, when an
     *    origin was configured at build time — see [setOrigin].
     */
    fun parseInvite(value: String?): ParsedJamInvite? {
        val uri = runCatching { URI(value ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery
        val server = extractQueryParam(query, "server")?.let { sanitizeServerUrl(it) }

        // 1. Custom scheme: swara://party/<CODE> or swara://party?code=<CODE>
        if (scheme == CUSTOM_SCHEME && host == CUSTOM_HOST) {
            val pathPart = uri.path.orEmpty().trim('/').takeIf { it.isNotBlank() }
            val candidate = pathPart ?: extractQueryParam(query, "code") ?: return null
            val code = cleanCode(candidate) ?: return null
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        // 2. Configured web origin: https://<origin>/invite/<CODE>
        if (scheme == "https" && webHost != null && host == webHost) {
            val match = INVITE_PATH.matchEntire(uri.path.orEmpty()) ?: return null
            val code = match.groupValues[1].uppercase()
            return ParsedJamInvite(code = code, serverUrl = server)
        }

        return null
    }

    /**
     * The link to share for [code].
     *
     * Falls back to the scheme form when no origin is configured, so sharing
     * always produces something the app itself will accept — a link to a host
     * nobody is serving would be worse than a scheme link that is guaranteed
     * to round-trip.
     */
    fun url(code: String, customServer: String? = null): String {
        val normalized = code.uppercase()
        val base = customServer?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (base != null && !base.equals(origin, ignoreCase = true)) {
            return "$base/invite/$normalized"
        }
        val siteOrigin = origin ?: return schemeUrl(normalized, base)
        return "$siteOrigin/invite/$normalized"
    }

    fun schemeUrl(code: String, customServer: String? = null): String {
        val normalizedCode = code.uppercase()
        val base = customServer?.trim()?.trimEnd('/')
        return if (!base.isNullOrBlank()) {
            val encoded = runCatching { URLEncoder.encode(base, "UTF-8") }.getOrDefault(base)
            "swara://party/$normalizedCode?server=$encoded"
        } else {
            "swara://party/$normalizedCode"
        }
    }

    private fun cleanCode(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }.uppercase()
        return if (cleaned.length == ListenTogether.CODE_LENGTH) cleaned else null
    }

    private fun extractQueryParam(query: String?, paramName: String): String? {
        if (query.isNullOrBlank()) return null
        return query.split('&').asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0].equals(paramName, ignoreCase = true) }
            ?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }

    private fun sanitizeServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    private val INVITE_PATH = Regex("""/invite/([A-Za-z0-9]{${ListenTogether.CODE_LENGTH}})""")
}

