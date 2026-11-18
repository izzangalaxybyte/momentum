package com.thegalaxybyte.momentum.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A strength workout: a name and the movements (ladders) it trains, in order. Each movement keeps
 * one progression, so a movement used in two workouts moves forward from either of them.
 */
@Serializable
data class Workout(val key: String, val name: String, val ladders: List<String>)

/** The user's workouts, done in rotation on strength days. Stored as JSON in the synced "workouts" setting. */
object Workouts {
    const val SETTING = "workouts"
    const val MAX_WORKOUTS = 7
    const val MAX_MOVES = 10
    private const val MAX_NAME = 30

    /** Short names for the movement ladders in data/exercises.json. */
    val MOVEMENT_NAMES = linkedMapOf(
        "squat" to "Squat",
        "lunge" to "Lunge",
        "hinge" to "Hip hinge (bridge / RDL)",
        "push" to "Push-up",
        "press" to "Dumbbell press",
        "row" to "Row",
        "core-a" to "Core: planks",
        "core-b" to "Core: dead bug, superman, climbers",
    )

    fun movementName(ladder: String): String = MOVEMENT_NAMES[ladder] ?: ladder.replaceFirstChar { it.uppercase() }

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Workout.serializer())

    /** The built-in plan: Strength A and B from the exercise catalog. */
    fun defaults(catalog: ExerciseCatalog): List<Workout> =
        catalog.sessions.map { (key, ladders) ->
            Workout(key, "Strength " + key.substringAfterLast('-').uppercase(), ladders)
        }

    /** Drops unknown/duplicate movements and empty workouts; falls back to [defaults] if nothing usable is left. */
    fun clean(list: List<Workout>, catalog: ExerciseCatalog): List<Workout> {
        val known = catalog.ladders.toSet()
        val seen = HashSet<String>()
        val out = list.mapNotNull { w ->
            val ladders = w.ladders.filter { it in known }.distinct().take(MAX_MOVES)
            if (ladders.isEmpty() || w.key.isBlank() || !seen.add(w.key)) null
            else w.copy(name = w.name.trim().take(MAX_NAME).ifEmpty { "Workout" }, ladders = ladders)
        }.take(MAX_WORKOUTS)
        return out.ifEmpty { defaults(catalog) }
    }

    fun decode(value: String?, catalog: ExerciseCatalog): List<Workout> =
        clean(value?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty(), catalog)

    fun encode(list: List<Workout>): String = json.encodeToString(serializer, list)

    /** The workout after the last one finished, in list order (the first one if there's no history or it was deleted). */
    fun next(list: List<Workout>, lastKey: String?): Workout {
        val i = list.indexOfFirst { it.key == lastKey }
        return if (i < 0) list.first() else list[(i + 1) % list.size]
    }

    fun newKey(): String = "w-" + java.util.UUID.randomUUID().toString().take(8)
}
