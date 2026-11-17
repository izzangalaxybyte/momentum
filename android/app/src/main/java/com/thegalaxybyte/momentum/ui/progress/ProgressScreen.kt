package com.thegalaxybyte.momentum.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thegalaxybyte.momentum.container
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.domain.WeightPoint
import com.thegalaxybyte.momentum.domain.WeightTrend
import com.thegalaxybyte.momentum.domain.fmtKg
import com.thegalaxybyte.momentum.ui.SectionCard
import com.thegalaxybyte.momentum.ui.Warn
import com.thegalaxybyte.momentum.ui.fmtDay
import com.thegalaxybyte.momentum.ui.fmtNum
import kotlin.math.roundToInt

@Composable
fun ProgressScreen() {
    val c = LocalContext.current.container
    val today = remember { Days.today() }
    val weights by remember { c.dao.weights() }.collectAsState(initial = emptyList())
    val totals by remember { c.dao.dayTotals(today - 13) }.collectAsState(initial = emptyList())
    val cardio by remember { c.dao.cardio(Days.monday(today) - 7 * 7) }.collectAsState(initial = emptyList())
    val activity by remember { c.dao.activity(today - 6) }.collectAsState(initial = emptyList())
    val ladders by remember { c.dao.ladderStates() }.collectAsState(initial = emptyList())
    val profile by remember { c.dao.profile() }.collectAsState(initial = null)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Progress", style = MaterialTheme.typography.headlineSmall)

        val trend = remember(weights) { WeightTrend.compute(weights.map { WeightPoint(it.epochDay, it.kg) }) }
        SectionCard("Weight") {
            if (trend.size < 2) {
                Text("Weigh in a few times to see your trend.", style = MaterialTheme.typography.bodySmall)
            } else {
                val p = profile
                val last = trend.last()
                if (p != null) {
                    Text(
                        "Start ${fmtNum(p.startWeightKg)} kg → now ${fmtNum(last.trend)} kg (trend) · goal ${fmtNum(p.goalWeightKg)} kg",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val recent = trend.filter { it.epochDay >= last.epochDay - 7 }
                if (recent.size >= 2) {
                    val perWeek = (recent.last().trend - recent.first().trend) / (recent.last().epochDay - recent.first().epochDay).coerceAtLeast(1) * 7
                    Text("Last week: ${if (perWeek <= 0) "−" else "+"}${fmtNum(kotlin.math.abs(perWeek))} kg/week", style = MaterialTheme.typography.bodySmall)
                }
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    val minD = trend.first().epochDay
                    val maxD = trend.last().epochDay.coerceAtLeast(minD + 1)
                    val lo = trend.minOf { minOf(it.kg, it.trend) } - 0.5
                    val hi = trend.maxOf { maxOf(it.kg, it.trend) } + 0.5
                    fun x(d: Long) = (d - minD).toFloat() / (maxD - minD) * size.width
                    fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
                    trend.forEach { drawCircle(muted, 3.dp.toPx(), Offset(x(it.epochDay), y(it.kg))) }
                    val path = Path()
                    trend.forEachIndexed { i, t -> if (i == 0) path.moveTo(x(t.epochDay), y(t.trend)) else path.lineTo(x(t.epochDay), y(t.trend)) }
                    drawPath(path, primary, style = Stroke(3.dp.toPx()))
                }
                Text("Dots: daily weigh-ins · line: trend", style = MaterialTheme.typography.labelSmall)
            }
        }

        SectionCard("Calories, last 14 days") {
            val target = (profile?.targetKcal ?: 0).toFloat()
            val byDay = totals.associateBy { it.epochDay }
            val days = (today - 13..today).toList()
            val maxV = maxOf(target, (totals.maxOfOrNull { it.kcal } ?: 0.0).toFloat(), 1f) * 1.1f
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val w = size.width / days.size
                days.forEachIndexed { i, d ->
                    val v = byDay[d]?.kcal?.toFloat() ?: 0f
                    val h = v / maxV * size.height
                    drawRect(
                        if (target > 0 && v > target * 1.05f) Warn else primary,
                        topLeft = Offset(i * w + w * 0.15f, size.height - h),
                        size = Size(w * 0.7f, h),
                    )
                }
                if (target > 0) {
                    val ty = size.height - target / maxV * size.height
                    drawLine(Color.Gray, Offset(0f, ty), Offset(size.width, ty), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                }
            }
            val logged = totals.filter { it.kcal > 0 }
            if (logged.isNotEmpty()) {
                Text(
                    "Average ${logged.map { it.kcal }.average().roundToInt()} kcal on ${logged.size} logged days · dashed line = target",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard("Cardio minutes per week") {
            val mondays = (7 downTo 0).map { Days.monday(today) - it * 7L }
            val perWeek = mondays.map { m -> cardio.filter { it.kind != "other" && it.epochDay in m until m + 7 }.sumOf { it.durationSec } / 60 }
            val maxV = (perWeek.maxOrNull() ?: 0).coerceAtLeast(60) * 1.1f
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val w = size.width / mondays.size
                perWeek.forEachIndexed { i, v ->
                    val h = v / maxV * size.height
                    drawRect(primary, topLeft = Offset(i * w + w * 0.15f, size.height - h), size = Size(w * 0.7f, h))
                }
            }
            Text("This week: ${perWeek.last()} min · ${fmtDay(mondays.first())} → now", style = MaterialTheme.typography.bodySmall)
            val avgSteps = activity.filter { it.steps > 0 }.map { it.steps }.average().takeIf { !it.isNaN() }
            if (avgSteps != null) Text("Average steps (7 days): ${avgSteps.roundToInt()}", style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Strength levels") {
            val engine = profile?.let { c.plan.engine(it) }
            if (ladders.isEmpty() || engine == null) {
                Text("Finish your first workout to see your levels.", style = MaterialTheme.typography.bodySmall)
            }
            ladders.sortedBy { it.id }.forEach { l ->
                val steps = c.catalogs.exercises.ladder(l.id)
                val ex = steps.getOrNull(l.step) ?: return@forEach
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ex.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "lvl ${l.step + 1}/${steps.size}" + if (l.loadKg > 0) " · ${fmtKg(l.loadKg)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
