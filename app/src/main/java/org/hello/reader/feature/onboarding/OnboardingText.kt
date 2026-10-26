package org.hello.reader.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.core.data.ConnectionError
import org.hello.reader.core.network.ServerUrl

/** What went wrong talking to a server, in the user's language. */
@Composable
fun ConnectionError.text(): String = when (this) {
    ConnectionError.HostNotFound -> stringResource(R.string.error_host_not_found)
    ConnectionError.Unreachable -> stringResource(R.string.error_unreachable)
    ConnectionError.Timeout -> stringResource(R.string.error_timeout)
    ConnectionError.TlsFailure -> stringResource(R.string.error_tls)
    ConnectionError.NotStoryteller -> stringResource(R.string.error_not_storyteller)
    is ConnectionError.Unhealthy -> stringResource(R.string.error_unhealthy, status)
    ConnectionError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    ConnectionError.InvalidToken -> stringResource(R.string.error_invalid_token)
    is ConnectionError.Http -> stringResource(R.string.error_http, code)
    is ConnectionError.Network -> detail?.let { stringResource(R.string.error_network_detail, it) }
        ?: stringResource(R.string.error_network)
}

@StringRes
fun ServerUrl.Reason.messageRes(): Int = when (this) {
    ServerUrl.Reason.EMPTY -> R.string.url_empty
    ServerUrl.Reason.CONTAINS_SPACES -> R.string.url_spaces
    ServerUrl.Reason.UNSUPPORTED_SCHEME -> R.string.url_scheme
    ServerUrl.Reason.MALFORMED -> R.string.url_malformed
    ServerUrl.Reason.QUERY_OR_FRAGMENT -> R.string.url_query
    ServerUrl.Reason.CREDENTIALS -> R.string.url_credentials
}
