package com.music.swara.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.music.swara.R
import com.music.swara.data.sources.SourceConfig
import com.music.swara.data.sources.SourceHealth
import com.music.swara.data.sources.SourceKind
import com.music.swara.data.sources.SourceRegistry
import com.music.swara.data.sources.qobuz.QobuzSecrets
import com.music.swara.ui.components.FieldConfig
import com.music.swara.ui.components.ServerEditorHost
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sign-in for a source that needs an account rather than an address.
 *
 * The same frosted card as every other editor — see [ServerEditorHost] — with
 * the fields the kind actually authenticates with. Qobuz takes a user token
 * plus an `app_id`/`app_secret` pair; Tidal takes an access token. Saved into
 * the [SourceConfig] itself rather than a side table, because the whole config
 * already lives in encrypted prefs and a token anywhere else would be the one
 * secret in the app with no protection at all.
 *
 * Qobuz's app pair can be left blank: testing then reads it from the web
 * player's own bundle first — the pair is baked into Qobuz's JavaScript
 * rather than published anywhere — and a successful test stores what the
 * scrape found, so Save keeps a complete config rather than the blanks that
 * were typed. A scrape that finds nothing fails the test with the reason
 * rather than storing a guess: a wrong secret signs requests Qobuz refuses,
 * which reads exactly like an expired token and sends the user to re-sign-in
 * when the token was never the problem.
 */
@Composable
internal fun SourceCredentialsAlert(
    hazeState: HazeState,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val fields: List<FieldConfig>
    val description: String
    val test: suspend (List<String>) -> Result<Unit>
    val save: (List<String>) -> Unit

    when (config.kind) {
        SourceKind.QOBUZ -> {
            description = stringResource(R.string.qobuz_signin_description)
            fields = listOf(
                FieldConfig(
                    initial = config.qobuzToken,
                    placeholder = stringResource(R.string.qobuz_user_token),
                    isPassword = true,
                ),
                FieldConfig(
                    initial = config.qobuzAppId,
                    placeholder = stringResource(R.string.qobuz_app_id),
                ),
                FieldConfig(
                    initial = config.qobuzAppSecret,
                    placeholder = stringResource(R.string.qobuz_app_secret),
                    isPassword = true,
                ),
            )
            test = { values ->
                val token = values.getOrNull(0).orEmpty().trim()
                if (token.isBlank()) {
                    Result.failure(IllegalStateException("a user token is needed before anything can be tested"))
                } else {
                    testQobuz(config, token, values.getOrNull(1).orEmpty().trim(), values.getOrNull(2).orEmpty().trim())
                }
            }
            save = { values ->
                // Blank app fields keep what is stored — which is what a
                // successful test just scraped in — rather than wiping a
                // working pair with the blanks that were typed.
                val stored = SourceRegistry.config(config.id) ?: config
                SourceRegistry.update(
                    stored.copy(
                        qobuzToken = values.getOrNull(0).orEmpty().trim(),
                        qobuzAppId = values.getOrNull(1).orEmpty().trim().ifBlank { stored.qobuzAppId },
                        qobuzAppSecret = values.getOrNull(2).orEmpty().trim().ifBlank { stored.qobuzAppSecret },
                    ),
                )
                onSaved()
            }
        }

        SourceKind.TIDAL -> {
            description = config.kind.detail
            fields = listOf(
                FieldConfig(
                    initial = config.tidalToken,
                    placeholder = stringResource(R.string.tidal_access_token),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                ),
            )
            test = { values ->
                val token = values.getOrNull(0).orEmpty().trim()
                if (token.isBlank()) {
                    Result.failure(IllegalStateException("an access token is needed before anything can be tested"))
                } else {
                    testCredentials(config.copy(tidalToken = token))
                }
            }
            save = { values ->
                SourceRegistry.update(config.copy(tidalToken = values.getOrNull(0).orEmpty().trim()))
                onSaved()
            }
        }

        else -> return
    }

    ServerEditorHost(
        hazeState = hazeState,
        title = config.kind.label,
        description = description,
        fields = fields,
        canSubmit = { values -> values.firstOrNull().orEmpty().isNotBlank() },
        testFailedRes = R.string.credentials_test_failed,
        onTest = test,
        onSave = save,
        onDismiss = onDismiss,
    )
}

/**
 * Probes a Qobuz credential set, scraping the app pair first when it was left
 * blank.
 *
 * A successful probe stores the scraped pair before reporting success. That
 * ordering is the point: Test is the step that proved the pair, and leaving
 * it unsaved would have Save store the blanks that were typed — a config the
 * probe just showed does not work.
 */
private suspend fun testQobuz(
    config: SourceConfig,
    token: String,
    appId: String,
    appSecret: String,
): Result<Unit> = withContext(Dispatchers.IO) {
    val resolvedAppId = appId.ifBlank {
        runCatching { QobuzSecrets.scrapeAppId() }.getOrNull().orEmpty()
    }
    val resolvedSecret = appSecret.ifBlank {
        runCatching { QobuzSecrets.scrapeAppSecret() }.getOrNull().orEmpty()
    }
    if (resolvedAppId.isBlank() || resolvedSecret.isBlank()) {
        return@withContext Result.failure(
            IllegalStateException("the web player gave up no app pair — paste the app ID and secret manually"),
        )
    }
    val candidate = config.copy(qobuzToken = token, qobuzAppId = resolvedAppId, qobuzAppSecret = resolvedSecret)
    when (val health = runCatching { SourceRegistry.probeCandidate(candidate) }.getOrElse {
        return@withContext Result.failure(it)
    }) {
        is SourceHealth.Ok -> {
            // Stored only once the probe has passed: an untested scrape is a
            // guess, and a wrong secret fails exactly like an expired token.
            if (appId.isBlank() || appSecret.isBlank()) {
                SourceRegistry.update(candidate)
            }
            Result.success(Unit)
        }

        is SourceHealth.Rejected -> Result.failure(IllegalStateException(health.reason))
        is SourceHealth.Unreachable -> Result.failure(IllegalStateException(health.reason))
    }
}

private suspend fun testCredentials(candidate: SourceConfig): Result<Unit> = withContext(Dispatchers.IO) {
    when (val health = runCatching { SourceRegistry.probeCandidate(candidate) }.getOrElse {
        return@withContext Result.failure(it)
    }) {
        is SourceHealth.Ok -> Result.success(Unit)
        is SourceHealth.Rejected -> Result.failure(IllegalStateException(health.reason))
        is SourceHealth.Unreachable -> Result.failure(IllegalStateException(health.reason))
    }
}
