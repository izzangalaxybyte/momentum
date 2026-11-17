package com.thegalaxybyte.momentum.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.AppContainer
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.TodayState
import com.thegalaxybyte.momentum.domain.CardioPhase
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.domain.Rating
import com.thegalaxybyte.momentum.ui.Brand
import com.thegalaxybyte.momentum.ui.NumberField
import com.thegalaxybyte.momentum.ui.ProgressRow
import com.thegalaxybyte.momentum.ui.RatingChips
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.Stepper
import com.thegalaxybyte.momentum.ui.fmtDay
import com.thegalaxybyte.momentum.ui.fmtNum
import com.thegalaxybyte.momentum.ui.goTab
import com.thegalaxybyte.momentum.ui.sessionNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Recomputes the plan whenever anything it depends on changes. */
@OptIn(ExperimentalCoroutinesApi::class)
fun todayFlow(c: AppContainer): Flow<TodayState?> {
    val day = Days.today()
    val triggers: List<Flow<Any?>> = listOf(
        c.dao.profile(), c.dao.weights(), c.dao.dayTotals(day), c.dao.cardio(day - 7),
        c.dao.activity(day), c.dao.sessions(), c.dao.settingFlow("cardio"),
    )
    return combine(triggers) { it.size }.mapLatest { c.plan.today() }
}

