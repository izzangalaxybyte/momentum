package com.thegalaxybyte.momentum.domain

/** Where a ladder (e.g. "push") currently is. Load is per dumbbell, or the bar's total, 0 = bodyweight. */
data class LadderState(
    val ladder: String,
    val step: Int,
    val loadKg: Double,
    /** 0 = 8–12 reps, 1 = 8–15 reps, 2 = 8–12 reps with slow tempo (used once the load is maxed). */
    val stage: Int,
    val misses: Int,
    val holdSec: Int,
)

data class Target(
    val exercise: ExerciseDef,
    val sets: Int,
    val repMin: Int,
    val repMax: Int,
    val holdSec: Int,
    val loadKg: Double,
    val tempo: Boolean,
)

data class SetResult(val reps: Int = 0, val seconds: Int = 0)

enum class OutcomeKind { HEAVIER, MORE_REPS, SLOWER_TEMPO, LONGER_HOLD, NEXT_EXERCISE, SAME, EASIER }

data class Outcome(val state: LadderState, val kind: OutcomeKind, val message: String)

/**
 * Double progression that keeps going past the heaviest weight you own:
 * heavier load → more reps (to 15) → slow tempo → the next, harder variation.
 * Two missed sessions in a row step back one notch.
 */
class ProgressionEngine(
    private val catalog: ExerciseCatalog,
    loads: Map<Equipment, List<Double>>,
) {
    private val loads: Map<Equipment, List<Double>> = loads.mapValues { (_, v) -> v.filter { it > 0 }.distinct().sorted() }

    companion object {
        const val REP_MIN = 8
        const val REP_MAX = 12
        const val REP_MAX_EXTENDED = 15
        const val HOLD_START = 20
        const val HOLD_MAX = 60
        const val HOLD_STEP = 5
        const val HOLD_MIN = 15
    }

    fun exercise(state: LadderState): ExerciseDef {
        val steps = catalog.ladder(state.ladder)
        return steps[state.step.coerceIn(0, steps.lastIndex)]
    }

    fun startLoad(ex: ExerciseDef): Double = when {
        ex.equipment == Equipment.NONE || ex.type != ExerciseType.WEIGHT_REPS -> 0.0
        ex.bodyweightStart -> 0.0
        else -> loads[ex.equipment]?.firstOrNull() ?: 0.0
    }

    fun initial(ladder: String): LadderState {
        val ex = catalog.ladder(ladder).first()
        return LadderState(ladder, 0, startLoad(ex), 0, 0, HOLD_START)
    }

    fun target(state: LadderState, weekIndex: Int): Target {
        val ex = exercise(state)
        return Target(
            exercise = ex,
            sets = if (weekIndex < 2) 2 else 3,
            repMin = REP_MIN,
            repMax = if (state.stage == 1) REP_MAX_EXTENDED else REP_MAX,
            holdSec = state.holdSec,
            loadKg = state.loadKg,
            tempo = state.stage == 2,
        )
    }

    private fun nextLoad(ex: ExerciseDef, current: Double): Double? =
        loads[ex.equipment]?.firstOrNull { it > current + 1e-9 }

    private fun prevLoad(ex: ExerciseDef, current: Double): Double? {
        val lower = loads[ex.equipment]?.lastOrNull { it < current - 1e-9 }
        return lower ?: if (ex.bodyweightStart && current > 0) 0.0 else null
    }

    fun evaluate(state: LadderState, sets: List<SetResult>, rating: Rating, weekIndex: Int): Outcome {
        val t = target(state, weekIndex)
        val ex = t.exercise
        if (sets.isEmpty()) return Outcome(state, OutcomeKind.SAME, "No sets logged; same target next time.")

        if (ex.type == ExerciseType.TIME) {
            val success = sets.size >= t.sets && sets.all { it.seconds >= t.holdSec } && rating != Rating.HARD
            val miss = sets.any { it.seconds < t.holdSec * 3 / 4 }
            return when {
                success && state.holdSec < HOLD_MAX -> {
                    val hold = state.holdSec + HOLD_STEP
                    Outcome(state.copy(holdSec = hold, misses = 0), OutcomeKind.LONGER_HOLD, "${ex.name}: hold ${hold}s next time.")
                }
                success -> advance(state, ex)
                miss -> missed(state, ex)
                else -> Outcome(state.copy(misses = 0), OutcomeKind.SAME, "${ex.name}: same target next time.")
            }
        }

        val success = sets.size >= t.sets && sets.all { it.reps >= t.repMax } && rating != Rating.HARD
        val miss = sets.any { it.reps < t.repMin }
        if (miss) return missed(state, ex)
        if (!success) return Outcome(state.copy(misses = 0), OutcomeKind.SAME, "${ex.name}: same target, aim for ${t.repMax} reps on every set.")

        if (ex.type == ExerciseType.WEIGHT_REPS) {
            val next = nextLoad(ex, state.loadKg)
            if (next != null) {
                return Outcome(
                    state.copy(loadKg = next, stage = 0, misses = 0),
                    OutcomeKind.HEAVIER,
                    "${ex.name}: go up to ${fmtKg(next)} next time.",
                )
            }
        }
        return when (state.stage) {
            0 -> Outcome(state.copy(stage = 1, misses = 0), OutcomeKind.MORE_REPS, "${ex.name}: aim for up to $REP_MAX_EXTENDED reps next time.")
            1 -> Outcome(state.copy(stage = 2, misses = 0), OutcomeKind.SLOWER_TEMPO, "${ex.name}: slow it down next time (3 s down, 1 s pause, 3 s up).")
            else -> advance(state, ex)
        }
    }

    private fun advance(state: LadderState, ex: ExerciseDef): Outcome {
        val steps = catalog.ladder(state.ladder)
        if (state.step >= steps.lastIndex) {
            return Outcome(state.copy(misses = 0), OutcomeKind.SAME, "${ex.name}: you've reached the top of this ladder. Keep it up!")
        }
        val nextEx = steps[state.step + 1]
        return Outcome(
            LadderState(state.ladder, state.step + 1, startLoad(nextEx), 0, 0, HOLD_START),
            OutcomeKind.NEXT_EXERCISE,
            "Level up: ${nextEx.name} replaces ${ex.name}.",
        )
    }

    private fun missed(state: LadderState, ex: ExerciseDef): Outcome {
        val misses = state.misses + 1
        if (misses < 2) {
            return Outcome(state.copy(misses = misses), OutcomeKind.SAME, "${ex.name}: same target next time.")
        }
        if (ex.type == ExerciseType.TIME) {
            val hold = (state.holdSec - HOLD_STEP).coerceAtLeast(HOLD_MIN)
            return Outcome(state.copy(holdSec = hold, misses = 0), OutcomeKind.EASIER, "${ex.name}: hold ${hold}s next time.")
        }
        if (state.stage > 0) {
            return Outcome(state.copy(stage = state.stage - 1, misses = 0), OutcomeKind.EASIER, "${ex.name}: back to an easier rep target.")
        }
        if (ex.type == ExerciseType.WEIGHT_REPS) {
            prevLoad(ex, state.loadKg)?.let { lower ->
                return Outcome(state.copy(loadKg = lower, misses = 0), OutcomeKind.EASIER, "${ex.name}: drop to ${fmtKg(lower)} next time.")
            }
        }
        if (state.step > 0) {
            val prevEx = catalog.ladder(state.ladder)[state.step - 1]
            val load = if (prevEx.type == ExerciseType.WEIGHT_REPS) loads[prevEx.equipment]?.lastOrNull() ?: 0.0 else 0.0
            return Outcome(
                LadderState(state.ladder, state.step - 1, load, 0, 0, HOLD_START),
                OutcomeKind.EASIER,
                "Back to ${prevEx.name} for now.",
            )
        }
        return Outcome(state.copy(misses = 0), OutcomeKind.SAME, "${ex.name}: same target next time.")
    }
}

fun fmtKg(kg: Double): String = when {
    kg <= 0.0 -> "bodyweight"
    kg % 1.0 == 0.0 -> "${kg.toInt()} kg"
    else -> "$kg kg"
}
