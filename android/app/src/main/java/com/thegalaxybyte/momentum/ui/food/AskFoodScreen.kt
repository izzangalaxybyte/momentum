package com.thegalaxybyte.momentum.ui.food

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.TodayState
import com.thegalaxybyte.momentum.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import kotlin.math.roundToInt

private val examples = listOf(
    "Biryani or daal chawal, which is better tonight?",
    "How many calories in 3 gulab jamun?",
    "Samosa or fruit chaat for a snack?",
)

/** Ask the coach before eating: "how many calories is this?" or "which is better?". Any option can be logged. */
@Composable
fun AskFoodScreen(nav: NavHostController, day: Long) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val custom by remember { c.dao.customFoods() }.collectAsState(initial = emptyList())
    var today by remember { mutableStateOf<TodayState?>(null) }
    var question by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf("") }
    var best by remember { mutableStateOf<Int?>(null) }
    val options = remember { mutableStateListOf<MealRow>() }
    var logging by remember { mutableStateOf<MealRow?>(null) }
    var logged by remember { mutableStateOf<String?>(null) }
    val loggedRows = remember { mutableStateListOf<MealRow>() }

    LaunchedEffect(Unit) { today = c.plan.today() }

    fun ask(q: String = question) {
        question = q
        busy = true
        error = null
        logged = null
        scope.launch {
            runCatching {
                val b64 = photo?.let { withContext(Dispatchers.Default) { jpegBase64(it) } }
                c.coach.askFood(q, b64)
            }.onSuccess { r ->
                options.clear()
                loggedRows.clear()
                options.addAll(r.options.map { MealRow(it) })
                answer = r.answer
                best = r.best
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text("Ask before you eat", style = MaterialTheme.typography.titleLarge)
        }
        today?.let { t ->
            val kcal = (t.targets.target - t.eatenKcal).roundToInt()
            val protein = (t.targets.proteinG - t.eatenProtein).roundToInt()
            Text(
                "Left today: ${if (kcal >= 0) "$kcal kcal" else "${-kcal} kcal over"} · ${protein.coerceAtLeast(0)} g protein",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(1000) },
            placeholder = { Text("How many calories is this? Biryani or karahi, which is better?") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        PhotoInput(photo, onPhoto = { photo = it }, onError = { error = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)) {
            Button(enabled = !busy && (question.isNotBlank() || photo != null), onClick = { ask() }) { Text("Ask") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (answer.isEmpty() && !busy) {
            examples.forEach { e -> AssistChip(onClick = { ask(e) }, label = { Text(e) }) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (answer.isNotEmpty()) {
            SectionCard("Coach says") {
                Text(answer, style = MaterialTheme.typography.bodyLarge)
            }
            options.toList().forEachIndexed { i, r ->
                val food = r.item.foodId?.let { c.repo.food(it, custom) }
                SectionCard(if (i == best) "★ Better pick" else null, Modifier.padding(top = 12.dp)) {
                    ItemRow(r, name = food?.nameEn ?: r.item.name, kcal = r.kcal(food), inList = food != null, onRemove = null)
                    Text("${r.protein(food).roundToInt()} g protein", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = r !in loggedRows, onClick = { logging = r }) {
                        Text(if (r in loggedRows) "Logged ✓" else "I ate this: log it")
                    }
                }
            }
            logged?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Text(
            "Your question or photo is sent to the AI coach (Claude Haiku via OpenRouter) and isn't stored. " +
                "Brand names may be searched on the web.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Column(Modifier.height(24.dp)) {}
    }

    logging?.let { r ->
        val suggested = when (LocalTime.now().hour) {
            in 4..10 -> "breakfast"
            in 11..15 -> "lunch"
            in 18..23 -> "dinner"
            else -> "snack"
        }
        AlertDialog(
            onDismissRequest = { logging = null },
            title = { Text("Log to which meal?") },
            text = {
                Column {
                    meals.forEach { (key, label) ->
                        TextButton(onClick = {
                            logging = null
                            loggedRows.add(r)
                            scope.launch {
                                c.coach.logMeal(day, key, listOf(r.choice()))
                                logged = "Logged to $label."
                                today = c.plan.today()
                            }
                        }) { Text(if (key == suggested) "$label (now)" else label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { logging = null }) { Text("Cancel") } },
        )
    }
}
