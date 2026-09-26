package com.charleshartman.porchlightpress.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charleshartman.porchlightpress.ui.speech.SpeechState
import com.charleshartman.porchlightpress.ui.speech.formatTimeMmSs
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import kotlin.math.roundToInt

@Composable
fun AudioPlayerBar(
    state: SpeechState,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val classic = LocalIsClassic.current
    val surfaceColor = if (classic) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = surfaceColor,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .testTag("audio-player-bar"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Header: Title & Close/Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifBlank { "Reading story" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("speech-player-title"),
                    )
                    val sentenceText = if (state.count > 0) {
                        "Sentence ${state.index + 1} of ${state.count}"
                    } else {
                        "Preparing read-aloud…"
                    }
                    Text(
                        text = sentenceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("speech-sentence-progress"),
                    )
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("speech-stop"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Stop reading",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Seek bar (Slider)
            val maxIndex = maxOf(1f, (state.count - 1).toFloat())
            var sliderPosition by remember(state.index) {
                mutableFloatStateOf(state.index.coerceAtLeast(0).toFloat())
            }
            var isDragging by remember { mutableStateOf(false) }

            Slider(
                value = if (isDragging) sliderPosition else state.index.coerceAtLeast(0).toFloat().coerceIn(0f, maxIndex),
                onValueChange = {
                    isDragging = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek(sliderPosition.roundToInt().coerceIn(0, (state.count - 1).coerceAtLeast(0)))
                },
                valueRange = 0f..maxIndex,
                steps = maxOf(0, state.count - 2),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("speech-seek-bar"),
            )

            // Time Row: Elapsed (left) & Remaining (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val elapsedSec = (state.totalSeconds - state.remainingSeconds).coerceAtLeast(0)
                Text(
                    text = formatTimeMmSs(elapsedSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("speech-time-elapsed"),
                )
                Text(
                    text = "-${formatTimeMmSs(state.remainingSeconds)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("speech-time-remaining"),
                )
            }

            // Controls Row: Previous, Play/Pause, Next
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = state.index > 0,
                    modifier = Modifier.testTag("speech-previous"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous sentence",
                        tint = if (state.index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }

                Spacer(Modifier.width(20.dp))

                FilledIconButton(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("speech-play-pause"),
                ) {
                    Icon(
                        imageVector = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier
                            .size(26.dp)
                            .testTag(if (state.playing) "speech-pause" else "speech-play"),
                    )
                }

                Spacer(Modifier.width(20.dp))

                IconButton(
                    onClick = onNext,
                    enabled = state.index + 1 < state.count,
                    modifier = Modifier.testTag("speech-next"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next sentence",
                        tint = if (state.index + 1 < state.count) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}
