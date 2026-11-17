package com.thegalaxybyte.momentum.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainTest {
    private val catalog: ExerciseCatalog = Json { ignoreUnknownKeys = true }
        .decodeFromString(File("../../data/exercises.json").readText())
    private val loads = mapOf(Equipment.DUMBBELL to listOf(2.0, 3.0, 4.0, 5.0), Equipment.BAR to listOf(10.0))
    private val engine = ProgressionEngine(catalog, loads)

    // --- calories ---
    private val me = BodyStats(Sex.MALE, age = 30, heightCm = 175.0, weightKg = 110.0, goalWeightKg = 80.0)

    @Test fun bmrMatchesMifflinStJeor() {
        assertEquals(10 * 110.0 + 6.25 * 175 - 5 * 30 + 5, CalorieEngine.bmr(me), 0.001)
    }

    @Test fun bigDeficitWhenObeseAndProteinFromGoal() {
        val t = CalorieEngine.targets(me, avgSteps = 4000)
        assertEquals(750, t.deficit)
        assertEquals(128, t.proteinG)
        assertEquals(CalorieEngine.bmr(me) * 1.2 - 750, t.target.toDouble(), 10.0)
    }

    @Test fun targetNeverBelowFloor() {
        val small = BodyStats(Sex.FEMALE, 60, 150.0, 50.0, 45.0)
        assertTrue(CalorieEngine.targets(small, null).target >= 1200)
    }

    @Test fun weeklyChangeIsCapped() {
        val t = CalorieEngine.targets(me, avgSteps = 4000, previousTarget = 1500)
        assertEquals(1650, t.target)
    }

    @Test fun observedMaintenanceNeedsEnoughData() {
        val today = 1000L
        val intake = (today - 20 until today).map { IntakeDay(it, 2000.0) }
        // 1 kg lost over 20 days at 2000 kcal → maintenance ≈ 2000 + 7700/20 = 2385
        val trend = (today - 20 until today).map { TrendPoint(it, 0.0, 100.0 - (it - (today - 20)) / 19.0) }
        val (obs, days) = CalorieEngine.observedMaintenance(intake, trend, today)!!
        assertEquals(20, days)
        assertEquals(2405.0, obs, 5.0)
        assertNull(CalorieEngine.observedMaintenance(intake.take(5), trend, today))
    }

    // --- weight trend ---
    @Test fun trendSmoothsSpikes() {
        val pts = listOf(WeightPoint(1, 100.0), WeightPoint(2, 103.0), WeightPoint(3, 100.0))
        val t = WeightTrend.compute(pts)
        assertEquals(100.0, t[0].trend, 0.001)
        assertTrue(t[1].trend < 100.5)
    }

    // --- progression ---
    @Test fun catalogHasAllSessionLadders() {
        catalog.sessions.values.flatten().forEach { assertTrue(it, catalog.ladder(it).isNotEmpty()) }
    }

    @Test fun firstWeeksUseTwoSets() {
        assertEquals(2, engine.target(engine.initial("push"), 0).sets)
        assertEquals(3, engine.target(engine.initial("push"), 2).sets)
    }

    @Test fun heavierWhenAllRepsHit() {
        val s = LadderState("row", 0, 2.0, 0, 0, 20)
        val o = engine.evaluate(s, List(3) { SetResult(reps = 12) }, Rating.GOOD, 4)
        assertEquals(OutcomeKind.HEAVIER, o.kind)
        assertEquals(3.0, o.state.loadKg, 0.0)
    }

    @Test fun hardRatingDoesNotProgress() {
        val s = LadderState("row", 0, 2.0, 0, 0, 20)
        assertEquals(OutcomeKind.SAME, engine.evaluate(s, List(3) { SetResult(reps = 12) }, Rating.HARD, 4).kind)
    }

    @Test fun maxedLoadGoesRepsThenTempoThenNextExercise() {
        var s = LadderState("row", 0, 5.0, 0, 0, 20)
        var o = engine.evaluate(s, List(3) { SetResult(reps = 12) }, Rating.EASY, 4)
        assertEquals(OutcomeKind.MORE_REPS, o.kind)
        s = o.state
        assertEquals(15, engine.target(s, 4).repMax)
        o = engine.evaluate(s, List(3) { SetResult(reps = 15) }, Rating.EASY, 4)
        assertEquals(OutcomeKind.SLOWER_TEMPO, o.kind)
        s = o.state
        assertTrue(engine.target(s, 4).tempo)
        o = engine.evaluate(s, List(3) { SetResult(reps = 12) }, Rating.EASY, 4)
        assertEquals(OutcomeKind.NEXT_EXERCISE, o.kind)
        assertEquals("one-arm-row", engine.exercise(o.state).id)
        assertEquals(2.0, o.state.loadKg, 0.0)
    }

    @Test fun bodyweightExercisesClimbTheLadder() {
        var s = engine.initial("push")
        assertEquals("wall-pushup", engine.exercise(s).id)
        repeat(3) { s = engine.evaluate(s, List(3) { SetResult(reps = 15) }, Rating.EASY, 4).state }
        assertEquals("incline-pushup", engine.exercise(s).id)
    }

    @Test fun lungesStartWithBodyweight() {
        val s = engine.initial("lunge")
        assertEquals(0.0, s.loadKg, 0.0)
        val o = engine.evaluate(s, List(3) { SetResult(reps = 12) }, Rating.GOOD, 4)
        assertEquals(2.0, o.state.loadKg, 0.0)
    }

    @Test fun twoMissesStepBack() {
        val s = LadderState("row", 0, 4.0, 0, 0, 20)
        val once = engine.evaluate(s, List(3) { SetResult(reps = 5) }, Rating.HARD, 4)
        assertEquals(OutcomeKind.SAME, once.kind)
        val twice = engine.evaluate(once.state, List(3) { SetResult(reps = 5) }, Rating.HARD, 4)
        assertEquals(OutcomeKind.EASIER, twice.kind)
        assertEquals(3.0, twice.state.loadKg, 0.0)
    }

    @Test fun plankHoldGrows() {
        val s = engine.initial("core-a")
        val o = engine.evaluate(s, List(3) { SetResult(seconds = 20) }, Rating.GOOD, 4)
        assertEquals(25, o.state.holdSec)
    }

    // --- cardio ---
    @Test fun walkPhaseGrowsTenPercent() {
        val s = CardioPlanner.initial(0, 100)
        val plan = CardioPlanner.plan(s, 0)
        assertEquals(5, plan.items.size)
        val done = plan.items.mapIndexed { i, it -> CardioDone(i.toLong(), it.minutes, false, Rating.GOOD) }
        val next = CardioPlanner.advanceWeek(s, done, 0, 1, 7)
        assertEquals(110, next.baseWeeklyMin)
    }

    @Test fun noProgressWhenShortOrDeload() {
        val s = CardioPlanner.initial(0, 100)
        assertEquals(100, CardioPlanner.advanceWeek(s, listOf(CardioDone(0, 20, false, null)), 0, 1, 7).baseWeeklyMin)
        val full = List(5) { CardioDone(it.toLong(), 30, false, null) }
        assertEquals(100, CardioPlanner.advanceWeek(s, full, 3, 1, 28).baseWeeklyMin)
    }

    @Test fun longGapStepsBack() {
        val s = CardioPlanner.initial(0, 180)
        assertEquals(126, CardioPlanner.advanceWeek(s, emptyList(), 2, 20, 21).baseWeeklyMin)
    }

    @Test fun walkingGraduatesToIntervalsThenRunning() {
        var s = CardioState(CardioPhase.WALK, CardioPlanner.WALK_MAX_WEEKLY, 0, 0)
        s = CardioPlanner.advanceWeek(s, List(5) { CardioDone(it.toLong(), 45, false, Rating.GOOD) }, 0, 1, 7)
        assertEquals(CardioPhase.INTERVALS, s.phase)
        repeat(CardioPlanner.INTERVALS.size) {
            s = CardioPlanner.advanceWeek(s, List(3) { CardioDone(it.toLong(), 30, true, Rating.GOOD) }, 4, 1, 7)
        }
        assertEquals(CardioPhase.RUN, s.phase)
    }

    // --- week ---
    @Test fun weekHasStrengthCardioAndRest() {
        val cardio = CardioPlanner.plan(CardioPlanner.initial(0, 100), 0)
        val week = WeekPlanner.plan(4L, setOf(1, 3, 5), 0, cardio) // 4 = a Monday (1970-01-05)
        assertEquals(2, week.count { it.strength })
        assertEquals(1, week.count { it.rest })
        assertEquals(5, week.count { it.cardio != null })
        assertNotNull(week.first { it.strength }.epochDay)
    }

    @Test fun strengthDaysAvoidBackToBack() {
        assertEquals(setOf(1, 3, 5), WeekPlanner.strengthDays(setOf(1, 2, 3, 5), 6))
    }
}
