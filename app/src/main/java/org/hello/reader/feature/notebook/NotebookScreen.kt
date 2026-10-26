package org.hello.reader.feature.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.feature.library.CoverImage
import org.hello.reader.ui.components.ScreenTitle
import org.hello.reader.ui.components.SegmentLabel
import org.hello.reader.ui.components.SegmentedPills
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.LocalHelloColors
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.parseColorOrNull
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.chapterLabel

/** Every highlight, note and bookmark, grouped by book. */
@Composable
fun NotebookScreen(
    onOpenBook: (bookId: String) -> Unit,
    viewModel: NotebookViewModel = viewModel(factory = NotebookViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.L)) {
        item(key = "title") { ScreenTitle(stringResource(R.string.notebook)) }
        item(key = "filter") {
            SegmentedPills(
                options = NotebookFilter.entries,
                selected = state.filter,
                onSelect = viewModel::setFilter,
                height = 40.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter, vertical = Spacing.XS),
            ) { option, selected -> SegmentLabel(stringResource(option.labelRes), selected) }
        }
        when {
            state.isLoading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.sections.isEmpty() -> item(key = "empty") {
                EmptyNotebook(hasAny = state.totalCount > 0)
            }
            else -> state.sections.forEach { section ->
                val openBookAction = if (section.book != null) ({ onOpenBook(section.bookId) }) else null
                item(key = "book:${section.bookId}") {
                    BookHeader(section, onOpen = openBookAction)
                }
                items(section.entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        onOpen = openBookAction,
                        onDelete = { viewModel.delete(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookHeader(section: NotebookSection, onOpen: (() -> Unit)?) {
    Column(Modifier.padding(top = Spacing.L)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = Spacing.Gutter, vertical = Spacing.XS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            section.book?.let { book ->
                CoverImage(book, titleSize = 6.sp, modifier = Modifier.width(32.dp).aspectRatio(2f / 3f))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    section.book?.title ?: stringResource(R.string.removed_book),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = section.entries.size
                Text(
                    listOfNotNull(section.book?.authors?.firstOrNull(), pluralStringResource(R.plurals.entry_count, count, count)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = Spacing.Gutter), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EntryRow(entry: BookAnnotation, onOpen: (() -> Unit)?, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = Spacing.Gutter, end = Spacing.XXS, top = Spacing.S, bottom = Spacing.S),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        if (entry.type == AnnotationType.BOOKMARK) {
            Icon(
                Icons.Outlined.BookmarkBorder,
                contentDescription = stringResource(R.string.bookmark),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            val swatch = parseColorOrNull(entry.color.hex) ?: LocalHelloColors.current.highlight
            Box(
                Modifier
                    .width(3.dp)
                    .height(40.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(swatch),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                chapterLabel(entry.chapterIndex, entry.chapterTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.type == AnnotationType.HIGHLIGHT && !entry.selectedText.isNullOrBlank()) {
                Text(
                    stringResource(R.string.quoted, entry.selectedText.trim()),
                    fontFamily = Literata,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (entry.type == AnnotationType.BOOKMARK) {
                Text(stringResource(R.string.bookmark), style = MaterialTheme.typography.bodyMedium)
            }
            if (!entry.note.isNullOrBlank()) {
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options_menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.medium) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyNotebook(hasAny: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.XL, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        Text(
            stringResource(if (hasAny) R.string.notebook_nothing_here else R.string.notebook_empty),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            if (hasAny) {
                stringResource(R.string.notebook_try_filter)
            } else {
                stringResource(R.string.notebook_empty_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
