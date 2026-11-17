package com.thegalaxybyte.momentum.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.FoodDef
import com.thegalaxybyte.momentum.ui.NumberField
import com.thegalaxybyte.momentum.ui.fmtNum
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AddFoodScreen(nav: NavHostController, day: Long, meal: String) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val custom by remember { c.dao.customFoods() }.collectAsState(initial = emptyList())
    val recent by remember { c.dao.recentFoods() }.collectAsState(initial = emptyList())
    var picked by remember { mutableStateOf<FoodDef?>(null) }
    var creating by remember { mutableStateOf(false) }

    val results = remember(query, custom, recent) {
        if (query.isBlank()) {
            val recentDefs = recent.mapNotNull { c.repo.food(it.foodId, custom) }
            recentDefs + c.repo.searchFoods("", custom).filter { f -> recentDefs.none { it.id == f.id } }
        } else c.repo.searchFoods(query, custom)
    }
    val mealLabel = meals.firstOrNull { it.first == meal }?.second ?: meal

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
            Text("Add to $mealLabel", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("roti, biryani, chai, daal…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { nav.navigate("food/ai/$day/$meal") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Icon(Icons.Default.AutoAwesome, null)
            Text("  Describe or snap your meal")
        }
        TextButton(onClick = { creating = true }) { Text("+ Create a custom food (e.g. Ammi's daal)") }
        if (query.isBlank() && recent.isNotEmpty()) Text("Recent first", style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = f }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.nameEn + if (f.custom) " ★" else "", style = MaterialTheme.typography.bodyLarge)
                        val urdu = if (f.nameUrdu.isNotBlank() && !f.nameUrdu.equals(f.nameEn, true)) "${f.nameUrdu} · " else ""
                        Text("${urdu}1 ${f.unit} · ${f.protein.roundToInt()} g protein", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${f.kcal.roundToInt()} kcal", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }

    picked?.let { f ->
        QuantityDialog(
            title = f.nameEn,
            unit = f.unit,
            kcalPerUnit = f.kcal,
            initial = 1.0,
            confirm = "Add",
            onDismiss = { picked = null },
            onConfirm = { q ->
                scope.launch {
                    c.repo.logFood(day, meal, f, q)
                    nav.popBackStack()
                }
                picked = null
            },
        )
    }
    if (creating) CustomFoodDialog(initialName = query, onDismiss = { creating = false }) { name, unit, grams, kcal, p, carbs, fat ->
        scope.launch {
            val e = c.repo.addCustomFood(name, unit, grams, kcal, p, carbs, fat)
            creating = false
            picked = c.repo.food(e.id, listOf(e))
        }
    }
}

@Composable
private fun CustomFoodDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Double, Double, Double, Double) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var unit by remember { mutableStateOf("bowl") }
    var grams by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Serving (bowl, piece, plate…)") }, singleLine = true)
                NumberField(grams, { grams = it }, "Grams per serving (optional)")
                NumberField(kcal, { kcal = it }, "Calories per serving")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField(protein, { protein = it }, "Protein g", Modifier.weight(1f))
                    NumberField(carbs, { carbs = it }, "Carbs g", Modifier.weight(1f))
                    NumberField(fat, { fat = it }, "Fat g", Modifier.weight(1f))
                }
                Text("Tip: start from a similar built-in item and adjust for extra oil/ghee.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && d(kcal) > 0,
                onClick = { onSave(name, unit, d(grams), d(kcal), d(protein), d(carbs), d(fat)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private fun FoodDef.summary() = "${fmtNum(kcal)} kcal"
