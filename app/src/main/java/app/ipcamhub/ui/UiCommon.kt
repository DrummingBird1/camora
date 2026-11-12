package app.ipcamhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

/**
 * A setting whose options lie on a scale (time, size, percent, quality): a slider that snaps to each
 * option, with the current value shown beside the title and the two ends under the track. The value is
 * saved when the finger lifts, not on every step.
 */
@Composable
fun <T> SliderChoice(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val index = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)
    var pos by remember(selected, options.size) { mutableFloatStateOf(index.toFloat()) }
    val shown = options[pos.roundToInt().coerceIn(0, options.lastIndex)].first
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onSelect(options[pos.roundToInt().coerceIn(0, options.lastIndex)].second) },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = (options.size - 2).coerceAtLeast(0),
        )
        Row {
            Text(options.first().first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(options.last().first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A labelled row of single-choice chips, for choices that are not on a scale. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, value) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}
