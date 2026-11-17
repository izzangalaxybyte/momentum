package com.thegalaxybyte.momentum.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

enum class Sex { MALE, FEMALE }

enum class Rating { EASY, GOOD, HARD }

@Serializable
enum class ExerciseType {
    @SerialName("weight_reps") WEIGHT_REPS,
    @SerialName("reps") REPS,
    @SerialName("time") TIME,
}

@Serializable
enum class Equipment {
    @SerialName("dumbbell") DUMBBELL,
    @SerialName("bar") BAR,
    @SerialName("none") NONE,
}

@Serializable
data class ExerciseDef(
    val id: String,
    val name: String,
    val ladder: String,
    val step: Int,
    val type: ExerciseType,
    val equipment: Equipment,
    val perSide: Boolean,
    val cues: List<String>,
    val mistakes: List<String>,
    val muscles: List<String>,
    val images: List<String>,
    val video: String,
    val bodyweightStart: Boolean = false,
)

@Serializable
data class ExerciseCatalog(
    val version: Int,
    val sessions: Map<String, List<String>>,
    val exercises: List<ExerciseDef>,
) {
    private val byLadder: Map<String, List<ExerciseDef>> by lazy {
        exercises.groupBy { it.ladder }.mapValues { (_, v) -> v.sortedBy { it.step } }
    }
    private val byId: Map<String, ExerciseDef> by lazy { exercises.associateBy { it.id } }

    fun ladder(name: String): List<ExerciseDef> = byLadder[name].orEmpty()
    fun exercise(id: String): ExerciseDef? = byId[id]
    val ladders: List<String> get() = byLadder.keys.toList()
}

/** Dates are handled as epoch days (LocalDate.toEpochDay()). */
object Days {
    fun monday(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).with(DayOfWeek.MONDAY).toEpochDay()
    fun dayOfWeek(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value // 1 = Monday
    fun today(): Long = LocalDate.now().toEpochDay()
}
