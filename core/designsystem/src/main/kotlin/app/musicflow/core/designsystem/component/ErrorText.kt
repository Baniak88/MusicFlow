package app.musicflow.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.musicflow.core.designsystem.R
import app.musicflow.core.model.ProviderError

/** Maps a classified provider error to a clear, localised message. */
@StringRes
fun ProviderError.messageRes(): Int = when (this) {
    is ProviderError.Network -> R.string.mf_error_network
    is ProviderError.RateLimited -> R.string.mf_error_rate_limited
    is ProviderError.StreamExpired -> R.string.mf_error_expired
    is ProviderError.Authentication -> R.string.mf_error_auth
    is ProviderError.Unavailable -> R.string.mf_error_unavailable
    is ProviderError.Restricted -> when (reason) {
        ProviderError.Restricted.Reason.GEOGRAPHIC -> R.string.mf_error_restricted_geo
        ProviderError.Restricted.Reason.AGE -> R.string.mf_error_restricted_age
        ProviderError.Restricted.Reason.PAID -> R.string.mf_error_restricted_paid
        ProviderError.Restricted.Reason.OTHER -> R.string.mf_error_unavailable
    }
    is ProviderError.Parsing -> R.string.mf_error_parsing
    is ProviderError.NotSupported -> R.string.mf_error_not_supported
    is ProviderError.Unknown -> R.string.mf_error_unknown
}

@Composable
fun errorMessage(t: Throwable): String =
    when {
        t is ProviderError && t !is ProviderError.Unknown -> stringResource(t.messageRes())
        // Unexpected: add the technical reason, so a screenshot is enough to diagnose it.
        else -> stringResource(R.string.mf_error_unknown) + (t.message?.takeIf { it.isNotBlank() }?.let { "\n($it)" } ?: "")
    }
