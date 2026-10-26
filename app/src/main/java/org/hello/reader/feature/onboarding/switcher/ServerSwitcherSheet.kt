package org.hello.reader.feature.onboarding.switcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

/** Bottom sheet for switching between saved servers, removing one, or adding another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSwitcherSheet(
    onDismiss: () -> Unit,
    onAddServer: () -> Unit,
    viewModel: ServerSwitcherViewModel = viewModel(factory = ServerSwitcherViewModel.Factory),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val active by viewModel.activeProfile.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingRename by remember { mutableStateOf<ServerProfile?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Spacing.XS)) {
            Text(
                stringResource(R.string.servers),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = Spacing.Gutter, end = Spacing.Gutter, bottom = Spacing.XS),
            )
            LazyColumn {
                items(profiles, key = { it.id }) { profile ->
                    val selected = profile.id == active?.id
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                viewModel.select(profile.id)
                                onDismiss()
                            },
                        ),
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(profile.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.joined_with_dot, profile.username, profile.baseUrl.substringAfter("://")),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { pendingRename = profile }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename_server_named, profile.name))
                                }
                                IconButton(onClick = { pendingDelete = profile }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.remove_server_named, profile.name))
                                }
                            }
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = Spacing.M), color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                modifier = Modifier.clickable(onClick = onAddServer),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.add_a_server), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
            )
        }
    }

    pendingRename?.let { profile ->
        RenameServerDialog(
            profile = profile,
            onConfirm = { name ->
                viewModel.rename(profile.id, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.remove_server_title, profile.name)) },
            text = { Text(stringResource(R.string.remove_server_body, profile.baseUrl, profile.username)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RenameServerDialog(
    profile: ServerProfile,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_server)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                supportingText = { Text(profile.baseUrl) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
