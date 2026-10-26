package org.hello.reader.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import org.hello.reader.core.util.findActivity

data class FoldableLayoutInfo(
    val foldingFeature: FoldingFeature? = null,
    val isTabletop: Boolean = false,
    val isBookPosture: Boolean = false,
    val isSeparating: Boolean = false,
)

/**
 * Observes Jetpack WindowManager [FoldingFeature] events across fold, unfold,
 * rotation, and tabletop postures.
 */
@Composable
fun rememberFoldableLayoutInfo(): FoldableLayoutInfo {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return FoldableLayoutInfo()
    var layoutInfo by remember(activity) { mutableStateOf(FoldableLayoutInfo()) }

    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val isTabletop = fold != null &&
                    fold.state == FoldingFeature.State.HALF_OPENED &&
                    fold.orientation == FoldingFeature.Orientation.HORIZONTAL
                val isBook = fold != null &&
                    fold.state == FoldingFeature.State.HALF_OPENED &&
                    fold.orientation == FoldingFeature.Orientation.VERTICAL
                layoutInfo = FoldableLayoutInfo(
                    foldingFeature = fold,
                    isTabletop = isTabletop,
                    isBookPosture = isBook,
                    isSeparating = fold?.isSeparating ?: false,
                )
            }
    }
    return layoutInfo
}
