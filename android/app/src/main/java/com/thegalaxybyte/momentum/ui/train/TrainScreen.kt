package com.thegalaxybyte.momentum.ui.train

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.domain.CardioPhase
import com.thegalaxybyte.momentum.domain.Target
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.sessionNames
import com.thegalaxybyte.momentum.ui.today.todayFlow

@Composable
fun TrainScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val today by remember { todayFlow(c) }.collectAsState(initial = null)
    val ladders by remember { c.dao.ladderStates() }.collectAsState(initial = emptyList())
    val targets by produceState<Map<String, List<Target>>>(emptyMap(), ladders, today) {
        val p = c.dao.profileNow() ?: return@produceState
        val engine = c.plan.engine(p)
        val wk = c.plan.weekIndex(p)
        value = sessionNames.keys.associateWith { key -> c.plan.ladderStates(p, key).map { engine.target(it, wk) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Train", style = MaterialTheme.typography.headlineSmall)

        today?.let { s ->
            SectionCard("Strength") {
                Text(
                    "Next up: ${sessionNames[s.nextSessionKey]}. Sessions alternate A and B on your training days.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessionNames.forEach { (key, name) ->
                        if (key == s.nextSessionKey) Button(onClick = { nav.navigate("session/$key") }) { Text("Start $name") }
                        else OutlinedButton(onClick = { nav.navigate("session/$key") }) { Text(name) }
                    }
                }
            }
        }

        sessionNames.forEach { (key, name) ->
            SectionCard(name) {
                targets[key].orEmpty().forEach { t ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate("exercise/${t.exercise.id}") }.padding(vertical = 4.dp)) {
                        Text(t.exercise.name, style = MaterialTheme.typography.bodyLarge)
                        Text(targetText(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        today?.let { s ->
            SectionCard("Cardio this week") {
                val phase = when (s.cardioState.phase) {
                    CardioPhase.WALK -> "Walking phase: building up to five 45-minute brisk walks a week. Then walk/jog intervals start."
                    CardioPhase.INTERVALS -> "Walk/jog phase (level ${s.cardioState.intervalLevel + 1} of 7): the jogging parts grow each week you finish them comfortably."
                    CardioPhase.RUN -> "Running phase: easy continuous runs, up to 10% more each week."
                }
                Text(phase, style = MaterialTheme.typography.bodySmall)
                if (s.cardioPlan.deload) Text("Lighter week: recover and keep moving.", style = MaterialTheme.typography.bodySmall)
                s.cardioPlan.items.forEach { Text("• ${it.label()}", style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Rate each session easy/good/hard on the Today screen; 'hard' holds the plan where it is.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
