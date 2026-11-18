package com.thegalaxybyte.momentum.ui.train

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.domain.Workout
import com.thegalaxybyte.momentum.domain.Workouts
import com.thegalaxybyte.momentum.ui.SectionCard
import kotlinx.coroutines.launch

private fun <T> MutableList<T>.swap(i: Int, j: Int) {
    if (i in indices && j in indices) this[i] = this[j].also { this[j] = this[i] }
}

/** Build your own strength workouts: name them, pick and order the movements, and set the rotation. */
@Composable
fun WorkoutsScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val workouts = remember { mutableStateListOf<Workout>() }
    var current by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<List<Workout>>(emptyList()) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = c.plan.workouts()
        workouts.addAll(list)
        saved = list
        c.dao.profileNow()?.let { current = c.plan.currentExercises(it) }
        loaded = true
    }

    val changed = loaded && workouts.toList() != saved
    val valid = workouts.isNotEmpty() && workouts.all { it.ladders.isNotEmpty() && it.name.isNotBlank() }
    fun update(i: Int, f: (Workout) -> Workout) { workouts[i] = f(workouts[i]) }
    fun back() = if (changed) confirmLeave = true else nav.popBackStack().let {}
    BackHandler(enabled = changed) { confirmLeave = true }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text("Your workouts", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Strength days go through these in order, then start again. Each movement keeps its own progress " +
                "(weight, reps, then the next harder exercise), so a movement in two workouts moves forward from either.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        workouts.toList().forEachIndexed { i, w -> key(w.key) {
            SectionCard("Workout ${i + 1} of ${workouts.size}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = w.name,
                        onValueChange = { v -> update(i) { it.copy(name = v.take(30)) } },
                        label = { Text("Name") },
                        singleLine = true,
                        isError = w.name.isBlank(),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = i > 0, onClick = { workouts.swap(i, i - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "earlier") }
                    IconButton(enabled = i < workouts.lastIndex, onClick = { workouts.swap(i, i + 1) }) {
                        Icon(Icons.Default.KeyboardArrowDown, "later")
                    }
                }
                w.ladders.forEachIndexed { j, ladder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Workouts.movementName(ladder), style = MaterialTheme.typography.bodyLarge)
                            current[ladder]?.let {
                                Text("Now: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(enabled = j > 0, onClick = {
                            update(i) { it.copy(ladders = it.ladders.toMutableList().apply { swap(j, j - 1) }) }
                        }) { Icon(Icons.Default.KeyboardArrowUp, "move up") }
                        IconButton(enabled = j < w.ladders.lastIndex, onClick = {
                            update(i) { it.copy(ladders = it.ladders.toMutableList().apply { swap(j, j + 1) }) }
                        }) { Icon(Icons.Default.KeyboardArrowDown, "move down") }
                        IconButton(onClick = { update(i) { it.copy(ladders = it.ladders - ladder) } }) { Icon(Icons.Default.Close, "remove") }
                    }
                }
                if (w.ladders.isEmpty()) {
                    Text("Add at least one movement.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val left = Workouts.MOVEMENT_NAMES.keys.filter { it !in w.ladders }
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(enabled = left.isNotEmpty() && w.ladders.size < Workouts.MAX_MOVES, onClick = { open = true }) {
                            Icon(Icons.Default.Add, null)
                            Text("Movement")
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            left.forEach { l ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(Workouts.movementName(l))
                                            current[l]?.let { Text("Now: $it", style = MaterialTheme.typography.bodySmall) }
                                        }
                                    },
                                    onClick = {
                                        open = false
                                        update(i) { it.copy(ladders = it.ladders + l) }
                                    },
                                )
                            }
                        }
                    }
                    if (workouts.size > 1) TextButton(onClick = { workouts.removeAt(i) }) { Text("Delete workout") }
                }
            }
        } }

        if (workouts.size < Workouts.MAX_WORKOUTS) {
            OutlinedButton(onClick = {
                val letter = 'A' + workouts.size
                workouts.add(Workout(Workouts.newKey(), "Strength $letter", emptyList()))
            }) {
                Icon(Icons.Default.Add, null)
                Text("Add workout")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = changed && valid, onClick = {
                scope.launch {
                    c.plan.saveWorkouts(workouts.toList())
                    nav.popBackStack()
                }
            }) { Text("Save") }
            TextButton(onClick = { confirmReset = true }) { Text("Reset to Strength A / B") }
        }
        Column(Modifier.height(24.dp)) {}
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to the workouts haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; nav.popBackStack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset workouts?") },
            text = { Text("This puts back the original Strength A and B. Your progress on each movement is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    workouts.clear()
                    workouts.addAll(c.plan.defaultWorkouts())
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
