package com.thegalaxybyte.momentum.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.db.FoodLogEntity
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.ui.ProgressRow
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.fmtDay
import com.thegalaxybyte.momentum.ui.fmtNum
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val meals = listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snacks & drinks")

@Composable
fun FoodScreen(nav: NavHostController) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var day by rememberSaveable { mutableLongStateOf(Days.today()) }
    val log by remember(day) { c.dao.foodLog(day) }.collectAsState(initial = emptyList())
    val profile by remember { c.dao.profile() }.collectAsState(initial = null)
    var editing by remember { mutableStateOf<FoodLogEntity?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { day -= 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "previous day") }
            Text(if (day == Days.today()) "Today" else fmtDay(day), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { if (day < Days.today()) day += 1 }, enabled = day < Days.today()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "next day")
            }
        }

        val kcal = log.sumOf { it.kcal }
        val protein = log.sumOf { it.protein }
        SectionCard {
            ProgressRow("Calories", kcal, (profile?.targetKcal ?: 0).toDouble(), "kcal")
            ProgressRow("Protein", protein, ((profile?.goalWeightKg ?: 0.0) * 1.6), "g")
            Text(
                "Carbs ${log.sumOf { it.carbs }.roundToInt()} g · Fat ${log.sumOf { it.fat }.roundToInt()} g",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedButton(onClick = { nav.navigate("food/ask/$day") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AutoAwesome, null)
            Text("  Ask: how many calories? Which is better?")
        }

        meals.forEach { (key, label) ->
            val items = log.filter { it.meal == key }
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (items.isNotEmpty()) Text("${items.sumOf { it.kcal }.roundToInt()} kcal", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { nav.navigate("food/add/$day/$key") }) { Icon(Icons.Default.Add, "add to $label") }
                }
                items.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${fmtNum(e.quantity)} × ${e.unit} · ${e.protein.roundToInt()} g protein", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${e.kcal.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    editing?.let { e ->
        QuantityDialog(
            title = e.name,
            unit = e.unit,
            kcalPerUnit = e.kcal / e.quantity,
            initial = e.quantity,
            confirm = "Save",
            onDismiss = { editing = null },
            onConfirm = { q -> scope.launch { c.repo.updateFoodQuantity(e, q) }; editing = null },
            onDelete = { scope.launch { c.repo.deleteFoodLog(e) }; editing = null },
        )
    }
}

@Composable
fun QuantityDialog(
    title: String,
    unit: String,
    kcalPerUnit: Double,
    initial: Double,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var q by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { q = (q - 0.5).coerceAtLeast(0.5) }) { Text("−", style = MaterialTheme.typography.headlineSmall) }
                    Text("${fmtNum(q)} × $unit", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { q += 0.5 }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5, 1.0, 1.5, 2.0, 3.0).forEach { v -> TextButton(onClick = { q = v }) { Text(fmtNum(v)) } }
                }
                Text("${(kcalPerUnit * q).roundToInt()} kcal", style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(q) }) { Text(confirm) } },
        dismissButton = {
            Row {
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
