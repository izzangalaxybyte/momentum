package com.thegalaxybyte.momentum.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Every synced row carries: id, updatedAt (client ms, last write wins), deleted (tombstone),
 * dirty (not yet pushed). Rows are serialized whole as the sync payload.
 */
interface Syncable {
    val id: String
    val updatedAt: Long
    val deleted: Boolean
    val dirty: Boolean
}

@Serializable
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey override val id: String = "me",
    val sex: String = "MALE",
    val birthYear: Int = 1995,
    val heightCm: Double = 170.0,
    val startWeightKg: Double = 100.0,
    val goalWeightKg: Double = 80.0,
    /** Days available for strength training, 1 = Monday … 7 = Sunday, comma separated. */
    val trainingDays: String = "1,3,5",
    val dumbbellLoads: String = "2,3,4,5",
    val barLoads: String = "10",
    val startEpochDay: Long = 0,
    val targetKcal: Int? = null,
    val targetSetEpochDay: Long? = null,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

@Serializable
@Entity(tableName = "weight", indices = [Index("epochDay")])
data class WeightEntity(
    @PrimaryKey override val id: String,
    val epochDay: Long,
    val kg: Double,
    val source: String = "manual",
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/** Custom foods only; the built-in desi food list ships with the app (data/foods_pk.json). */
@Serializable
@Entity(tableName = "food")
data class FoodEntity(
    @PrimaryKey override val id: String,
    val nameEn: String,
    val nameUrdu: String = "",
    val aliases: String = "",
    val unit: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val category: String = "custom",
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

@Serializable
@Entity(tableName = "food_log", indices = [Index("epochDay")])
data class FoodLogEntity(
    @PrimaryKey override val id: String,
    val epochDay: Long,
    val meal: String,
    val foodId: String?,
    val name: String,
    val unit: String,
    val quantity: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/** Progress on one exercise ladder ("push", "squat", …). id = ladder name. */
@Serializable
@Entity(tableName = "ladder_state")
data class LadderStateEntity(
    @PrimaryKey override val id: String,
    val step: Int,
    val loadKg: Double,
    val stage: Int,
    val misses: Int,
    val holdSec: Int,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

@Serializable
@Entity(tableName = "workout_session", indices = [Index("epochDay")])
data class WorkoutSessionEntity(
    @PrimaryKey override val id: String,
    val epochDay: Long,
    val sessionKey: String,
    val weekIndex: Int,
    val startedAt: Long,
    val endedAt: Long?,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

@Serializable
@Entity(tableName = "set_log", indices = [Index("sessionId"), Index("exerciseId")])
data class SetLogEntity(
    @PrimaryKey override val id: String,
    val sessionId: String,
    val epochDay: Long,
    val exerciseId: String,
    val ladder: String,
    val setIndex: Int,
    val loadKg: Double,
    val reps: Int,
    val seconds: Int,
    val rating: String?,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/** Walks and runs, mostly imported from Health Connect (id "hc-<record id>"). */
@Serializable
@Entity(tableName = "cardio", indices = [Index("epochDay")])
data class CardioEntity(
    @PrimaryKey override val id: String,
    val epochDay: Long,
    val kind: String,
    val startMs: Long,
    val durationSec: Int,
    val distanceM: Double?,
    val avgHr: Long?,
    val kcal: Double?,
    val source: String,
    val rating: String? = null,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/** Steps and active calories per day. id = epoch day. */
@Serializable
@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey override val id: String,
    val epochDay: Long,
    val steps: Long,
    val activeKcal: Double,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/** Small synced key/value records (e.g. the cardio plan state as JSON). */
@Serializable
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey override val id: String,
    val value: String,
    override val updatedAt: Long,
    override val deleted: Boolean = false,
    override val dirty: Boolean = true,
) : Syncable

/**
 * A weekly coach review, written by the server (Monday mornings, or on request) and synced down.
 * id = the week's Monday (epoch day). [body] is the review as JSON (see data/coach/CoachModels.kt);
 * the phone rewrites it when a proposal is accepted or dismissed.
 */
@Serializable
@Entity(tableName = "coach_review")
data class CoachReviewEntity(
    @PrimaryKey override val id: String,
    val weekStart: Long,
    val createdAt: Long,
    val body: String,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable

/** One chat message, role "user" or "coach". Both sides are stored by the server and synced. */
@Serializable
@Entity(tableName = "coach_message", indices = [Index("createdAt")])
data class CoachMessageEntity(
    @PrimaryKey override val id: String,
    val createdAt: Long,
    val role: String,
    val text: String,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val dirty: Boolean = false,
) : Syncable
