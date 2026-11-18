package com.thegalaxybyte.momentum.data

import com.thegalaxybyte.momentum.data.db.CardioEntity
import com.thegalaxybyte.momentum.data.db.MomentumDao
import com.thegalaxybyte.momentum.data.db.ProfileEntity
import com.thegalaxybyte.momentum.domain.BodyStats
import com.thegalaxybyte.momentum.domain.CalorieEngine
import com.thegalaxybyte.momentum.domain.CalorieTargets
import com.thegalaxybyte.momentum.domain.CardioDone
import com.thegalaxybyte.momentum.domain.CardioPhase
import com.thegalaxybyte.momentum.domain.CardioPlanner
import com.thegalaxybyte.momentum.domain.CardioState
import com.thegalaxybyte.momentum.domain.CardioWeekPlan
import com.thegalaxybyte.momentum.domain.DayPlan
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.domain.Equipment
import com.thegalaxybyte.momentum.domain.IntakeDay
import com.thegalaxybyte.momentum.domain.LadderState
import com.thegalaxybyte.momentum.domain.ProgressionEngine
import com.thegalaxybyte.momentum.domain.Rating
import com.thegalaxybyte.momentum.domain.Sex
import com.thegalaxybyte.momentum.domain.TrendPoint
import com.thegalaxybyte.momentum.domain.WeekPlanner
import com.thegalaxybyte.momentum.domain.WeightPoint
import com.thegalaxybyte.momentum.domain.WeightTrend
import com.thegalaxybyte.momentum.domain.Workout
import com.thegalaxybyte.momentum.domain.Workouts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

data class TodayState(
    val day: Long,
    val profile: ProfileEntity,
    val targets: CalorieTargets,
    val eatenKcal: Double,
    val eatenProtein: Double,
    val trend: List<TrendPoint>,
    val weighedToday: Boolean,
    val stepsToday: Long,
    val weekIndex: Int,
    val week: List<DayPlan>,
    val cardioPlan: CardioWeekPlan,
    val cardioState: CardioState,
    val nextSessionKey: String,
    val workouts: List<Workout>,
    val strengthDoneToday: Boolean,
    val cardioToday: List<CardioEntity>,
) {
    val todayPlan: DayPlan get() = week.first { it.epochDay == day }
    val nextWorkoutName: String get() = workouts.firstOrNull { it.key == nextSessionKey }?.name ?: "Strength workout"
}

@Serializable
private data class CardioStateJson(val phase: String, val baseWeeklyMin: Int, val intervalLevel: Int, val weekStart: Long)

/** Turns the stored history into today's plan, and runs the weekly re-calibrations. */
class PlanService(private val dao: MomentumDao, private val catalogs: Catalogs, private val repo: Repo) {
    private val json = Json { ignoreUnknownKeys = true }

    fun loads(p: ProfileEntity): Map<Equipment, List<Double>> = mapOf(
        Equipment.DUMBBELL to parseLoads(p.dumbbellLoads),
        Equipment.BAR to parseLoads(p.barLoads),
    )

    fun engine(p: ProfileEntity) = ProgressionEngine(catalogs.exercises, loads(p))

    fun weekIndex(p: ProfileEntity, day: Long = Days.today()): Int =
        ((Days.monday(day) - Days.monday(p.startEpochDay)) / 7).toInt().coerceAtLeast(0)

    private fun stats(p: ProfileEntity, weightKg: Double) = BodyStats(
        sex = if (p.sex == "FEMALE") Sex.FEMALE else Sex.MALE,
        age = (LocalDate.now().year - p.birthYear).coerceIn(14, 100),
        heightCm = p.heightCm,
        weightKg = weightKg,
        goalWeightKg = p.goalWeightKg,
    )

    private suspend fun trend(): List<TrendPoint> =
        WeightTrend.compute(dao.weightsNow().map { WeightPoint(it.epochDay, it.kg) })

    private suspend fun avgSteps(today: Long): Int? {
        val days = dao.activityNow(today - 7).filter { it.epochDay < today && it.steps > 0 }
        return if (days.size >= 3) days.map { it.steps }.average().toInt() else null
    }

    private suspend fun computeTargets(p: ProfileEntity, today: Long, previous: Int?): CalorieTargets {
        val tr = trend()
        val weight = tr.lastOrNull()?.trend ?: p.startWeightKg
        val intake = dao.dayTotalsNow(today - CalorieEngine.WINDOW_DAYS).map { IntakeDay(it.epochDay, it.kcal) }
        val observed = CalorieEngine.observedMaintenance(intake, tr, today)
        return CalorieEngine.targets(stats(p, weight), avgSteps(today), observed, previous)
    }

    suspend fun cardioState(): CardioState? = dao.setting("cardio")?.let {
        runCatching {
            val s = json.decodeFromString(CardioStateJson.serializer(), it.value)
            CardioState(CardioPhase.valueOf(s.phase), s.baseWeeklyMin, s.intervalLevel, s.weekStart)
        }.getOrNull()
    }

