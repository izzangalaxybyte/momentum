package com.thegalaxybyte.momentum.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DayTotal(val epochDay: Long, val kcal: Double, val protein: Double)

data class FoodUse(val foodId: String, val uses: Int)

@Dao
interface MomentumDao {
    // profile
    @Query("SELECT * FROM profile WHERE id = 'me' AND deleted = 0") fun profile(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 'me' AND deleted = 0") suspend fun profileNow(): ProfileEntity?
    @Upsert suspend fun upsertProfile(p: ProfileEntity)

    // weight
    @Query("SELECT * FROM weight WHERE deleted = 0 ORDER BY epochDay") fun weights(): Flow<List<WeightEntity>>
    @Query("SELECT * FROM weight WHERE deleted = 0 ORDER BY epochDay") suspend fun weightsNow(): List<WeightEntity>
    @Upsert suspend fun upsertWeight(w: WeightEntity)

    // foods
    @Query("SELECT * FROM food WHERE deleted = 0 ORDER BY nameEn") fun customFoods(): Flow<List<FoodEntity>>
    @Upsert suspend fun upsertFood(f: FoodEntity)

    // food log
    @Query("SELECT * FROM food_log WHERE epochDay = :day AND deleted = 0 ORDER BY createdAt")
    fun foodLog(day: Long): Flow<List<FoodLogEntity>>
    @Query("SELECT * FROM food_log WHERE id = :id") suspend fun foodLogEntry(id: String): FoodLogEntity?
    @Upsert suspend fun upsertFoodLog(f: FoodLogEntity)
    @Query("SELECT epochDay, SUM(kcal) AS kcal, SUM(protein) AS protein FROM food_log WHERE deleted = 0 AND epochDay >= :from GROUP BY epochDay ORDER BY epochDay")
    fun dayTotals(from: Long): Flow<List<DayTotal>>
    @Query("SELECT epochDay, SUM(kcal) AS kcal, SUM(protein) AS protein FROM food_log WHERE deleted = 0 AND epochDay >= :from GROUP BY epochDay ORDER BY epochDay")
    suspend fun dayTotalsNow(from: Long): List<DayTotal>
    @Query("SELECT foodId, COUNT(*) AS uses FROM food_log WHERE deleted = 0 AND foodId IS NOT NULL GROUP BY foodId ORDER BY MAX(createdAt) DESC LIMIT 30")
    fun recentFoods(): Flow<List<FoodUse>>
    @Query("SELECT foodId, COUNT(*) AS uses FROM food_log WHERE deleted = 0 AND foodId IS NOT NULL GROUP BY foodId ORDER BY MAX(createdAt) DESC LIMIT 30")
    suspend fun recentFoodsNow(): List<FoodUse>

    // ladders
    @Query("SELECT * FROM ladder_state WHERE deleted = 0") fun ladderStates(): Flow<List<LadderStateEntity>>
    @Query("SELECT * FROM ladder_state WHERE deleted = 0") suspend fun ladderStatesNow(): List<LadderStateEntity>
    @Upsert suspend fun upsertLadderState(s: LadderStateEntity)

    // workouts
    @Query("SELECT * FROM workout_session WHERE deleted = 0 ORDER BY startedAt DESC") fun sessions(): Flow<List<WorkoutSessionEntity>>
    @Query("SELECT * FROM workout_session WHERE deleted = 0 AND endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastFinishedSession(): WorkoutSessionEntity?
    @Upsert suspend fun upsertSession(s: WorkoutSessionEntity)
    @Upsert suspend fun upsertSets(s: List<SetLogEntity>)
    @Query("SELECT * FROM set_log WHERE deleted = 0 AND exerciseId = :exerciseId ORDER BY epochDay DESC, setIndex LIMIT 60")
    fun setsFor(exerciseId: String): Flow<List<SetLogEntity>>

    // cardio and activity
    @Query("SELECT * FROM cardio WHERE deleted = 0 AND epochDay >= :from ORDER BY startMs DESC") fun cardio(from: Long): Flow<List<CardioEntity>>
    @Query("SELECT * FROM cardio WHERE deleted = 0 AND epochDay >= :from ORDER BY startMs DESC") suspend fun cardioNow(from: Long): List<CardioEntity>
    @Query("SELECT * FROM cardio WHERE id = :id") suspend fun cardioEntry(id: String): CardioEntity?
    @Upsert suspend fun upsertCardio(c: CardioEntity)
    @Query("SELECT * FROM daily_activity WHERE deleted = 0 AND epochDay >= :from ORDER BY epochDay") fun activity(from: Long): Flow<List<DailyActivityEntity>>
    @Query("SELECT * FROM daily_activity WHERE deleted = 0 AND epochDay >= :from ORDER BY epochDay") suspend fun activityNow(from: Long): List<DailyActivityEntity>
    @Query("SELECT * FROM daily_activity WHERE id = :id") suspend fun activityEntry(id: String): DailyActivityEntity?
    @Upsert suspend fun upsertActivity(a: DailyActivityEntity)

    // settings
    @Query("SELECT * FROM setting WHERE id = :id AND deleted = 0") suspend fun setting(id: String): SettingEntity?
    @Query("SELECT * FROM setting WHERE id = :id AND deleted = 0") fun settingFlow(id: String): Flow<SettingEntity?>
    @Upsert suspend fun upsertSetting(s: SettingEntity)

    // coach
    @Query("SELECT * FROM coach_review WHERE deleted = 0 ORDER BY weekStart DESC LIMIT 12") fun coachReviews(): Flow<List<CoachReviewEntity>>
    @Query("SELECT * FROM coach_review WHERE id = :id") suspend fun getCoachReview(id: String): CoachReviewEntity?
    @Upsert suspend fun upsertCoachReview(r: CoachReviewEntity)
    @Query("SELECT * FROM coach_message WHERE deleted = 0 ORDER BY createdAt") fun coachMessages(): Flow<List<CoachMessageEntity>>
    @Query("SELECT * FROM coach_message WHERE id = :id") suspend fun getCoachMessage(id: String): CoachMessageEntity?
    @Upsert suspend fun upsertCoachMessage(m: CoachMessageEntity)

    // --- sync: dirty rows, lookup by id, mark pushed ---
    @Query("SELECT * FROM profile WHERE dirty = 1") suspend fun dirtyProfile(): List<ProfileEntity>
    @Query("SELECT * FROM weight WHERE dirty = 1") suspend fun dirtyWeight(): List<WeightEntity>
    @Query("SELECT * FROM food WHERE dirty = 1") suspend fun dirtyFood(): List<FoodEntity>
    @Query("SELECT * FROM food_log WHERE dirty = 1") suspend fun dirtyFoodLog(): List<FoodLogEntity>
    @Query("SELECT * FROM ladder_state WHERE dirty = 1") suspend fun dirtyLadder(): List<LadderStateEntity>
    @Query("SELECT * FROM workout_session WHERE dirty = 1") suspend fun dirtySession(): List<WorkoutSessionEntity>
    @Query("SELECT * FROM set_log WHERE dirty = 1") suspend fun dirtySet(): List<SetLogEntity>
    @Query("SELECT * FROM cardio WHERE dirty = 1") suspend fun dirtyCardio(): List<CardioEntity>
    @Query("SELECT * FROM daily_activity WHERE dirty = 1") suspend fun dirtyActivity(): List<DailyActivityEntity>
    @Query("SELECT * FROM setting WHERE dirty = 1") suspend fun dirtySetting(): List<SettingEntity>
    @Query("SELECT * FROM coach_review WHERE dirty = 1") suspend fun dirtyCoachReview(): List<CoachReviewEntity>
    @Query("SELECT * FROM coach_message WHERE dirty = 1") suspend fun dirtyCoachMessage(): List<CoachMessageEntity>

    @Query("SELECT * FROM profile WHERE id = :id") suspend fun getProfile(id: String): ProfileEntity?
    @Query("SELECT * FROM weight WHERE id = :id") suspend fun getWeight(id: String): WeightEntity?
    @Query("SELECT * FROM food WHERE id = :id") suspend fun getFood(id: String): FoodEntity?
    @Query("SELECT * FROM ladder_state WHERE id = :id") suspend fun getLadder(id: String): LadderStateEntity?
    @Query("SELECT * FROM workout_session WHERE id = :id") suspend fun getSession(id: String): WorkoutSessionEntity?
    @Query("SELECT * FROM set_log WHERE id = :id") suspend fun getSet(id: String): SetLogEntity?
    @Upsert suspend fun upsertSet(s: SetLogEntity)

    @Query("UPDATE profile SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanProfile(id: String, u: Long)
    @Query("UPDATE weight SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanWeight(id: String, u: Long)
    @Query("UPDATE food SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanFood(id: String, u: Long)
    @Query("UPDATE food_log SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanFoodLog(id: String, u: Long)
    @Query("UPDATE ladder_state SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanLadder(id: String, u: Long)
    @Query("UPDATE workout_session SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanSession(id: String, u: Long)
    @Query("UPDATE set_log SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanSet(id: String, u: Long)
    @Query("UPDATE cardio SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanCardio(id: String, u: Long)
    @Query("UPDATE daily_activity SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanActivity(id: String, u: Long)
    @Query("UPDATE setting SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanSetting(id: String, u: Long)
    @Query("UPDATE coach_review SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanCoachReview(id: String, u: Long)
    @Query("UPDATE coach_message SET dirty = 0 WHERE id = :id AND updatedAt = :u") suspend fun cleanCoachMessage(id: String, u: Long)

    // export
    @Query("SELECT * FROM food_log WHERE deleted = 0") suspend fun allFoodLog(): List<FoodLogEntity>
    @Query("SELECT * FROM set_log WHERE deleted = 0") suspend fun allSets(): List<SetLogEntity>
    @Query("SELECT * FROM workout_session WHERE deleted = 0") suspend fun allSessions(): List<WorkoutSessionEntity>
    @Query("SELECT * FROM food WHERE deleted = 0") suspend fun allFoods(): List<FoodEntity>
}