@Composable
fun TodayScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val flow = remember { todayFlow(c) }
    val state by flow.collectAsState(initial = null)
    val s = state ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    val hasToken by c.tokens.hasToken.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(fmtDay(s.day, "EEEE, d MMMM"), style = MaterialTheme.typography.headlineSmall)
            val phase = when (s.cardioState.phase) {
                CardioPhase.WALK -> "walking phase"
                CardioPhase.INTERVALS -> "walk/jog phase"
                CardioPhase.RUN -> "running phase"
            }
            Text(
                "Week ${s.weekIndex + 1} · $phase" + if (s.cardioPlan.deload) " · lighter week" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
            IconButton(onClick = { nav.goTab("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        }

        CoachReadyCard(s.day) { nav.goTab("coach") }
        CaloriesCard(s) { nav.goTab("food") }
        PlanCard(s, nav)
        WeighInCard(s) { kg -> scope.launch { c.repo.addWeight(s.day, kg) } }
        WeekStrip(s)

        if (!hasToken) {
            SectionCard("Set up cloud backup") {
                Text("Enter your sync token in Settings so your data is backed up.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { nav.goTab("settings") }) { Text("Open settings") }
            }
        }
    }
}

/** Shown on Mondays (or after "Review my week now") until the review is opened and its suggestions handled. */
@Composable
private fun CoachReadyCard(day: Long, onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val reviews by remember { c.dao.coachReviews() }.collectAsState(initial = emptyList())
    val seen by c.prefs.coachSeen.collectAsState(initial = Long.MAX_VALUE)
    val latest = reviews.firstOrNull() ?: return
    if (latest.weekStart != Days.monday(day)) return
    val body = remember(latest.body) { c.coach.body(latest) }
    val pending = body.proposals.count { it.status == "pending" }
    if (latest.createdAt <= seen && pending == 0) return
    SectionCard("Your weekly review is ready") {
        Text(body.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        if (pending > 0) Text("$pending suggested change${if (pending == 1) "" else "s"} to look at", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpen) { Text("Open coach") }
    }
}

@Composable
private fun CaloriesCard(s: TodayState, onLog: () -> Unit) {
    val left = s.targets.target - s.eatenKcal
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    if (left >= 0) "${left.roundToInt()} kcal left" else "${(-left).roundToInt()} kcal over",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Target ${s.targets.target} kcal" + if (s.targets.adaptive) " (calibrated to you)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onLog) { Text("Log food") }
        }
        ProgressRow("Eaten", s.eatenKcal, s.targets.target.toDouble(), "kcal")
        ProgressRow("Protein", s.eatenProtein, s.targets.proteinG.toDouble(), "g")
        Text("Steps today: ${s.stepsToday}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlanCard(s: TodayState, nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val plan = s.todayPlan
    var showLog by remember { mutableStateOf(false) }
    SectionCard("Today's plan") {
        if (plan.strength) {
            val name = sessionNames[s.nextSessionKey] ?: s.nextSessionKey
            if (s.strengthDoneToday) {
                Text("✓ $name done. Nice work!", color = Brand)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text("~30 min · full body + core", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { nav.navigate("session/${s.nextSessionKey}") }) { Text("Start") }
                }
            }
        }
        plan.cardio?.let { item ->
            val doneMin = s.cardioToday.sumOf { it.durationSec } / 60
            Text(item.label(), style = MaterialTheme.typography.titleMedium)
            Text(
                if (doneMin >= item.minutes) "✓ Done: $doneMin min recorded today"
                else if (doneMin > 0) "$doneMin of ${item.minutes} min recorded today"
                else "Record it on your watch; it shows up here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = if (doneMin >= item.minutes) Brand else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.cardioToday.firstOrNull()?.let { latest ->
                Text("How did it feel?", style = MaterialTheme.typography.bodySmall)
                RatingChips(latest.rating?.let { Rating.valueOf(it) }) { r -> scope.launch { c.repo.rateCardio(latest, r) } }
            }
            TextButton(onClick = { showLog = true }) { Text("Log a walk/run manually") }
        }
        if (plan.rest) {
            Text("Rest day", style = MaterialTheme.typography.titleMedium)
            Text("Recovery is part of the plan. A gentle walk is fine.", style = MaterialTheme.typography.bodySmall)
        }
        if (!plan.strength && plan.cardio == null && !plan.rest) {
            Text("Nothing planned today.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (showLog) ManualCardioDialog(onDismiss = { showLog = false }) { kind, min, rating ->
        scope.launch { c.repo.addManualCardio(s.day, kind, min, rating) }
        showLog = false
    }
}

@Composable
private fun ManualCardioDialog(onDismiss: () -> Unit, onSave: (String, Int, Rating?) -> Unit) {
    var kind by remember { mutableStateOf("walk") }
    var minutes by remember { mutableStateOf(30) }
    var rating by remember { mutableStateOf<Rating?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log cardio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "walk", { kind = "walk" }, label = { Text("Walk") })
                    FilterChip(kind == "run", { kind = "run" }, label = { Text("Run / jog") })
                }
                Stepper(minutes, { minutes = it }, "min", step = 5, min = 5)
                RatingChips(rating) { rating = it }
            }
        },
        confirmButton = { Button(onClick = { onSave(kind, minutes, rating) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WeighInCard(s: TodayState, onSave: (Double) -> Unit) {
    var kg by remember { mutableStateOf("") }
    val trend = s.trend.lastOrNull()
    SectionCard("Weight") {
        if (trend != null) {
            val lost = s.profile.startWeightKg - trend.trend
            Text(
                "Trend ${fmtNum(trend.trend)} kg · " +
                    (if (lost >= 0) "${fmtNum(lost)} kg down" else "${fmtNum(-lost)} kg up") +
                    " · ${fmtNum((trend.trend - s.profile.goalWeightKg).coerceAtLeast(0.0))} kg to goal",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!s.weighedToday) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(kg, { kg = it }, "Today's weight (kg)", Modifier.weight(1f))
                Button(onClick = { kg.replace(',', '.').toDoubleOrNull()?.let { if (it in 30.0..350.0) onSave(it) } }) { Text("Save") }
            }
            Text("Weigh in the morning, after the toilet, before eating.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("✓ Weighed in today", style = MaterialTheme.typography.bodySmall, color = Brand)
        }
    }
}

@Composable
private fun WeekStrip(s: TodayState) {
    SectionCard("This week") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            s.week.forEach { d ->
                val today = d.epochDay == s.day
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtDay(d.epochDay, "EEE").take(2), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.size(36.dp).background(
                            if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tag = buildString {
                            if (d.strength) append("S")
                            d.cardio?.let { append(if (it.kind.name == "WALK") "W" else "J") }
                            if (d.rest) append("R")
                        }
                        Text(
                            tag.ifEmpty { "·" },
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = if (today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Text("S strength · W walk · J walk/jog or run · R rest", style = MaterialTheme.typography.labelSmall)
    }
}