    suspend fun saveCardioState(s: CardioState) = repo.putSetting(
        "cardio",
        json.encodeToString(CardioStateJson.serializer(), CardioStateJson(s.phase.name, s.baseWeeklyMin, s.intervalLevel, s.weekStart)),
    )

    private fun CardioEntity.done() = CardioDone(epochDay, durationSec / 60, kind == "run", rating?.let { Rating.valueOf(it) })

    /** Weekly calorie re-calibration and cardio progression. Safe to call often. */
    suspend fun ensureWeeklyUpdates() {
        val p = dao.profileNow() ?: return
        val today = Days.today()
        val thisMonday = Days.monday(today)

        if (p.targetKcal == null || p.targetSetEpochDay == null || today - p.targetSetEpochDay >= 7) {
            val t = computeTargets(p, today, p.targetKcal)
            repo.saveProfile(p.copy(targetKcal = t.target, targetSetEpochDay = today))
        }

        var state = cardioState()
        if (state == null) {
            val recent = dao.cardioNow(today - 7).filter { it.kind != "other" }.sumOf { it.durationSec / 60 }
            saveCardioState(CardioPlanner.initial(thisMonday, recent))
            return
        }
        var changed = false
        while (state!!.weekStart < thisMonday) {
            val start = state.weekStart
            val end = start + 7
            val all = dao.cardioNow(start - 60).filter { it.kind != "other" }
            val lastWeek = all.filter { it.epochDay in start until end }.map { it.done() }
            val lastDay = all.filter { it.epochDay < end }.maxOfOrNull { it.epochDay }
            val gap = lastDay?.let { (end - it).toInt() }
            state = CardioPlanner.advanceWeek(state, lastWeek, weekIndex(p, start), gap, end)
            changed = true
        }
        if (changed) saveCardioState(state!!)
    }

    suspend fun today(): TodayState? {
        val p = dao.profileNow() ?: return null
        val day = Days.today()
        val tr = trend()
        val computed = computeTargets(p, day, null)
        val targets = computed.copy(target = p.targetKcal ?: computed.target)
        val eaten = dao.dayTotalsNow(day).firstOrNull { it.epochDay == day }
        val weekIndex = weekIndex(p, day)
        val cardioState = cardioState() ?: CardioPlanner.initial(Days.monday(day), 0)
        val cardioPlan = CardioPlanner.plan(cardioState, weekIndex)
        val days = p.trainingDays.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val week = WeekPlanner.plan(Days.monday(day), days, weekIndex, cardioPlan)
        val last = dao.lastFinishedSession()
        val workouts = workouts()
        val next = Workouts.next(workouts, last?.sessionKey).key
        return TodayState(
            day = day,
            profile = p,
            targets = targets,
            eatenKcal = eaten?.kcal ?: 0.0,
            eatenProtein = eaten?.protein ?: 0.0,
            trend = tr,
            weighedToday = dao.weightsNow().any { it.epochDay == day },
            stepsToday = dao.activityEntry(day.toString())?.steps ?: 0,
            weekIndex = weekIndex,
            week = week,
            cardioPlan = cardioPlan,
            cardioState = cardioState,
            nextSessionKey = if (last?.epochDay == day && last.endedAt != null) last.sessionKey else next,
            workouts = workouts,
            strengthDoneToday = last?.epochDay == day && last.endedAt != null,
            cardioToday = dao.cardioNow(day).filter { it.epochDay == day && it.kind != "other" },
        )
    }

    /** Your strength workouts, in rotation order (Strength A and B until you change them). */
    suspend fun workouts(): List<Workout> = Workouts.decode(dao.setting(Workouts.SETTING)?.value, catalogs.exercises)

    fun defaultWorkouts(): List<Workout> = Workouts.defaults(catalogs.exercises)

    suspend fun saveWorkouts(list: List<Workout>) =
        repo.putSetting(Workouts.SETTING, Workouts.encode(Workouts.clean(list, catalogs.exercises)))

    /** The exercise you're currently on in every movement (ladder → exercise name). */
    suspend fun currentExercises(p: ProfileEntity): Map<String, String> {
        val engine = engine(p)
        val stored = dao.ladderStatesNow().associateBy { it.id }
        return catalogs.exercises.ladders.associateWith { ladder ->
            val st = stored[ladder]?.let { LadderState(it.id, it.step, it.loadKg, it.stage, it.misses, it.holdSec) }
                ?: engine.initial(ladder)
            engine.exercise(st).name
        }
    }

    /** Current state of each ladder used by [sessionKey], creating starting states as needed. */
    suspend fun ladderStates(p: ProfileEntity, sessionKey: String): List<LadderState> {
        val engine = engine(p)
        val stored = dao.ladderStatesNow().associateBy { it.id }
        val ladders = workouts().firstOrNull { it.key == sessionKey }?.ladders
            ?: catalogs.exercises.sessions[sessionKey].orEmpty()
        return ladders.map { ladder ->
            stored[ladder]?.let { LadderState(it.id, it.step, it.loadKg, it.stage, it.misses, it.holdSec) }
                ?: engine.initial(ladder)
        }
    }

    companion object {
        fun parseLoads(s: String): List<Double> =
            s.split(',', ' ').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }.distinct().sorted()
    }
}
