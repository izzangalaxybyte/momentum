package com.thegalaxybyte.momentum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.PlanService
import com.thegalaxybyte.momentum.data.db.ProfileEntity
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.ui.NumberField
import com.thegalaxybyte.momentum.ui.SectionCard
import kotlinx.coroutines.launch

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** New phone: pull everything from the cloud instead of starting over. */
@Composable
private fun RestoreCard() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    SectionCard("Already use Momentum?") {
        if (!open) {
            androidx.compose.material3.TextButton(onClick = { open = true }) { Text("Restore my data from the cloud") }
        } else {
            androidx.compose.material3.OutlinedTextField(
                token, { token = it }, label = { Text("Sync token") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                scope.launch {
                    msg = "Restoring…"
                    val url = c.prefs.apiUrlNow()
                    c.sync.test(url, token).onFailure { msg = it.message ?: "Failed"; return@launch }
                    c.tokens.set(token)
                    msg = c.sync.sync().fold(
                        { if (c.dao.profileNow() == null) "Connected, but no saved profile was found." else "Restored" },
                        { it.message ?: "Restore failed" },
                    )
                }
            }) { Text("Restore") }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Onboarding (first run) and the "edit profile & equipment" screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onboarding: Boolean, onDone: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<ProfileEntity?>(null) }
    var sex by remember { mutableStateOf("MALE") }
    var birthYear by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf(1, 3, 5)) }
    var dumbbells by remember { mutableStateOf("2, 3, 4, 5") }
    var bar by remember { mutableStateOf("10") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        c.dao.profileNow()?.let { p ->
            existing = p
            sex = p.sex; birthYear = p.birthYear.toString(); height = p.heightCm.toString()
            goal = p.goalWeightKg.toString(); days = p.trainingDays.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            dumbbells = p.dumbbellLoads.replace(",", ", "); bar = p.barLoads
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onboarding) {
            Text("Welcome to Momentum", style = MaterialTheme.typography.headlineMedium)
            Text(
                "A few details to set your calorie target and build your plan. You can change all of this later.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("Profile & equipment", style = MaterialTheme.typography.headlineSmall)
        }

        SectionCard("You") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == "MALE", onClick = { sex = "MALE" }, label = { Text("Male") })
                FilterChip(selected = sex == "FEMALE", onClick = { sex = "FEMALE" }, label = { Text("Female") })
            }
            NumberField(birthYear, { birthYear = it }, "Birth year", Modifier.fillMaxWidth(), decimal = false)
            NumberField(height, { height = it }, "Height (cm)", Modifier.fillMaxWidth())
            if (existing == null) NumberField(weight, { weight = it }, "Current weight (kg)", Modifier.fillMaxWidth())
            NumberField(goal, { goal = it }, "Goal weight (kg)", Modifier.fillMaxWidth())
        }

        SectionCard("Strength training days") {
            Text("Pick the days you can train. Weeks 1–4 use 2 of them, then 3.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayNames.forEachIndexed { i, n ->
                    val d = i + 1
                    FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(n) })
                }
            }
        }

        SectionCard("Equipment") {
            Text(
                "Weights each dumbbell can be set to (kg), and the bar's total weight. Bodyweight exercises need nothing.",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField(dumbbells, { dumbbells = it }, "Dumbbell weights, e.g. 2, 3, 4, 5", Modifier.fillMaxWidth())
            NumberField(bar, { bar = it }, "Bar weights, e.g. 10", Modifier.fillMaxWidth())
        }

        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)

        if (onboarding) RestoreCard()

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val by = birthYear.toIntOrNull()
                val h = height.toDoubleOrNull()
                val w = weight.toDoubleOrNull()
                val g = goal.toDoubleOrNull()
                error = when {
                    by == null || by !in 1920..2015 -> "Enter a valid birth year"
                    h == null || h !in 120.0..230.0 -> "Enter your height in cm"
                    existing == null && (w == null || w !in 30.0..350.0) -> "Enter your current weight in kg"
                    g == null || g !in 30.0..300.0 -> "Enter a goal weight in kg"
                    days.isEmpty() -> "Pick at least one training day"
                    else -> ""
                }
                if (error.isNotEmpty()) return@Button
                scope.launch {
                    val base = existing ?: ProfileEntity(startWeightKg = w!!, startEpochDay = Days.today())
                    c.repo.saveProfile(
                        base.copy(
                            sex = sex, birthYear = by!!, heightCm = h!!, goalWeightKg = g!!,
                            trainingDays = days.sorted().joinToString(","),
                            dumbbellLoads = PlanService.parseLoads(dumbbells).joinToString(",") { com.thegalaxybyte.momentum.ui.fmtNum(it) },
                            barLoads = PlanService.parseLoads(bar).joinToString(",") { com.thegalaxybyte.momentum.ui.fmtNum(it) },
                            // re-calibrate the calorie target with the new details
                            targetKcal = null, targetSetEpochDay = null,
                        ),
                    )
                    if (existing == null) c.repo.addWeight(Days.today(), w!!)
                    c.plan.ensureWeeklyUpdates()
                    onDone()
                }
            },
        ) { Text(if (onboarding) "Build my plan" else "Save") }
    }
}
