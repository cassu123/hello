package org.hello.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hello.reader.core.engine.smil.BookChapter
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.feature.library.LibraryFormatters
import org.hello.reader.ui.components.SegmentLabel
import org.hello.reader.ui.components.SegmentedPills
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.parseColorOrNull
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.chapterLabel

private enum class SheetTab { CONTENTS, HIGHLIGHTS, BOOKMARKS }

/** Contents (when the book has chapters), then notes & highlights, and bookmarks, in reading order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSheet(
    annotations: List<BookAnnotation>,
    paragraphOrder: Map<String, Int>,
    onJumpTo: (BookAnnotation) -> Unit,
    onEditNote: (BookAnnotation, String) -> Unit,
    onDelete: (BookAnnotation) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
    chapters: BookChapters = BookChapters.EMPTY,
    currentChapter: Int? = null,
    onGoToChapter: (Int) -> Unit = {},
) {
    val tabs = if (chapters.isNavigable) SheetTab.entries else listOf(SheetTab.HIGHLIGHTS, SheetTab.BOOKMARKS)
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var editing by remember { mutableStateOf<BookAnnotation?>(null) }
    val ordered = remember(annotations, paragraphOrder) { annotationsInReadingOrder(annotations, paragraphOrder) }
    val highlights = ordered.filter { it.type == AnnotationType.HIGHLIGHT }
    val bookmarks = ordered.filter { it.type == AnnotationType.BOOKMARK }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxHeight(0.85f).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.Gutter, end = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (tab == SheetTab.CONTENTS) R.string.contents else R.string.notebook),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (tab != SheetTab.CONTENTS) {
                    IconButton(onClick = onExport, enabled = annotations.isNotEmpty()) {
                        Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.export_notes_markdown))
                    }
                }
            }
            SegmentedPills(
                options = tabs,
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.S),
            ) { option, isSelected ->
                SegmentLabel(
                    when (option) {
                        SheetTab.CONTENTS -> stringResource(R.string.contents)
                        SheetTab.HIGHLIGHTS -> stringResource(R.string.highlights_count, highlights.size)
                        SheetTab.BOOKMARKS -> stringResource(R.string.bookmarks_count, bookmarks.size)
                    },
                    isSelected,
                )
            }
            if (tab == SheetTab.CONTENTS) {
                ChapterList(chapters, currentChapter, onGoToChapter)
                return@Column
            }
            val shown = if (tab == SheetTab.HIGHLIGHTS) highlights else bookmarks
            if (shown.isEmpty()) {
                Text(
                    if (tab == SheetTab.HIGHLIGHTS) {
                        stringResource(R.string.highlights_empty_hint)
                    } else {
                        stringResource(R.string.bookmarks_empty_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.M),
                )
            } else {
                LazyColumn {
                    items(shown, key = { it.id }) { annotation ->
                        AnnotationRow(
                            annotation,
                            onJumpTo = { onJumpTo(annotation) },
                            onEditNote = { editing = annotation },
                            onDelete = { onDelete(annotation) },
                        )
                        HorizontalDivider(Modifier.padding(start = Spacing.Gutter), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    editing?.let { annotation ->
        NoteDialog(
            quote = annotation.selectedText,
            initialNote = annotation.note.orEmpty(),
            onSave = { note ->
                editing = null
                onEditNote(annotation, note)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ChapterList(chapters: BookChapters, current: Int?, onGoToChapter: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ((current ?: 0) - 2).coerceAtLeast(0))
    LazyColumn(state = listState) {
        itemsIndexed(chapters.chapters, key = { index, _ -> index }) { index, chapter ->
            ChapterRow(chapter, isCurrent = index == current, onClick = { onGoToChapter(index) })
        }
    }
}

@Composable
private fun ChapterRow(chapter: BookChapter, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(end = Spacing.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .background(if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Text(
            chapterLabel(chapter.index, chapter.title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.Gutter - 3.dp, end = Spacing.S, top = Spacing.XS, bottom = Spacing.XS),
        )
        chapter.startMs?.let { start ->
            Text(
                LibraryFormatters.position(start),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnnotationRow(annotation: BookAnnotation, onJumpTo: () -> Unit, onEditNote: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = annotation.elementId != null, onClick = onJumpTo)
            .padding(start = Spacing.Gutter, end = Spacing.XS, top = Spacing.S, bottom = Spacing.S),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        if (annotation.type == AnnotationType.HIGHLIGHT) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(4.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(parseColorOrNull(annotation.color.hex) ?: Color.Yellow),
            )
        } else {
            Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                chapterLabel(annotation.chapterIndex, annotation.chapterTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            annotation.selectedText?.let {
                Text(stringResource(R.string.quoted, it.trim()), fontFamily = Literata, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            annotation.note?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (annotation.type == AnnotationType.HIGHLIGHT) {
            IconButton(onClick = onEditNote) {
                Icon(Icons.Outlined.EditNote, contentDescription = stringResource(if (annotation.note == null) R.string.add_note else R.string.edit_note))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(if (annotation.type == AnnotationType.HIGHLIGHT) R.string.delete_highlight else R.string.delete_bookmark))
        }
    }
}

/**
 * Reading order: paragraph position in the book (from [paragraphOrder]), then
 * offset within the paragraph, then creation time. Annotations whose paragraph
 * is no longer in the book (e.g. after a re-download) sort last, by chapter.
 */
internal fun annotationsInReadingOrder(
    annotations: List<BookAnnotation>,
    paragraphOrder: Map<String, Int>,
): List<BookAnnotation> = annotations.sortedWith(
    compareBy<BookAnnotation> { paragraphOrder[it.elementId] ?: Int.MAX_VALUE }
        .thenBy { it.chapterIndex }
        .thenBy { it.startOffset }
        .thenBy { it.createdAt },
)
