package com.thegalaxybyte.momentum.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class BodyStats(
    val sex: Sex,
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val goalWeightKg: Double,
)

data class IntakeDay(val epochDay: Long, val kcal: Double)

data class CalorieTargets(
    val maintenance: Int,
    val target: Int,
    val proteinG: Int,
    val deficit: Int,
    val floor: Int,
    val adaptive: Boolean,
)

/**
 * Daily calorie and protein targets.
 *  - BMR: Mifflin-St Jeor; maintenance = BMR × activity factor (from average daily steps).
 *  - Deficit 750 kcal at BMI ≥ 30, else 500, capped so loss stays ≤ 1% bodyweight/week.
 *  - Never below 1500 (men) / 1200 (women) kcal.
 *  - After enough logging, maintenance is blended with the observed value (intake vs weight trend).
 *  - The target moves at most ±150 kcal per weekly update.
 */
object CalorieEngine {
    const val KCAL_PER_KG = 7700.0
    const val MAX_WEEKLY_CHANGE = 150
    const val MIN_LOGGED_DAYS = 10
    const val WINDOW_DAYS = 28

    fun bmr(s: BodyStats): Double =
        10 * s.weightKg + 6.25 * s.heightCm - 5 * s.age + if (s.sex == Sex.MALE) 5 else -161

    fun activityFactor(avgSteps: Int?): Double = when {
        avgSteps == null || avgSteps < 5000 -> 1.2
        avgSteps < 7500 -> 1.375
        avgSteps < 10000 -> 1.45
        else -> 1.55
    }

    fun floor(sex: Sex): Int = if (sex == Sex.MALE) 1500 else 1200

    fun deficit(s: BodyStats): Int {
        val bmi = s.weightKg / ((s.heightCm / 100) * (s.heightCm / 100))
        val base = if (bmi >= 30) 750 else 500
        val cap = (s.weightKg * 0.01 * KCAL_PER_KG / 7).roundToInt()
        return min(base, cap)
    }

    /** Observed maintenance from the last [WINDOW_DAYS] days before [today], or null if there isn't enough data. */
    fun observedMaintenance(intake: List<IntakeDay>, trend: List<TrendPoint>, today: Long): Pair<Double, Int>? {
        val start = today - WINDOW_DAYS
        val logged = intake.filter { it.epochDay in start until today && it.kcal > 0 }
        if (logged.size < MIN_LOGGED_DAYS) return null
        val window = trend.filter { it.epochDay in start until today }
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        val days = (last.epochDay - first.epochDay).toInt()
        if (days < 14) return null
        val avgIntake = logged.map { it.kcal }.average()
        return (avgIntake - (last.trend - first.trend) * KCAL_PER_KG / days) to logged.size
    }

    fun targets(
        s: BodyStats,
        avgSteps: Int?,
        observed: Pair<Double, Int>? = null,
        previousTarget: Int? = null,
    ): CalorieTargets {
        val formula = bmr(s) * activityFactor(avgSteps)
        val maintenance = if (observed != null) {
            val (obs, loggedDays) = observed
            val w = 0.5 + 0.3 * min(1.0, (loggedDays - MIN_LOGGED_DAYS) / 18.0)
            obs * w + formula * (1 - w)
        } else formula
        val deficit = deficit(s)
        var target = maintenance - deficit
        if (previousTarget != null) {
            target = target.coerceIn(
                (previousTarget - MAX_WEEKLY_CHANGE).toDouble(),
                (previousTarget + MAX_WEEKLY_CHANGE).toDouble(),
            )
        }
        val floor = floor(s.sex)
        val rounded = max(floor, (target / 10).roundToInt() * 10)
        return CalorieTargets(
            maintenance = maintenance.roundToInt(),
            target = rounded,
            proteinG = (1.6 * s.goalWeightKg).roundToInt(),
            deficit = deficit,
            floor = floor,
            adaptive = observed != null,
        )
    }
}
