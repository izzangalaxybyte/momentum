package com.thegalaxybyte.momentum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.thegalaxybyte.momentum.data.Catalogs
import com.thegalaxybyte.momentum.domain.Rating
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun ProgressRow(label: String, value: Double, target: Double, unit: String) {
    val frac = if (target > 0) (value / target).toFloat() else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${value.roundToInt()} / ${target.roundToInt()} $unit", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { frac.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = if (frac > 1.05f) Warn else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun RatingChips(selected: Rating?, onSelect: (Rating) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Rating.EASY to "Easy", Rating.GOOD to "Good", Rating.HARD to "Hard").forEach { (r, label) ->
            FilterChip(selected = selected == r, onClick = { onSelect(r) }, label = { Text(label) })
        }
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, label: String, step: Int = 1, min: Int = 0) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Icon(Icons.Default.Remove, "less") }
        Text("$value $label", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(76.dp))
        IconButton(onClick = { onChange(value + step) }) { Icon(Icons.Default.Add, "more") }
    }
}

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') || it == ',' || it == ' ' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** Loops the exercise's start/end photos so the movement reads like a GIF. */
@Composable
fun ExerciseDemo(images: List<String>, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(images) {
        while (images.size > 1) {
            delay(900)
            frame = (frame + 1) % images.size
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
    ) {
        images.getOrNull(frame)?.let {
            AsyncImage(
                model = Catalogs.assetUri(it),
                contentDescription = "Exercise demonstration",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            )
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

fun fmtDay(epochDay: Long, pattern: String = "EEE d MMM"): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(pattern))

fun fmtNum(d: Double, decimals: Int = 1): String =
    if (d % 1.0 == 0.0) d.toInt().toString() else "%.${decimals}f".format(d)

