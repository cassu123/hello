package org.hello.reader.feature.onboarding.url

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.ui.theme.LocalHelloColors
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.feature.onboarding.messageRes
import org.hello.reader.feature.onboarding.text
import org.hello.reader.ui.components.HelloGreeting

internal fun isRiverSongAddress(text: String): Boolean =
    text.contains("sweetie", ignoreCase = true) || text.contains("riversong", ignoreCase = true)

@Composable
fun ServerUrlScreen(
    onContinue: (baseUrl: String) -> Unit,
    onScanQr: () -> Unit,
    onBack: (() -> Unit)?,
    onSkipToLocal: (() -> Unit)? = null,
    viewModel: ServerUrlViewModel = viewModel(factory = ServerUrlViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val proceed = { (state.status as? ServerStatus.Online)?.let { onContinue(it.baseUrl) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Spacing.L, vertical = Spacing.M),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { proceed() },
                    enabled = state.canContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) { Text(stringResource(R.string.action_continue)) }
                OutlinedButton(
                    onClick = onScanQr,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.XS))
                    Text(stringResource(R.string.scan_connect_code))
                }
                if (onSkipToLocal != null) {
                    TextButton(onClick = onSkipToLocal, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.use_without_server), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = Spacing.XXS, top = Spacing.XXS)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.height(Spacing.XL))
            }
            Column(
                Modifier.padding(horizontal = Spacing.L, vertical = Spacing.L),
                verticalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                if (onBack == null) {
                    // First run keeps the app's greeting above the heading.
                    HelloGreeting(stringResource(R.string.hello_sweetie))
                }
                Text(stringResource(R.string.connect_to_storyteller), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.connect_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.S))
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) },
                    placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                    leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.Control),
                    isError = state.status is ServerStatus.InvalidInput || state.status is ServerStatus.Unreachable,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onGo = { proceed() }),
                )
                ServerStatusRow(state.status, onRetry = viewModel::retry)
            }
        }
    }
}

@Composable
private fun ServerStatusRow(status: ServerStatus, onRetry: () -> Unit) {
    val success = LocalHelloColors.current.success
    AnimatedContent(targetState = status, label = "server-status") { current ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp),
        ) {
            when (current) {
                ServerStatus.Idle -> Unit
                is ServerStatus.InvalidInput -> {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(stringResource(current.reason.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                is ServerStatus.Checking -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.checking_server, current.baseUrl),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ServerStatus.Online -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = success, modifier = Modifier.size(18.dp))
                        Text(
                            current.version?.let { stringResource(R.string.storyteller_online_version, it) }
                                ?: stringResource(R.string.storyteller_online),
                            style = MaterialTheme.typography.bodyMedium,
                            color = success,
                        )
                    }
                    if (isRiverSongAddress(current.baseUrl)) {
                        Text(
                            stringResource(R.string.river_song_expecting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current.isCleartext) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.cleartext_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is ServerStatus.Unreachable -> {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        current.error.text(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}
