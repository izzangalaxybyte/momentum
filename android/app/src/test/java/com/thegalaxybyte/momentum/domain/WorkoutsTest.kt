package com.thegalaxybyte.momentum.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WorkoutsTest {
    private val catalog: ExerciseCatalog = Json { ignoreUnknownKeys = true }
        .decodeFromString(File("../../data/exercises.json").readText())

    @Test fun defaultsAreStrengthAAndB() {
        val d = Workouts.defaults(catalog)
        assertEquals(listOf("Strength A", "Strength B"), d.map { it.name })
        assertEquals(listOf("strength-a", "strength-b"), d.map { it.key })
        assertEquals(d, Workouts.decode(null, catalog))
        assertEquals(d, Workouts.decode("not json", catalog))
    }

    @Test fun rotatesInOrderAndRestartsWhenLastWasDeleted() {
        val list = listOf(Workout("a", "A", listOf("push")), Workout("b", "B", listOf("squat")), Workout("c", "C", listOf("row")))
        assertEquals("a", Workouts.next(list, null).key)
        assertEquals("b", Workouts.next(list, "a").key)
        assertEquals("c", Workouts.next(list, "b").key)
        assertEquals("a", Workouts.next(list, "c").key)
        assertEquals("a", Workouts.next(list, "strength-b").key) // old workout no longer in the list
        assertEquals("a", Workouts.next(list.take(1), "a").key)
    }

    @Test fun cleanDropsBadMovementsAndEmptyWorkouts() {
        val raw = listOf(
            Workout("x", "  Legs  ", listOf("squat", "nope", "squat", "lunge")),
            Workout("y", "Empty", listOf("nope")),
            Workout("x", "Duplicate key", listOf("push")),
            Workout("z", "", listOf("row")),
        )
        val out = Workouts.clean(raw, catalog)
        assertEquals(listOf(Workout("x", "Legs", listOf("squat", "lunge")), Workout("z", "Workout", listOf("row"))), out)
        assertEquals(out, Workouts.decode(Workouts.encode(out), catalog))
        assertEquals(Workouts.defaults(catalog), Workouts.clean(emptyList(), catalog))
    }

    @Test fun everyCatalogMovementHasAName() {
        assertEquals(catalog.ladders.toSet(), Workouts.MOVEMENT_NAMES.keys)
    }
}
