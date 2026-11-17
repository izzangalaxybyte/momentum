package com.thegalaxybyte.momentum.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.BuildConfig
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.ui.SectionCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()

    // Health Connect
    var hcStatus by remember { mutableIntStateOf(c.health.status()) }
    var hcGranted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hcMessage by remember { mutableStateOf("") }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        hcGranted = granted
        scope.launch { hcMessage = "Imported ${c.health.importRecent()} new records" }
    }
    LaunchedEffect(Unit) { hcStatus = c.health.status(); hcGranted = runCatching { c.health.granted() }.getOrDefault(emptySet()) }

    // Sync
    val savedUrl by c.prefs.apiUrl.collectAsState(initial = "")
    val hasToken by c.tokens.hasToken.collectAsState(initial = false)
    val status by c.prefs.syncStatus.collectAsState(initial = null)
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf("") }
    var syncMessage by remember { mutableStateOf("") }

    // Export
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val text = c.repo.exportJson()
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                .onSuccess { syncMessage = "Backup exported" }
                .onFailure { syncMessage = "Export failed: ${it.message}" }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Profile & plan") {
            Text("Height, goal, training days and equipment.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { nav.navigate("profile") }) { Text("Edit profile & equipment") }
        }

        SectionCard("Samsung watch (Health Connect)") {
            when (hcStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    val ok = hcGranted.containsAll(c.health.readPermissions)
                    Text(
                        if (ok) "Connected. Walks, runs, steps, heart rate and weight are imported automatically."
                        else "Not connected yet. In Samsung Health, open Settings → Health Connect and allow sharing, then connect here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { hcLauncher.launch(c.health.allPermissions) }) { Text(if (ok) "Permissions" else "Connect") }
                        if (ok) OutlinedButton(onClick = {
                            scope.launch { hcMessage = "Imported ${c.health.importRecent()} new records" }
                        }) { Text("Import now") }
                    }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    Text("Health Connect needs an update from the Play Store.", style = MaterialTheme.typography.bodySmall)
                else -> Text("Health Connect isn't available on this device.", style = MaterialTheme.typography.bodySmall)
            }
            if (hcMessage.isNotEmpty()) Text(hcMessage, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Cloud sync") {
            Text(
                "Backs up everything to your Momentum server and restores it on a new phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it },
                label = { Text(if (hasToken) "Token (saved; type to replace)" else "Token, e.g. ABCDE-FGHIJ-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val t = token.ifBlank { c.tokens.get().orEmpty() }
                        syncMessage = "Checking…"
                        c.sync.test(url, t)
                            .onSuccess {
                                c.prefs.setApiUrl(url)
                                if (token.isNotBlank()) c.tokens.set(token)
                                token = ""
                                syncMessage = "Connected. Syncing…"
                                syncMessage = c.sync.sync().fold({ "Synced" }, { it.message ?: "Sync failed" })
                            }
                            .onFailure { syncMessage = it.message ?: "Failed" }
                    }
                }) { Text("Save & sync") }
                if (hasToken) OutlinedButton(onClick = {
                    scope.launch { syncMessage = c.sync.sync().fold({ "Synced" }, { it.message ?: "Sync failed" }) }
                }) { Text("Sync now") }
            }
            status?.let {
                val last = if (it.lastSyncAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.lastSyncAt)) else "never"
                Text("Last sync: $last" + if (it.lastError.isNotEmpty()) " · ${it.lastError}" else "", style = MaterialTheme.typography.bodySmall)
            }
            if (syncMessage.isNotEmpty()) Text(syncMessage, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Backup") {
            Text("Save all your data as a JSON file.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { exportLauncher.launch("momentum-backup.json") }) { Text("Export backup") }
        }

        SectionCard("About") {
            Text("Momentum ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Exercise demo images: free-exercise-db (public domain). Food values are averages; home cooking varies. " +
                    "Not medical advice — check with a doctor before starting a weight-loss or training programme.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
