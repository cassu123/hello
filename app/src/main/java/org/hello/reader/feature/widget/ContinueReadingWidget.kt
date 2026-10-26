package org.hello.reader.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.hello.reader.R
import org.hello.reader.app.MainActivity

/**
 * Home-screen "Continue Reading" widget built with Jetpack Glance.
 *
 * Features:
 * - Scoped strictly to the active server profile (never leaks other profiles).
 * - Friendly empty state when no book is in progress.
 * - Displays book cover thumbnail, title, author, progress bar, and percentage.
 * - "Resume" button deep-links directly into the reader via [MainActivity.EXTRA_OPEN_BOOK_ID].
 * - Play/Pause button for books with audio, toggling playback directly from the home screen.
 * - Monochrome high-contrast styling for E-ink devices.
 */
class ContinueReadingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = ContinueReadingWidgetUpdater.loadWidgetData(context)

        provideContent {
            GlanceTheme {
                WidgetRoot(context, data)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetRoot(context: Context, data: ContinueReadingWidgetData) {
    val bgColor = if (data.isEInk) ColorProvider(Color.White) else GlanceTheme.colors.surface
    val textColor = if (data.isEInk) ColorProvider(Color.Black) else GlanceTheme.colors.onSurface
    val textMutedColor = if (data.isEInk) ColorProvider(Color.DarkGray) else GlanceTheme.colors.onSurfaceVariant
    val barColor = if (data.isEInk) ColorProvider(Color.Black) else GlanceTheme.colors.primary
    val barBgColor = if (data.isEInk) ColorProvider(Color.LightGray) else GlanceTheme.colors.surfaceVariant

    if (data.isEmpty) {
        val launchAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(launchAppIntent)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_book),
                    contentDescription = null,
                    modifier = GlanceModifier.size(32.dp),
                    colorFilter = ColorFilter.tint(textColor),
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text(
                    text = context.getString(R.string.widget_empty_title),
                    style = TextStyle(
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = context.getString(R.string.widget_empty_subtitle),
                    style = TextStyle(
                        color = textMutedColor,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
    } else {
        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_BOOK_ID, data.bookId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .padding(10.dp)
                .clickable(actionStartActivity(resumeIntent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover thumbnail or fallback icon
            if (data.coverBitmap != null) {
                Image(
                    provider = ImageProvider(data.coverBitmap),
                    contentDescription = data.title,
                    modifier = GlanceModifier
                        .width(58.dp)
                        .height(86.dp)
                        .cornerRadius(6.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = GlanceModifier
                        .width(58.dp)
                        .height(86.dp)
                        .background(if (data.isEInk) ColorProvider(Color.LightGray) else GlanceTheme.colors.secondaryContainer)
                        .cornerRadius(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_book),
                        contentDescription = null,
                        modifier = GlanceModifier.size(24.dp),
                        colorFilter = ColorFilter.tint(textColor),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(10.dp))

            Column(
                modifier = GlanceModifier
                    .defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = data.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
                if (data.author.isNotBlank()) {
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = data.author,
                        maxLines = 1,
                        style = TextStyle(
                            color = textMutedColor,
                            fontSize = 11.sp,
                        ),
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Progress Bar & Percentage
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = (data.progressPercent / 100f).coerceIn(0f, 1f),
                        modifier = GlanceModifier.defaultWeight().height(5.dp),
                        color = barColor,
                        backgroundColor = barBgColor,
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = "${data.progressPercent.toInt()}%",
                        style = TextStyle(
                            color = textMutedColor,
                            fontSize = 11.sp,
                        ),
                    )
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Actions row: Resume button + optional Play/Pause
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        text = context.getString(R.string.widget_resume),
                        onClick = actionStartActivity(resumeIntent),
                        modifier = GlanceModifier.height(38.dp),
                        colors = if (data.isEInk) {
                            ButtonDefaults.buttonColors(
                                backgroundColor = ColorProvider(Color.Black),
                                contentColor = ColorProvider(Color.White),
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                backgroundColor = GlanceTheme.colors.primary,
                                contentColor = GlanceTheme.colors.onPrimary,
                            )
                        },
                    )

                    if (data.hasAudio) {
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        val iconRes = if (data.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        Box(
                            modifier = GlanceModifier
                                .size(38.dp)
                                .cornerRadius(19.dp)
                                .background(if (data.isEInk) ColorProvider(Color.Black) else GlanceTheme.colors.secondaryContainer)
                                .clickable(actionRunCallback<TogglePlayPauseAction>()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                provider = ImageProvider(iconRes),
                                contentDescription = context.getString(if (data.isPlaying) R.string.pause else R.string.play),
                                modifier = GlanceModifier.size(18.dp),
                                colorFilter = ColorFilter.tint(
                                    if (data.isEInk) ColorProvider(Color.White) else GlanceTheme.colors.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
