package com.thegalaxybyte.momentum.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.data.coach.Proposal
import com.thegalaxybyte.momentum.data.db.CoachReviewEntity
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.fmtDay
import com.thegalaxybyte.momentum.ui.goTab
import kotlinx.coroutines.launch

@Composable
fun CoachScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val hasToken by LocalContext.current.container.tokens.hasToken.collectAsState(initial = true)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Weekly review") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Chat") })
        }
        if (!hasToken) {
            Box(Modifier.padding(16.dp)) {
                SectionCard("Connect the coach") {
                    Text(
                        "The coach runs on your Momentum server. Enter your sync token in Settings → Cloud sync first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { nav.goTab("settings") }) { Text("Open settings") }
                }
            }
        } else if (tab == 0) ReviewTab() else ChatTab()
    }
}

// ------------------------------------------------------------------ weekly review

@Composable
private fun ReviewTab() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val reviews by remember { c.dao.coachReviews() }.collectAsState(initial = null)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showOlder by remember { mutableStateOf(false) }

    val latest = reviews?.firstOrNull()
    LaunchedEffect(latest?.createdAt) { latest?.let { c.prefs.setCoachSeen(it.createdAt) } }

    fun request() {
        busy = true
        error = null
        scope.launch {
            runCatching { c.coach.requestReview() }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val list = reviews ?: return@Column
        if (list.isEmpty()) {
            SectionCard("Your weekly review") {
                Text(
                    "Every Monday morning the coach looks at your last week (food, weight, workouts, walks, steps) " +
                        "and writes a short review. It can also suggest plan changes, which you accept or dismiss.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            ReviewCard(list.first(), current = list.first().weekStart == Days.monday(Days.today()))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !busy, onClick = ::request) {
                Text(if (latest?.weekStart == Days.monday(Days.today())) "Redo this week's review" else "Review my week now")
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val older = list.drop(1)
        if (older.isNotEmpty()) {
            TextButton(onClick = { showOlder = !showOlder }) { Text(if (showOlder) "Hide earlier reviews" else "Earlier reviews (${older.size})") }
            if (showOlder) older.forEach { ReviewCard(it, current = false) }
        }
        Text(
            "Suggestions come from an AI (Claude Haiku). Changes are kept within the app's safety limits. Not medical advice.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewCard(review: CoachReviewEntity, current: Boolean) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val body = remember(review.body) { c.coach.body(review) }
    SectionCard(if (current) "This week's review" else "Week of ${fmtDay(review.weekStart, "d MMM")}") {
        if (body.period.isNotBlank()) {
            Text(body.period, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(body.summary, style = MaterialTheme.typography.bodyLarge)
        if (body.wins.isNotEmpty()) Bullets("Wins", body.wins, "✓")
        if (body.concerns.isNotEmpty()) Bullets("Watch out for", body.concerns, "!")
        if (body.tip.isNotBlank()) {
            Text("Tip for this week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body.tip, style = MaterialTheme.typography.bodyMedium)
        }
        if (body.proposals.isNotEmpty()) {
            HorizontalDivider()
            Text("Suggested changes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            body.proposals.forEach { p ->
                ProposalRow(p, onDecide = { accept -> scope.launch { c.coach.decide(review, p.id, accept) } })
            }
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>, mark: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    items.forEach { Text("$mark  $it", style = MaterialTheme.typography.bodyMedium) }
}

private fun Proposal.title(): String = when (kind) {
    "calorie_target" -> "Change daily calories to ${value ?: "?"} kcal"
    "exercise_easier" -> "Make ${ladderName(target)} easier"
    "exercise_harder" -> "Make ${ladderName(target)} harder"
    "cardio_easier" -> "Ease off the walking/running plan"
    "cardio_harder" -> "Step up the walking/running plan"
    else -> kind
}

private fun ladderName(id: String?) = when (id) {
    "push" -> "push-ups"
    "squat" -> "squats"
    "lunge" -> "lunges"
    "hinge" -> "hip hinges (bridges/deadlifts)"
    "row" -> "rows"
    "press" -> "overhead press"
    "core-a", "core-b" -> "core work"
    else -> id ?: "this exercise"
}

@Composable
private fun ProposalRow(p: Proposal, onDecide: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(p.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (p.reason.isNotBlank()) Text(p.reason, style = MaterialTheme.typography.bodySmall)
        when (p.status) {
            "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide(true) }) { Text("Accept") }
                OutlinedButton(onClick = { onDecide(false) }) { Text("Dismiss") }
            }
            "accepted" -> Text("Accepted. ${p.result.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            else -> Text("Dismissed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ chat

private val starters = listOf(
    "How am I doing this week?",
    "What should I eat for dinner tonight?",
    "My knees hurt during squats. What should I do?",
    "I missed 3 days. How do I get back on track?",
)

@Composable
private fun ChatTab() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val messages by remember { c.dao.coachMessages() }.collectAsState(initial = emptyList())
    var input by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val count = messages.size + (if (pending != null) 2 else 0)
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || pending != null) return
        pending = t
        input = ""
        error = null
        scope.launch {
            runCatching { c.coach.send(t) }.onFailure {
                error = it.message
                input = t
            }
            pending = null
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && pending == null) {
                item {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask your coach anything about food, training or getting back on track. " +
                            "It can see a summary of your last week.", style = MaterialTheme.typography.bodyMedium)
                        starters.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m.text, mine = m.role == "user") }
            pending?.let { p ->
                item(key = "pending") { Bubble(p, mine = true) }
                item(key = "typing") {
                    Text("Coach is typing…", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(2000) },
                placeholder = { Text("Message your coach") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            IconButton(enabled = input.isNotBlank() && pending == null, onClick = { send(input) }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
