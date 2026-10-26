package org.hello.reader.feature.reader

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.core.network.DictionaryEntry
import java.io.IOException
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

private const val TAG = "DictionaryBottomSheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryBottomSheet(
    state: DictionaryUiState,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Spacing.Gutter, end = Spacing.Gutter, top = Spacing.XS, bottom = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            when (state) {
                is DictionaryUiState.Loading -> {
                    Text(state.word, style = MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.dictionary_looking_up), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is DictionaryUiState.Loaded -> EntryContent(state.entry)
                is DictionaryUiState.NotFound -> {
                    Text(state.word, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.dictionary_not_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DictionaryUiState.Failed -> {
                    Text(state.word, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.dictionary_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onRetry(state.word) }) { Text(stringResource(R.string.try_again)) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.dictionary_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryContent(entry: DictionaryEntry) {
    val player = rememberPronunciationPlayer()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.word, style = MaterialTheme.typography.headlineMedium)
            entry.phonetic?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        entry.audioUrl?.let { url ->
            IconButton(onClick = { player.play(url) }, enabled = !player.isPreparing) {
                if (player.isPreparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.play_pronunciation))
                }
            }
        }
    }
    entry.meanings.forEach { meaning ->
        Text(
            meaning.partOfSpeech,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.XXS),
        )
        meaning.definitions.take(MAX_DEFINITIONS_PER_MEANING).forEachIndexed { index, definition ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.numbered_item, index + 1, definition.definition), style = MaterialTheme.typography.bodyLarge)
                definition.example?.let {
                    Text(
                        stringResource(R.string.quoted, it),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = Literata,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private const val MAX_DEFINITIONS_PER_MEANING = 3

/** Streams a short pronunciation clip; released when the sheet leaves composition. */
private class PronunciationPlayer {
    private var player: MediaPlayer? = null
    var isPreparing by mutableStateOf(false)
        private set

    fun play(url: String) {
        release()
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnPreparedListener {
                isPreparing = false
                it.start()
            }
            setOnCompletionListener { release() }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Pronunciation playback failed ($what/$extra)")
                release()
                true
            }
        }
        player = mp
        try {
            mp.setDataSource(url)
            isPreparing = true
            mp.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "Pronunciation unavailable", e)
            release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Pronunciation player in bad state", e)
            release()
        }
    }

    fun release() {
        isPreparing = false
        player?.release()
        player = null
    }
}

@Composable
private fun rememberPronunciationPlayer(): PronunciationPlayer {
    val player = remember { PronunciationPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
