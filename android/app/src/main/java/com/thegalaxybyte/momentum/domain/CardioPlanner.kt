package com.thegalaxybyte.momentum.domain

import kotlin.math.min
import kotlin.math.roundToInt

enum class CardioPhase { WALK, INTERVALS, RUN }

data class CardioState(
    val phase: CardioPhase,
    /** Weekly minutes the plan is built from (walk or run minutes). */
    val baseWeeklyMin: Int,
    val intervalLevel: Int,
    /** Monday (epoch day) of the week this state applies to. */
    val weekStart: Long,
)

data class IntervalSpec(val reps: Int, val jogMin: Double, val walkMin: Double) {
    val totalMin: Int get() = (reps * (jogMin + walkMin)).roundToInt() + 10 // + 5 min warm-up and cool-down walk
    fun label(): String = "$reps × (${fmt(jogMin)} min jog / ${fmt(walkMin)} min walk)"
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

enum class CardioKind { WALK, INTERVALS, RUN }

data class CardioItem(val kind: CardioKind, val minutes: Int, val interval: IntervalSpec? = null) {
    fun label(): String = when (kind) {
        CardioKind.WALK -> "Brisk walk $minutes min"
        CardioKind.RUN -> "Easy run $minutes min"
        CardioKind.INTERVALS -> "Walk/jog: ${interval!!.label()}"
    }
}

data class CardioWeekPlan(val phase: CardioPhase, val deload: Boolean, val items: List<CardioItem>) {
    val weeklyMin: Int get() = items.sumOf { it.minutes }
}

data class CardioDone(val epochDay: Long, val minutes: Int, val isRun: Boolean, val rating: Rating?)

/**
 * Walk → walk/jog intervals → continuous running.
 * Weekly volume grows ≤ 10%; every 4th week is a lighter (deload) week; long gaps step back.
 */
object CardioPlanner {
    val INTERVALS = listOf(
        IntervalSpec(8, 1.0, 2.0), IntervalSpec(8, 1.5, 2.0), IntervalSpec(6, 2.0, 2.0), IntervalSpec(5, 3.0, 2.0),
        IntervalSpec(4, 5.0, 2.0), IntervalSpec(3, 8.0, 2.0), IntervalSpec(2, 12.0, 2.0),
    )
    const val WALK_SESSIONS = 5
    const val WALK_MAX_SESSION = 45
    const val WALK_MIN_WEEKLY = 100
    const val WALK_MAX_WEEKLY = WALK_SESSIONS * WALK_MAX_SESSION
    const val RUN_START_WEEKLY = 60
    const val RUN_MAX_WEEKLY = 300

    fun isDeload(weekIndex: Int) = weekIndex % 4 == 3

    fun initial(monday: Long, recentWeeklyMin: Int): CardioState =
        CardioState(CardioPhase.WALK, recentWeeklyMin.coerceIn(WALK_MIN_WEEKLY, 180), 0, monday)

    fun plan(state: CardioState, weekIndex: Int): CardioWeekPlan {
        val deload = isDeload(weekIndex)
        val f = if (deload) 0.75 else 1.0
        val items = when (state.phase) {
            CardioPhase.WALK -> {
                val per = min(WALK_MAX_SESSION, (state.baseWeeklyMin * f / WALK_SESSIONS).roundToInt()).coerceAtLeast(15)
                List(WALK_SESSIONS) { CardioItem(CardioKind.WALK, per) }
            }
            CardioPhase.INTERVALS -> {
                val level = if (deload) (state.intervalLevel - 1).coerceAtLeast(0) else state.intervalLevel
                val spec = INTERVALS[level.coerceIn(0, INTERVALS.lastIndex)]
                List(3) { CardioItem(CardioKind.INTERVALS, spec.totalMin, spec) } +
                    List(2) { CardioItem(CardioKind.WALK, (40 * f).roundToInt()) }
            }
            CardioPhase.RUN -> {
                val per = (state.baseWeeklyMin * f / 3).roundToInt().coerceAtLeast(15)
                List(3) { CardioItem(CardioKind.RUN, per) } + List(1) { CardioItem(CardioKind.WALK, 40) }
            }
        }
        return CardioWeekPlan(state.phase, deload, items)
    }

