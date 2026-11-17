package com.thegalaxybyte.momentum.data

import com.thegalaxybyte.momentum.data.db.CardioEntity
import com.thegalaxybyte.momentum.data.db.CoachReviewEntity
import com.thegalaxybyte.momentum.data.db.FoodEntity
import com.thegalaxybyte.momentum.data.db.FoodLogEntity
import com.thegalaxybyte.momentum.data.db.LadderStateEntity
import com.thegalaxybyte.momentum.data.db.MomentumDao
import com.thegalaxybyte.momentum.data.db.ProfileEntity
import com.thegalaxybyte.momentum.data.db.SetLogEntity
import com.thegalaxybyte.momentum.data.db.SettingEntity
import com.thegalaxybyte.momentum.data.db.WeightEntity
import com.thegalaxybyte.momentum.data.db.WorkoutSessionEntity
import com.thegalaxybyte.momentum.data.health.HealthSource
import com.thegalaxybyte.momentum.domain.LadderState
import com.thegalaxybyte.momentum.domain.Rating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** All writes go through here: stamp updatedAt, mark dirty, mirror to Health Connect, and schedule a sync. */
class Repo(
    val dao: MomentumDao,
    val catalogs: Catalogs,
    private val health: HealthSource,
    private val onChange: () -> Unit,
) {
    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // --- profile ---
    suspend fun saveProfile(p: ProfileEntity) {
        dao.upsertProfile(p.copy(updatedAt = now(), dirty = true))
        onChange()
    }

    // --- weight ---
    suspend fun addWeight(epochDay: Long, kg: Double) {
        val e = WeightEntity(newId(), epochDay, kg, "manual", updatedAt = now())
        dao.upsertWeight(e)
        health.writeWeight(e)
        onChange()
    }

    suspend fun deleteWeight(w: WeightEntity) {
        dao.upsertWeight(w.copy(deleted = true, updatedAt = now(), dirty = true))
        onChange()
    }

    // --- food ---
    fun searchFoods(query: String, custom: List<FoodEntity>): List<FoodDef> {
        val all = custom.map { it.toDef() } + catalogs.foods
        if (query.isBlank()) return all
        return all.filter { it.matches(query) }.sortedBy { it.rank(query) }
    }

    fun food(id: String, custom: List<FoodEntity>): FoodDef? =
        custom.firstOrNull { it.id == id }?.toDef() ?: catalogs.builtInFood(id)

    suspend fun logFood(epochDay: Long, meal: String, food: FoodDef, quantity: Double) {
        val t = now()
        val e = FoodLogEntity(
            id = newId(), epochDay = epochDay, meal = meal, foodId = food.id, name = food.nameEn, unit = food.unit,
            quantity = quantity, kcal = food.kcal * quantity, protein = food.protein * quantity,
            carbs = food.carbs * quantity, fat = food.fat * quantity, createdAt = t, updatedAt = t,
        )
        dao.upsertFoodLog(e)
        health.writeFood(e)
        onChange()
    }

    /** A food the coach estimated that isn't in the food list (no foodId); macros are for the whole quantity. */
    suspend fun logEstimate(
        epochDay: Long, meal: String, name: String, unit: String, quantity: Double,
        kcal: Double, protein: Double, carbs: Double, fat: Double,
    ) {
        val t = now()
        val e = FoodLogEntity(
            id = newId(), epochDay = epochDay, meal = meal, foodId = null, name = name, unit = unit,
            quantity = quantity, kcal = kcal, protein = protein, carbs = carbs, fat = fat, createdAt = t, updatedAt = t,
        )
        dao.upsertFoodLog(e)
        health.writeFood(e)
        onChange()
    }

    suspend fun updateFoodQuantity(entry: FoodLogEntity, quantity: Double) {
        val f = quantity / entry.quantity
        val e = entry.copy(
            quantity = quantity, kcal = entry.kcal * f, protein = entry.protein * f, carbs = entry.carbs * f,
            fat = entry.fat * f, updatedAt = now(), dirty = true,
        )
        dao.upsertFoodLog(e)
        health.writeFood(e)
        onChange()
    }

    suspend fun deleteFoodLog(entry: FoodLogEntity) {
        val e = entry.copy(deleted = true, updatedAt = now(), dirty = true)
        dao.upsertFoodLog(e)
        health.writeFood(e)
        onChange()
    }

    suspend fun addCustomFood(
        name: String, unit: String, grams: Double, kcal: Double, protein: Double, carbs: Double, fat: Double,
    ): FoodEntity {
        val f = FoodEntity(
            id = "custom-" + newId(), nameEn = name.trim(), unit = unit.trim().ifEmpty { "serving" }, grams = grams,
            kcal = kcal, protein = protein, carbs = carbs, fat = fat, updatedAt = now(),
        )
        dao.upsertFood(f)
        onChange()
        return f
    }

    // --- cardio ---
    suspend fun rateCardio(c: CardioEntity, rating: Rating) {
        dao.upsertCardio(c.copy(rating = rating.name, updatedAt = now(), dirty = true))
        onChange()
    }

    suspend fun addManualCardio(epochDay: Long, kind: String, minutes: Int, rating: Rating?) {
        dao.upsertCardio(CardioEntity(
            id = newId(), epochDay = epochDay, kind = kind, startMs = now(), durationSec = minutes * 60,
            distanceM = null, avgHr = null, kcal = null, source = "manual", rating = rating?.name, updatedAt = now(),
        ))
        onChange()
    }

    suspend fun deleteCardio(c: CardioEntity) {
        dao.upsertCardio(c.copy(deleted = true, updatedAt = now(), dirty = true))
        onChange()
    }

    // --- strength ---
    suspend fun saveWorkout(
        epochDay: Long, sessionKey: String, weekIndex: Int, startedAt: Long,
        sets: List<SetLogEntity>, newStates: List<LadderState>,
    ) {
        val t = now()
        val id = newId()
        dao.upsertSession(WorkoutSessionEntity(id, epochDay, sessionKey, weekIndex, startedAt, t, updatedAt = t))
        dao.upsertSets(sets.mapIndexed { i, s -> s.copy(id = newId(), sessionId = id, updatedAt = t + i) })
        newStates.forEach { saveLadder(it, t) }
        onChange()
    }

    suspend fun saveLadder(s: LadderState, t: Long = now()) {
        dao.upsertLadderState(LadderStateEntity(s.ladder, s.step, s.loadKg, s.stage, s.misses, s.holdSec, updatedAt = t))
    }

    // --- coach ---
    suspend fun saveCoachReview(r: CoachReviewEntity) {
        dao.upsertCoachReview(r.copy(updatedAt = maxOf(now(), r.updatedAt + 1), dirty = true))
        onChange()
    }

    // --- settings ---
    suspend fun putSetting(id: String, value: String) {
        dao.upsertSetting(SettingEntity(id, value, updatedAt = now()))
        onChange()
    }

    // --- export ---
    suspend fun exportJson(): String {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val obj = buildJsonObject {
            put("exportedAt", now())
            put("profile", json.encodeToJsonElement(ProfileEntity.serializer(), dao.profileNow() ?: ProfileEntity()))
            put("weights", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(WeightEntity.serializer()), dao.weightsNow()))
            put("foodLog", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(FoodLogEntity.serializer()), dao.allFoodLog()))
            put("customFoods", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(FoodEntity.serializer()), dao.allFoods()))
            put("workouts", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(WorkoutSessionEntity.serializer()), dao.allSessions()))
            put("sets", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(SetLogEntity.serializer()), dao.allSets()))
            put("cardio", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(CardioEntity.serializer()), dao.cardioNow(0)))
            put("ladders", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(LadderStateEntity.serializer()), dao.ladderStatesNow()))
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }
}
