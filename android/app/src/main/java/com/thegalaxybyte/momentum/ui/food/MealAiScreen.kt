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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.thegalaxybyte.momentum.data.FoodDef
import com.thegalaxybyte.momentum.data.coach.CoachService
import com.thegalaxybyte.momentum.data.coach.MealItem
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.fmtNum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** One item the coach suggested, with the user's edits. Branded items are saved to "my foods" by default. */
class MealRow(val item: MealItem, quantity: Double = item.quantity) {
    var quantity by mutableStateOf(quantity)
    var save by mutableStateOf(item.branded)

    fun choice() = CoachService.Choice(item, quantity, save && item.foodId == null)

    /** Calories for the current quantity: from the food list when matched, else scaled from the coach's estimate. */
    fun kcal(food: FoodDef?): Double = if (food != null) food.kcal * quantity
    else (item.kcal ?: 0.0) * if (item.quantity > 0) quantity / item.quantity else 1.0

    fun protein(food: FoodDef?): Double = if (food != null) food.protein * quantity
    else (item.protein ?: 0.0) * if (item.quantity > 0) quantity / item.quantity else 1.0
}

/** Describe or photograph a meal; the coach turns it into log items the user checks before saving. */
@Composable
fun MealAiScreen(nav: NavHostController, day: Long, meal: String) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val custom by remember { c.dao.customFoods() }.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<MealRow>() }
    var analysed by remember { mutableStateOf(false) }
    val mealLabel = meals.firstOrNull { it.first == meal }?.second ?: meal

    fun analyse() {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val b64 = photo?.let { withContext(Dispatchers.Default) { jpegBase64(it) } }
                c.coach.parseMeal(text, b64)
            }.onSuccess { r ->
                rows.clear()
                rows.addAll(r.items.map { MealRow(it) })
                note = r.note
                analysed = true
                if (r.items.isEmpty()) error = "The coach didn't find any food in that. Try describing it differently."
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text("Describe your $mealLabel", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(1000) },
            placeholder = { Text("do roti, aadha bowl qeema, ek cup chai…") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        PhotoInput(photo, onPhoto = { photo = it }, onError = { error = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)) {
            Button(enabled = !busy && (text.isNotBlank() || photo != null), onClick = ::analyse) {
                Text(if (analysed) "Work it out again" else "Work it out")
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Brands are looked up online, which takes a few seconds more", style = MaterialTheme.typography.labelSmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (rows.isNotEmpty()) {
            SectionCard("Check before logging") {
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.toList().forEach { r ->
                    val food = r.item.foodId?.let { c.repo.food(it, custom) }
                    ItemRow(r, name = food?.nameEn ?: r.item.name, kcal = r.kcal(food), inList = food != null) { rows.remove(r) }
                    HorizontalDivider()
                }
                val total = rows.sumOf { r -> r.kcal(r.item.foodId?.let { c.repo.food(it, custom) }) }
                Text("Total ≈ ${total.roundToInt()} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(
                    enabled = rows.isNotEmpty() && rows.all { it.quantity > 0 },
                    onClick = {
                        scope.launch {
                            c.coach.logMeal(day, meal, rows.map { it.choice() })
                            nav.popBackStack("food", inclusive = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log ${rows.size} item${if (rows.size == 1) "" else "s"} to $mealLabel") }
            }
        }
        Text(
            "Your text or photo is sent to the AI coach (Claude Haiku via OpenRouter) and isn't stored. " +
                "Brand names may be searched on the web.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Column(Modifier.height(24.dp)) {}
    }
}

/** One suggested item: name, quantity stepper, calories, where the numbers came from, and "save to my foods". */
@Composable
fun ItemRow(r: MealRow, name: String, kcal: Double, inList: Boolean, onRemove: (() -> Unit)?) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                val origin = when {
                    inList -> ""
                    r.item.source != null -> " · from ${r.item.source}"
                    else -> " · estimate"
                }
                Text("${fmtNum(r.quantity)} × ${r.item.unit} · ${kcal.roundToInt()} kcal$origin", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { r.quantity = max(0.0, r.quantity - 0.5) }) { Text("−") }
            TextButton(onClick = { r.quantity += 0.5 }) { Text("+") }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "remove") }
        }
        if (!inList && r.item.kcal != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.save, onCheckedChange = { r.save = it })
                Text("Save to my foods (pick it from the list next time)", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
