package org.hello.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.parseColorOrNull
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.nameRes

/** Actions offered for a text selection. */
interface SelectionActions {
    fun onDefine(word: String)
    fun onHighlight(color: HighlightColor)
    fun onAddNote(note: String)
    fun onBookmark()
    fun onCopy(text: String)

    /** Starts narration at the selected sentence; only offered when [SelectionMenu]'s `canPlayHere` is true. */
    fun onPlayHere()
    fun onDismiss()
}

/**
 * Floating menu for the current selection, placed just above the selected text (or below it
 * when there's no room above) and clamped to the window. Row one: highlight colors, bookmark
 * and copy; row two: Define (single words), Note and Play here (books with narration).
 */
@Composable
fun SelectionMenu(
    selection: ReaderSelection,
    anchorInWindow: Rect,
    canPlayHere: Boolean,
    actions: SelectionActions,
) {
    var writingNote by rememberSaveable(selection.sentence.elementId, selection.range) { mutableStateOf(false) }

    if (writingNote) {
        NoteDialog(
            quote = selection.text,
            onSave = { note ->
                writingNote = false
                actions.onAddNote(note)
            },
            onDismiss = { writingNote = false },
        )
        return
    }

    Popup(
        popupPositionProvider = remember(anchorInWindow) { SelectionMenuPosition(anchorInWindow) },
        onDismissRequest = actions::onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.Card),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
        ) {
            Column(Modifier.padding(Spacing.XS)) {
                Row(
                    modifier = Modifier.padding(start = Spacing.XXS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HighlightColor.entries.forEach { color ->
                        ColorDot(color) { actions.onHighlight(color) }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = actions::onBookmark) {
                        Icon(Icons.Outlined.BookmarkAdd, contentDescription = stringResource(R.string.bookmark_passage))
                    }
                    IconButton(onClick = { actions.onCopy(selection.text) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy))
                    }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = Spacing.XXS),
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.16f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
                    if (selection.isSingleWord) {
                        MenuAction(stringResource(R.string.define), Icons.AutoMirrored.Outlined.MenuBook) { actions.onDefine(selection.text) }
                    }
                    MenuAction(stringResource(R.string.note), Icons.Outlined.EditNote) { writingNote = true }
                    if (canPlayHere) {
                        MenuAction(stringResource(R.string.play_here), Icons.Rounded.PlayArrow, actions::onPlayHere)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Radius.Small + 2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun ColorDot(color: HighlightColor, onClick: () -> Unit) {
    val description = stringResource(R.string.highlight_in_color, stringResource(color.nameRes()))
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(parseColorOrNull(color.hex) ?: Color.Yellow),
        )
    }
}

@Composable
fun NoteDialog(
    quote: String?,
    initialNote: String = "",
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initialNote.isEmpty()) R.string.add_note else R.string.edit_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                quote?.let {
                    Text(
                        stringResource(R.string.quoted, it.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.note)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }, enabled = note.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Positions the popup relative to a rectangle in window coordinates. */
private class SelectionMenuPosition(private val anchor: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = selectionMenuOffset(anchor, windowSize, popupContentSize)
}

/** Pure placement math (unit-tested): centered over the selection, above if it fits, else below. */
internal fun selectionMenuOffset(anchor: Rect, window: IntSize, popup: IntSize, gapPx: Int = 16): IntOffset {
    val centeredX = (anchor.center.x - popup.width / 2f).toInt()
    val x = centeredX.coerceIn(0, (window.width - popup.width).coerceAtLeast(0))
    val above = anchor.top.toInt() - popup.height - gapPx
    val below = anchor.bottom.toInt() + gapPx
    val y = when {
        above >= 0 -> above
        below + popup.height <= window.height -> below
        else -> (window.height - popup.height).coerceAtLeast(0)
    }
    return IntOffset(x, y)
}