    /** A coach proposal the user accepted: one step easier (−10% volume, or one interval level back). */
    fun easier(s: CardioState): CardioState = when (s.phase) {
        CardioPhase.WALK -> s.copy(baseWeeklyMin = (s.baseWeeklyMin * 0.9).roundToInt().coerceAtLeast(WALK_MIN_WEEKLY))
        CardioPhase.INTERVALS -> if (s.intervalLevel > 0) s.copy(intervalLevel = s.intervalLevel - 1)
        else s.copy(phase = CardioPhase.WALK, baseWeeklyMin = WALK_MAX_WEEKLY)
        CardioPhase.RUN -> s.copy(baseWeeklyMin = (s.baseWeeklyMin * 0.9).roundToInt().coerceAtLeast(RUN_START_WEEKLY))
    }

    /** One step harder, never more than the usual weekly growth (+10%, or one interval level). */
    fun harder(s: CardioState): CardioState = when (s.phase) {
        CardioPhase.WALK -> s.copy(baseWeeklyMin = min(WALK_MAX_WEEKLY, (s.baseWeeklyMin * 1.1).roundToInt()))
        CardioPhase.INTERVALS -> if (s.intervalLevel < INTERVALS.lastIndex) s.copy(intervalLevel = s.intervalLevel + 1) else s
        CardioPhase.RUN -> s.copy(baseWeeklyMin = min(RUN_MAX_WEEKLY, (s.baseWeeklyMin * 1.1).roundToInt()))
    }

    /** Move [state] from the finished week ([lastWeekIndex]) to the week starting [newMonday]. */
    fun advanceWeek(
        state: CardioState,
        lastWeek: List<CardioDone>,
        lastWeekIndex: Int,
        daysSinceLastSession: Int?,
        newMonday: Long,
    ): CardioState {
        val s = state.copy(weekStart = newMonday)
        val gap = daysSinceLastSession ?: Int.MAX_VALUE
        if (gap >= 14) {
            return when (s.phase) {
                CardioPhase.WALK -> s.copy(baseWeeklyMin = (s.baseWeeklyMin * 0.7).roundToInt().coerceAtLeast(WALK_MIN_WEEKLY))
                CardioPhase.INTERVALS -> if (s.intervalLevel >= 2) s.copy(intervalLevel = s.intervalLevel - 2)
                else s.copy(phase = CardioPhase.WALK, baseWeeklyMin = 180)
                CardioPhase.RUN -> s.copy(baseWeeklyMin = (s.baseWeeklyMin * 0.7).roundToInt().coerceAtLeast(RUN_START_WEEKLY))
            }
        }
        if (gap >= 7 || isDeload(lastWeekIndex)) return s
        val planned = plan(state, lastWeekIndex)
        val anyHard = lastWeek.any { it.rating == Rating.HARD }
        return when (s.phase) {
            CardioPhase.WALK -> {
                val actual = lastWeek.sumOf { it.minutes }
                if (actual < planned.weeklyMin * 0.9 || anyHard) return s
                val base = min(WALK_MAX_WEEKLY, (s.baseWeeklyMin * 1.1).roundToInt())
                val longWalks = lastWeek.count { it.minutes >= 40 }
                if (s.baseWeeklyMin >= WALK_MAX_WEEKLY && longWalks >= 3) {
                    s.copy(phase = CardioPhase.INTERVALS, intervalLevel = 0)
                } else s.copy(baseWeeklyMin = base)
            }
            CardioPhase.INTERVALS -> {
                val done = lastWeek.count { it.minutes >= 20 }
                if (done < 2 || anyHard) return s
                if (s.intervalLevel >= INTERVALS.lastIndex) {
                    s.copy(phase = CardioPhase.RUN, baseWeeklyMin = RUN_START_WEEKLY)
                } else s.copy(intervalLevel = s.intervalLevel + 1)
            }
            CardioPhase.RUN -> {
                val runMin = lastWeek.filter { it.isRun }.sumOf { it.minutes }
                if (runMin < s.baseWeeklyMin * 0.9 || anyHard) return s
                s.copy(baseWeeklyMin = min(RUN_MAX_WEEKLY, (s.baseWeeklyMin * 1.1).roundToInt()))
            }
        }
    }
}
