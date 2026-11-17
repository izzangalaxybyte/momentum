package com.thegalaxybyte.momentum.ui.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.coach.MealItem
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.fmtNum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private class MealRow(val item: MealItem, quantity: Double) {
    var quantity by mutableStateOf(quantity)
}

/** Describe or photograph a meal; the coach turns it into log items the user checks before saving. */
@Composable
fun MealAiScreen(nav: NavHostController, day: Long, meal: String) {
    val context = LocalContext.current
    val c = context.container
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

    fun load(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            photo = runCatching { withContext(Dispatchers.IO) { decodeScaled(context, uri) } }
                .onFailure { error = "Couldn't open that photo." }.getOrNull()
        }
    }

    val cameraFile = remember { File(context.cacheDir, "photos/meal.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) load(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { load(it) }

    fun analyse() {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val b64 = photo?.let { withContext(Dispatchers.Default) { jpegBase64(it) } }
                c.coach.parseMeal(text, b64)
            }.onSuccess { r ->
                rows.clear()
                rows.addAll(r.items.map { MealRow(it, it.quantity) })
                note = r.note
                analysed = true
                if (r.items.isEmpty()) error = "The coach didn't find any food in that. Try describing it differently."
            }.onFailure { error = it.message }
            busy = false
        }
    }

    fun kcalOf(r: MealRow): Double {
        val food = r.item.foodId?.let { c.repo.food(it, custom) }
        return if (food != null) food.kcal * r.quantity
        else (r.item.kcal ?: 0.0) * if (r.item.quantity > 0) r.quantity / r.item.quantity else 1.0
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { takePhoto.launch(cameraUri) }) {
                Icon(Icons.Default.PhotoCamera, null)
                Text("  Take photo")
            }
            OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.PhotoLibrary, null)
                Text("  Choose")
            }
        }
        photo?.let { bmp ->
            Row(verticalAlignment = Alignment.Top) {
                Image(
                    bmp.asImageBitmap(), "meal photo", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)),
                )
                IconButton(onClick = { photo = null }) { Icon(Icons.Default.Close, "remove photo") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 8.dp)) {
            Button(enabled = !busy && (text.isNotBlank() || photo != null), onClick = ::analyse) {
                Text(if (analysed) "Work it out again" else "Work it out")
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (rows.isNotEmpty()) {
            SectionCard("Check before logging") {
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.toList().forEach { r ->
                    val food = r.item.foodId?.let { c.repo.food(it, custom) }
                    ItemRow(r, name = food?.nameEn ?: r.item.name, kcal = kcalOf(r), inList = food != null) { rows.remove(r) }
                    HorizontalDivider()
                }
                val total = rows.sumOf { kcalOf(it) }
                Text("Total ≈ ${total.roundToInt()} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(
                    enabled = rows.isNotEmpty() && rows.all { it.quantity > 0 },
                    onClick = {
                        scope.launch {
                            c.coach.logMeal(day, meal, rows.map { it.item to it.quantity })
                            nav.popBackStack("food", inclusive = false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Log ${rows.size} item${if (rows.size == 1) "" else "s"} to $mealLabel") }
            }
        }
        Text(
            "Your text or photo is sent to the AI coach (Claude Haiku via OpenRouter) and isn't stored.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Column(Modifier.height(24.dp)) {}
    }
}

@Composable
private fun ItemRow(r: MealRow, name: String, kcal: Double, inList: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${fmtNum(r.quantity)} × ${r.item.unit} · ${kcal.roundToInt()} kcal" + if (inList) "" else " · estimate",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { r.quantity = max(0.0, r.quantity - 0.5) }) { Text("−") }
        TextButton(onClick = { r.quantity += 0.5 }) { Text("+") }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "remove") }
    }
}

/** Decode with EXIF rotation applied, at most 1280 px on the long side. */
private fun decodeScaled(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1.0, 1280.0 / max(w, h))
        decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

private fun jpegBase64(bmp: Bitmap): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
