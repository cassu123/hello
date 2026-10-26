package org.hello.reader.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.model.SyncConflict
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.chapterLabel

/**
 * Asks the user which position to keep when this device and the server
 * disagree by more than [SyncCheckpoint.CONFLICT_THRESHOLD_MS]. Dismissing
 * without choosing keeps the conflict pending; it is shown again next time.
 */
@Composable
fun SyncConflictDialog(
    conflict: SyncConflict,
    bookTitle: String?,
    onKeepLocal: () -> Unit,
    onUseRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    bookTitle?.let { stringResource(R.string.conflict_body, it) } ?: stringResource(R.string.conflict_body_untitled),
                )
                PositionSummary(stringResource(R.string.server_this_device), conflict.local)
                PositionSummary(stringResource(R.string.conflict_server), conflict.remote)
            }
        },
        confirmButton = { TextButton(onClick = onUseRemote) { Text(stringResource(R.string.conflict_use_server)) } },
        dismissButton = { TextButton(onClick = onKeepLocal) { Text(stringResource(R.string.conflict_keep_device)) } },
    )
}

@Composable
private fun PositionSummary(label: String, checkpoint: SyncCheckpoint) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(describe(checkpoint, chapterLabel(checkpoint.chapterIndex, checkpoint.chapterTitle)), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                R.string.updated_ago,
                DateUtils.getRelativeTimeSpanString(checkpoint.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Chapter 3 · 1:02:03 · 37%"; [chapter] is the chapter's display name. */
internal fun describe(checkpoint: SyncCheckpoint, chapter: String): String {
    val percent = LibraryFormatters.percent(checkpoint.progressPercent / 100f)
    return if (checkpoint.totalDurationMs > 0 || checkpoint.audioPositionMs > 0) {
        "$chapter · ${LibraryFormatters.position(checkpoint.audioPositionMs)} · $percent"
    } else {
        "$chapter · $percent"
    }
}
