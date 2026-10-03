package com.music.swara.data.sources.qobuz

/**
 * The three fields a Qobuz request is authenticated with.
 *
 * Deliberately one object rather than three loose strings, because the failure
 * mode of splitting them is silent: a token without its `app_secret` produces
 * `invalid request signature`, which is indistinguishable from a bad token
 * unless the two are validated together. [isComplete] is the check the sources
 * screen uses to decide whether this is worth contacting at all.
 */
data class QobuzCredentials(
    val token: String,
    val appId: String,
    val appSecret: String,
) {
    /**
     * Whether the pair is whole enough to sign a request.
     *
     * All three, not just the token. Qobuz signs with the `app_secret`, so a
     * token pasted in from a browser session — the obvious way to get one — is
     * valid on its own and still cannot produce a signature without the other
     * half, and the resulting 400 reads as "wrong token" when the token was
     * never the problem.
     */
    val isComplete: Boolean
        get() = token.isNotBlank() && appId.isNotBlank() && appSecret.isNotBlank()

    companion object {
        /** The format_id that asks for the best the catalogue has. */
        const val BEST_FORMAT_ID = QobuzApi.FORMAT_HI_RES_192
    }
}
