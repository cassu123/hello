package org.hello.reader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.hello.reader.R
import org.hello.reader.core.model.WcagContrastValidator
import org.hello.reader.ui.theme.parseColorOrNull
import java.util.Locale

internal val EditorHighlights = listOf(
    "#EBCB86", "#E0A458", "#F2E06B", "#9CC9A4",
    "#9DBAE3", "#C5B3E6", "#E3A6BE", "#3D5AFE", "#2B4FB8",
)

internal val EditorDayBackgrounds = listOf("#FFFFFF", "#FBFAF7", "#F4ECD8", "#F1E9DC", "#E9EEF2", "#EAF0E6", "#E4E4E1")
internal val EditorDayText = listOf("#000000", "#18181B", "#2B241C", "#3B2E1E", "#1E2A36", "#22301F", "#3A393E")
internal val EditorNightBackgrounds = listOf("#000000", "#141518", "#1B1C20", "#1E2430", "#202A26", "#2A2320", "#232026")
internal val EditorNightText = listOf("#F2F0EA", "#E3E1DC", "#D9D2C3", "#C9D3E0", "#CFE0D4", "#E8D5C0", "#BDBAB2")

/** A labelled row of 36dp color circles; the selected one gets a ring in the accent color. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSwatchRow(
    label: String,
    currentHex: String,
    palette: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                currentHex.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            palette.forEach { hex ->
                val color = parseColorOrNull(hex) ?: return@forEach
                ColorSwatch(
                    color = color,
                    label = "$label $hex",
                    selected = hex.equals(currentHex.trim(), ignoreCase = true),
                    onClick = { onPick(hex) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .size(44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .then(
                    if (selected) {
                        Modifier
                            .border(2.dp, ring, CircleShape)
                            .padding(4.dp)
                            .border(1.dp, gap, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .background(color)
                .border(1.dp, Color.Black.copy(alpha = 0.14f), CircleShape),
        )
    }
}

/** "7.2:1 · AAA"; [lowLabel] names a ratio below AA. */
internal fun contrastLabel(report: WcagContrastValidator.ContrastReport, lowLabel: String): String {
    val ratio = String.format(Locale.US, "%.1f:1", report.ratio)
    return when {
        report.passesAAA -> "$ratio · AAA"
        report.passesAA -> "$ratio · AA"
        else -> "$ratio · $lowLabel"
    }
}

/** Compact pill rating text contrast. */
@Composable
fun ContrastBadge(report: WcagContrastValidator.ContrastReport, modifier: Modifier = Modifier) {
    val ok = report.passesAA
    val container = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Text(
        contrastLabel(report, stringResource(R.string.contrast_low)),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = content,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
