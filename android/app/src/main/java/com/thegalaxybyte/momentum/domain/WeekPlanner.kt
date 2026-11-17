package com.thegalaxybyte.momentum.domain

data class DayPlan(
    val epochDay: Long,
    val strength: Boolean,
    val cardio: CardioItem?,
    val rest: Boolean,
)

/**
 * Lays out a week: strength on your chosen days (2 a week in the first 4 weeks, then 3, never on
 * back-to-back days where it can be avoided), cardio on the other days first, and one full rest day.
 */
object WeekPlanner {
    fun strengthDays(trainingDays: Set<Int>, weekIndex: Int): Set<Int> {
        val wanted = if (weekIndex < 4) 2 else 3
        val sorted = trainingDays.filter { it in 1..7 }.sorted()
        if (sorted.size <= wanted) return sorted.toSet()
        // Prefer days that aren't next to each other.
        val picked = mutableListOf<Int>()
        for (d in sorted) if (picked.size < wanted && picked.none { kotlin.math.abs(it - d) == 1 }) picked += d
        for (d in sorted) if (picked.size < wanted && d !in picked) picked += d
        return picked.toSet()
    }

    fun plan(monday: Long, trainingDays: Set<Int>, weekIndex: Int, cardio: CardioWeekPlan): List<DayPlan> {
        val strength = strengthDays(trainingDays, weekIndex)
        val rest = (7 downTo 1).firstOrNull { it !in strength } ?: 7
        val nonStrength = (1..7).filter { it !in strength && it != rest }
        val strengthOnly = (1..7).filter { it in strength && it != rest }
        // Interval/run sessions go on non-strength days first; plain walks fill the rest.
        val ordered = cardio.items.sortedBy { if (it.kind == CardioKind.WALK) 1 else 0 }
        val dayOrder = nonStrength + strengthOnly
        val cardioByDay = HashMap<Int, CardioItem>()
        ordered.zip(dayOrder).forEach { (item, day) -> cardioByDay[day] = item }
        return (1..7).map { d ->
            DayPlan(
                epochDay = monday + d - 1,
                strength = d in strength,
                cardio = cardioByDay[d],
                rest = d == rest && d !in strength,
            )
        }
    }
}
