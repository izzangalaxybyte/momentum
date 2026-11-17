package com.thegalaxybyte.momentum.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.thegalaxybyte.momentum.data.db.CardioEntity
import com.thegalaxybyte.momentum.data.db.DailyActivityEntity
import com.thegalaxybyte.momentum.data.db.FoodLogEntity
import com.thegalaxybyte.momentum.data.db.MomentumDao
import com.thegalaxybyte.momentum.data.db.WeightEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * Health Connect bridge. Samsung Health (and the Galaxy Watch) write walks, runs, steps, heart rate and
 * weight into Health Connect; we read the recent window and upsert it into Room (deduped by record id).
 * Food log entries and manual weigh-ins are written back so Samsung Health shows them too.
 */
class HealthSource(private val context: Context, private val dao: MomentumDao) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val readPermissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )
    val writePermissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class),
    )
    val allPermissions = readPermissions + writePermissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun status(): Int = HealthConnectClient.getSdkStatus(context)
    fun available(): Boolean = status() == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient? = if (available()) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Set<String> = client()?.permissionController?.getGrantedPermissions().orEmpty()

    suspend fun canRead(): Boolean = granted().containsAll(readPermissions)

    private fun epochDay(i: Instant) = i.atZone(zone).toLocalDate().toEpochDay()

    /** Import the last [days] days. Returns how many rows changed. */
    suspend fun importRecent(days: Long = 14): Int {
        val c = client() ?: return 0
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.containsAll(readPermissions)) return 0
        val now = Instant.now()
        val startDate = LocalDate.now(zone).minusDays(days - 1)
        val start = startDate.atStartOfDay(zone).toInstant()
        var changed = 0
        val stamp = System.currentTimeMillis()

        // walks and runs
        var page: String? = null
        do {
            val resp = c.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, now), pageToken = page),
            )
            for (r in resp.records) {
                val kind = when (r.exerciseType) {
                    ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walk"
                    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
                    else -> "other"
                }
                val range = TimeRangeFilter.between(r.startTime, r.endTime)
                val agg = runCatching {
                    c.aggregate(AggregateRequest(
                        setOf(DistanceRecord.DISTANCE_TOTAL, HeartRateRecord.BPM_AVG, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        range,
                    ))
                }.getOrNull()
                val id = "hc-" + r.metadata.id
                val existing = dao.cardioEntry(id)
                val row = CardioEntity(
                    id = id,
                    epochDay = epochDay(r.startTime),
                    kind = kind,
                    startMs = r.startTime.toEpochMilli(),
                    durationSec = ((r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 1000).toInt(),
                    distanceM = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                    avgHr = agg?.get(HeartRateRecord.BPM_AVG),
                    kcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
                    source = r.metadata.dataOrigin.packageName,
                    rating = existing?.rating,
                    updatedAt = stamp,
                )
                if (existing == null || existing.copy(updatedAt = 0, dirty = false) != row.copy(updatedAt = 0, dirty = false)) {
                    dao.upsertCardio(row)
                    changed++
                }
            }
            page = resp.pageToken
        } while (!page.isNullOrEmpty())

        // steps and active calories per day
        val groups = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startDate.atStartOfDay(), LocalDateTime.now(zone)),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        for (g in groups) {
            val day = g.startTime.toLocalDate().toEpochDay()
            val steps = g.result[StepsRecord.COUNT_TOTAL] ?: 0L
            val kcal = g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
            val existing = dao.activityEntry(day.toString())
            if (existing == null && steps == 0L && kcal < 1) continue // no data that day
            if (existing == null || existing.steps != steps || kotlin.math.abs(existing.activeKcal - kcal) > 1) {
                dao.upsertActivity(DailyActivityEntity(day.toString(), day, steps, kcal, updatedAt = stamp))
                changed++
            }
        }

        // weight from scales / Samsung Health (skip what this app wrote itself)
        page = null
        do {
            val resp = c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, now), pageToken = page))
            for (r in resp.records) {
                if (r.metadata.dataOrigin.packageName == context.packageName) continue
                val id = "hc-" + r.metadata.id
                if (dao.getWeight(id) == null) {
                    dao.upsertWeight(WeightEntity(id, epochDay(r.time), r.weight.inKilograms, "health_connect", updatedAt = stamp))
                    changed++
                }
            }
            page = resp.pageToken
        } while (!page.isNullOrEmpty())
        return changed
    }

    private suspend fun canWrite(perm: String): HealthConnectClient? {
        val c = client() ?: return null
        return if (perm in c.permissionController.getGrantedPermissions()) c else null
    }

    suspend fun writeWeight(entry: WeightEntity) = runCatching {
        val c = canWrite(HealthPermission.getWritePermission(WeightRecord::class)) ?: return@runCatching
        val time = LocalDate.ofEpochDay(entry.epochDay).atTime(8, 0).atZone(zone)
        c.insertRecords(listOf(WeightRecord(
            time = time.toInstant(),
            zoneOffset = time.offset,
            weight = Mass.kilograms(entry.kg),
            metadata = Metadata.manualEntry(clientRecordId = entry.id, clientRecordVersion = entry.updatedAt),
        )))
    }

    suspend fun writeFood(entry: FoodLogEntity) = runCatching {
        val c = canWrite(HealthPermission.getWritePermission(NutritionRecord::class)) ?: return@runCatching
        if (entry.deleted) {
            c.deleteRecords(NutritionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = listOf(entry.id))
            return@runCatching
        }
        val at = Instant.ofEpochMilli(entry.createdAt)
        val start = if (epochDay(at) == entry.epochDay) at
        else LocalDate.ofEpochDay(entry.epochDay).atTime(13, 0).atZone(zone).toInstant()
        val offset = start.atZone(zone).offset
        c.insertRecords(listOf(NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = start.plusSeconds(60),
            endZoneOffset = offset,
            name = entry.name,
            energy = Energy.kilocalories(entry.kcal),
            protein = Mass.grams(entry.protein),
            totalCarbohydrate = Mass.grams(entry.carbs),
            totalFat = Mass.grams(entry.fat),
            mealType = when (entry.meal) {
                "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
                "lunch" -> MealType.MEAL_TYPE_LUNCH
                "dinner" -> MealType.MEAL_TYPE_DINNER
                else -> MealType.MEAL_TYPE_SNACK
            },
            metadata = Metadata.manualEntry(clientRecordId = entry.id, clientRecordVersion = entry.updatedAt),
        )))
    }
}
