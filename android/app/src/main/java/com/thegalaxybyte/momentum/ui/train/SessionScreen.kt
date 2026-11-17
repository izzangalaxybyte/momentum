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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.AppContainer
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.db.SetLogEntity
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.domain.ExerciseType
import com.thegalaxybyte.momentum.domain.LadderState
import com.thegalaxybyte.momentum.domain.Rating
import com.thegalaxybyte.momentum.domain.SetResult
import com.thegalaxybyte.momentum.domain.Target
import com.thegalaxybyte.momentum.domain.fmtKg
import com.thegalaxybyte.momentum.ui.ExerciseDemo
import com.thegalaxybyte.momentum.ui.RatingChips
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.Stepper
import com.thegalaxybyte.momentum.ui.sessionNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExerciseEntry(val state: LadderState, val target: Target) {
    val values = mutableStateListOf<Int>().apply {
        repeat(target.sets) { add(if (target.exercise.type == ExerciseType.TIME) target.holdSec else target.repMax) }
    }
    val done = mutableStateListOf<Boolean>().apply { repeat(target.sets) { add(false) } }
    var rating by mutableStateOf<Rating?>(null)
}

/** Holds the in-progress workout so it survives rotation and navigation to the exercise page. */
class SessionVm(private val c: AppContainer, val key: String) : ViewModel() {
    var entries by mutableStateOf<List<ExerciseEntry>>(emptyList())
    var current by mutableIntStateOf(0)
    var weekIndex = 0
    var summary by mutableStateOf<List<String>?>(null)
    val startedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            val p = c.dao.profileNow() ?: return@launch
            weekIndex = c.plan.weekIndex(p)
            val engine = c.plan.engine(p)
            entries = c.plan.ladderStates(p, key).map { ExerciseEntry(it, engine.target(it, weekIndex)) }
        }
    }

    fun finish() = viewModelScope.launch {
        val p = c.dao.profileNow() ?: return@launch
        val engine = c.plan.engine(p)
        val day = Days.today()
        val sets = mutableListOf<SetLogEntity>()
        val newStates = mutableListOf<LadderState>()
        val messages = mutableListOf<String>()
        for (e in entries) {
            val ex = e.target.exercise
            val results = e.values.indices.filter { e.done[it] }.map { i ->
                if (ex.type == ExerciseType.TIME) SetResult(seconds = e.values[i]) else SetResult(reps = e.values[i])
            }
            if (results.isEmpty()) continue
            results.forEachIndexed { i, r ->
                sets += SetLogEntity(
                    id = "", sessionId = "", epochDay = day, exerciseId = ex.id, ladder = e.state.ladder, setIndex = i,
                    loadKg = e.target.loadKg, reps = r.reps, seconds = r.seconds, rating = e.rating?.name, updatedAt = 0,
                )
            }
            val outcome = engine.evaluate(e.state, results, e.rating ?: Rating.GOOD, weekIndex)
            newStates += outcome.state
            messages += outcome.message
        }
        c.repo.saveWorkout(day, key, weekIndex, startedAt, sets, newStates)
        summary = messages.ifEmpty { listOf("No sets were ticked, so nothing was saved to your progress.") }
    }
}

fun targetText(t: Target): String {
    val ex = t.exercise
    val side = if (ex.perSide) " per side" else ""
    return when (ex.type) {
        ExerciseType.TIME -> "${t.sets} × ${t.holdSec} s$side"
        ExerciseType.REPS -> "${t.sets} × ${t.repMin}–${t.repMax} reps$side"
        ExerciseType.WEIGHT_REPS -> "${t.sets} × ${t.repMin}–${t.repMax} reps$side @ ${fmtKg(t.loadKg)}" +
            if (ex.equipment.name == "DUMBBELL" && t.loadKg > 0) " each" else ""
    } + if (t.tempo) " · slow tempo (3-1-3)" else ""
}

@Composable
fun SessionScreen(nav: NavHostController, key: String) {
    val c = LocalContext.current.container
    val vm: SessionVm = viewModel(key = "session-$key") { SessionVm(c, key) }
    val uri = LocalUriHandler.current
    var confirmLeave by remember { mutableStateOf(false) }
    // Rest timer: each ticked set bumps restKey, which restarts the countdown.
    var restKey by remember { mutableIntStateOf(0) }
    var restFor by remember { mutableIntStateOf(0) }
    var restLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(restKey) {
        if (restKey > 0) {
            restLeft = restFor
            while (restLeft > 0) { delay(1000); restLeft-- }
        }
    }

    if (vm.entries.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    val e = vm.entries[vm.current]
    val ex = e.target.exercise

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { confirmLeave = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "leave") }
            Column {
                Text(sessionNames[key] ?: key, style = MaterialTheme.typography.titleLarge)
                Text("Exercise ${vm.current + 1} of ${vm.entries.size}", style = MaterialTheme.typography.bodySmall)
            }
        }
        LinearProgressIndicator(progress = { (vm.current + 1f) / vm.entries.size }, modifier = Modifier.fillMaxWidth())

        ExerciseDemo(ex.images)
        Text(ex.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(targetText(e.target), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

        SectionCard("How to do it") {
            ex.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Avoid: " + ex.mistakes.joinToString("; ").lowercase(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uri.openUri(ex.video) }) { Text("Watch video") }
                TextButton(onClick = { nav.navigate("exercise/${ex.id}") }) { Text("More") }
            }
        }

        SectionCard("Sets") {
            val unit = if (ex.type == ExerciseType.TIME) "s" else "reps"
            e.values.indices.forEach { i ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Set ${i + 1}", style = MaterialTheme.typography.bodyLarge)
                    Stepper(e.values[i], { e.values[i] = it }, unit, step = if (ex.type == ExerciseType.TIME) 5 else 1)
                    if (e.done[i]) {
                        Button(onClick = { e.done[i] = false }) { Text("✓") }
                    } else {
                        OutlinedButton(onClick = {
                            e.done[i] = true
                            restFor = if (ex.type == ExerciseType.TIME) 45 else 75
                            restKey++
                        }) { Text("Done") }
                    }
                }
            }
            if (restLeft > 0) Text("Rest: $restLeft s", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("How did this exercise feel?", style = MaterialTheme.typography.bodySmall)
            RatingChips(e.rating) { e.rating = it }
            Text(
                "Easy/good with all reps → it gets harder next time. Hard → you repeat it.",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(enabled = vm.current > 0, onClick = { vm.current-- }) { Text("Previous") }
            if (vm.current < vm.entries.lastIndex) {
                Button(onClick = { vm.current++ }) { Text("Next exercise") }
            } else {
                Button(onClick = { vm.finish() }) { Text("Finish workout") }
            }
        }
    }

    vm.summary?.let { msgs ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Workout saved 💪") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { msgs.forEach { Text("• $it") } } },
            confirmButton = { Button(onClick = { nav.popBackStack() }) { Text("Done") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave workout?") },
            text = { Text("Sets you've ticked won't be saved unless you finish the workout.") },
            confirmButton = { TextButton(onClick = { nav.popBackStack() }) { Text("Leave") } },
            dismissButton = { Button(onClick = { confirmLeave = false }) { Text("Keep going") } },
        )
    }
}
