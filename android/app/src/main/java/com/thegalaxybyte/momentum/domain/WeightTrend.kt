package com.thegalaxybyte.momentum.domain

import kotlin.math.pow

data class WeightPoint(val epochDay: Long, val kg: Double)

data class TrendPoint(val epochDay: Long, val kg: Double, val trend: Double)

/**
 * Exponentially smoothed weight trend (α per day), so daily water swings don't move the plan.
 * Several weigh-ins on one day are averaged; gaps decay the old trend as if the days had passed.
 */
object WeightTrend {
    const val ALPHA = 0.1

    fun compute(points: List<WeightPoint>, alpha: Double = ALPHA): List<TrendPoint> {
        val daily = points.groupBy { it.epochDay }.mapValues { (_, v) -> v.map { it.kg }.average() }.toSortedMap()
        val out = ArrayList<TrendPoint>(daily.size)
        var trend: Double? = null
        var lastDay = 0L
        for ((day, kg) in daily) {
            trend = if (trend == null) kg else {
                val gap = (day - lastDay).coerceAtLeast(1)
                val a = 1 - (1 - alpha).pow(gap.toDouble())
                trend + a * (kg - trend)
            }
            lastDay = day
            out += TrendPoint(day, kg, trend)
        }
        return out
    }
}
