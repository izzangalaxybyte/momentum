package com.thegalaxybyte.momentum.ui.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.domain.ExerciseType
import com.thegalaxybyte.momentum.domain.fmtKg
import com.thegalaxybyte.momentum.ui.ExerciseDemo
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.fmtDay

@Composable
fun ExerciseScreen(nav: NavHostController, id: String) {
    val c = LocalContext.current.container
    val ex = c.catalogs.exercises.exercise(id) ?: return
    val uri = LocalUriHandler.current
    val history by remember(id) { c.dao.setsFor(id) }.collectAsState(initial = emptyList())
    val ladders by remember { c.dao.ladderStates() }.collectAsState(initial = emptyList())
    val currentStep = ladders.firstOrNull { it.id == ex.ladder }?.step ?: 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text(ex.name, style = MaterialTheme.typography.titleLarge)
        }
        ExerciseDemo(ex.images)
        SectionCard("How to do it") {
            ex.cues.forEachIndexed { i, cue -> Text("${i + 1}. $cue", style = MaterialTheme.typography.bodyMedium) }
        }
        SectionCard("Common mistakes") {
            ex.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        SectionCard("Works") {
            Text(ex.muscles.joinToString(", ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { uri.openUri(ex.video) }) { Text("Watch a video") }
        }
        SectionCard("Progression") {
            c.catalogs.exercises.ladder(ex.ladder).forEach { step ->
                val mark = when {
                    step.step < currentStep -> "✓"
                    step.step == currentStep -> "▶"
                    else -> "○"
                }
                Text(
                    "$mark ${step.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (step.id == ex.id) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        SectionCard("History") {
            if (history.isEmpty()) Text("No sets logged yet.", style = MaterialTheme.typography.bodySmall)
            history.groupBy { it.epochDay }.entries.take(10).forEach { (day, sets) ->
                val desc = sets.sortedBy { it.setIndex }.joinToString(", ") { s ->
                    if (ex.type == ExerciseType.TIME) "${s.seconds}s" else "${s.reps}"
                }
                val load = sets.first().loadKg
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtDay(day), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        (if (ex.type == ExerciseType.WEIGHT_REPS) "${fmtKg(load)} × " else "") + desc +
                            (sets.first().rating?.let { " · ${it.lowercase()}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
