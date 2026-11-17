package com.thegalaxybyte.momentum.data.sync

import com.thegalaxybyte.momentum.data.Prefs
import com.thegalaxybyte.momentum.data.TokenStore
import com.thegalaxybyte.momentum.data.db.CardioEntity
import com.thegalaxybyte.momentum.data.db.CoachMessageEntity
import com.thegalaxybyte.momentum.data.db.CoachReviewEntity
import com.thegalaxybyte.momentum.data.db.DailyActivityEntity
import com.thegalaxybyte.momentum.data.db.FoodEntity
import com.thegalaxybyte.momentum.data.db.FoodLogEntity
import com.thegalaxybyte.momentum.data.db.LadderStateEntity
import com.thegalaxybyte.momentum.data.db.MomentumDao
import com.thegalaxybyte.momentum.data.db.ProfileEntity
import com.thegalaxybyte.momentum.data.db.SetLogEntity
import com.thegalaxybyte.momentum.data.db.SettingEntity
import com.thegalaxybyte.momentum.data.db.Syncable
import com.thegalaxybyte.momentum.data.db.WeightEntity
import com.thegalaxybyte.momentum.data.db.WorkoutSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class WireChange(val type: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JsonObject)

@Serializable
private data class PushBody(val changes: List<WireChange>)

@Serializable
private data class PushResult(val applied: List<String>, val stale: List<String>)

@Serializable
private data class PullResult(val changes: List<WireChange>, val cursor: String, val hasMore: Boolean)

class SyncException(message: String) : Exception(message)

/** One synced table: how to find dirty rows, look one up, save a pulled one, and mark one pushed. */
private class Table<T : Syncable>(
    val type: String,
    val serializer: KSerializer<T>,
    val dirty: suspend () -> List<T>,
    val get: suspend (String) -> T?,
    val save: suspend (T) -> Unit,
    val clean: suspend (String, Long) -> Unit,
)

/**
 * Offline-first sync with the Momentum API: push dirty rows (last write wins on the server), then pull
 * everything changed since the stored cursor and keep whichever side has the newer updatedAt.
 */
class SyncEngine(private val dao: MomentumDao, private val prefs: Prefs, private val tokens: TokenStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val jsonType = "application/json".toMediaType()

    private val tables: List<Table<out Syncable>> = listOf(
        Table("Profile", ProfileEntity.serializer(), dao::dirtyProfile, dao::getProfile, { dao.upsertProfile(it) }, dao::cleanProfile),
        Table("WeightEntry", WeightEntity.serializer(), dao::dirtyWeight, dao::getWeight, { dao.upsertWeight(it) }, dao::cleanWeight),
        Table("Food", FoodEntity.serializer(), dao::dirtyFood, dao::getFood, { dao.upsertFood(it) }, dao::cleanFood),
        Table("FoodLog", FoodLogEntity.serializer(), dao::dirtyFoodLog, dao::foodLogEntry, { dao.upsertFoodLog(it) }, dao::cleanFoodLog),
        Table("ExerciseState", LadderStateEntity.serializer(), dao::dirtyLadder, dao::getLadder, { dao.upsertLadderState(it) }, dao::cleanLadder),
        Table("WorkoutSession", WorkoutSessionEntity.serializer(), dao::dirtySession, dao::getSession, { dao.upsertSession(it) }, dao::cleanSession),
        Table("SetLog", SetLogEntity.serializer(), dao::dirtySet, dao::getSet, { dao.upsertSet(it) }, dao::cleanSet),
        Table("CardioSession", CardioEntity.serializer(), dao::dirtyCardio, dao::cardioEntry, { dao.upsertCardio(it) }, dao::cleanCardio),
        Table("DailyActivity", DailyActivityEntity.serializer(), dao::dirtyActivity, dao::activityEntry, { dao.upsertActivity(it) }, dao::cleanActivity),
        Table("Setting", SettingEntity.serializer(), dao::dirtySetting, dao::setting, { dao.upsertSetting(it) }, dao::cleanSetting),
        Table("CoachReview", CoachReviewEntity.serializer(), dao::dirtyCoachReview, dao::getCoachReview, { dao.upsertCoachReview(it) }, dao::cleanCoachReview),
        Table("CoachMessage", CoachMessageEntity.serializer(), dao::dirtyCoachMessage, dao::getCoachMessage, { dao.upsertCoachMessage(it) }, dao::cleanCoachMessage),
    )
    private val byType = tables.associateBy { it.type }

    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val base = prefs.apiUrlNow()
            val token = tokens.get()
            if (base.isBlank() || token.isNullOrBlank()) {
                return@withLock Result.failure(SyncException("Sync not set up"))
            }
            val result = runCatching {
                tables.forEach { push(base, token, it) }
                pull(base, token)
            }
            prefs.setSyncResult(System.currentTimeMillis(), result.exceptionOrNull()?.message ?: "")
            result
        }
    }

    private suspend fun <T : Syncable> push(base: String, token: String, table: Table<T>) {
        val rows = table.dirty()
        for (chunk in rows.chunked(200)) {
            val changes = chunk.map {
                WireChange(table.type, it.id, it.updatedAt, it.deleted,
                    json.encodeToJsonElement(table.serializer, it).jsonObject)
            }
            val body = json.encodeToString(PushBody.serializer(), PushBody(changes)).toRequestBody(jsonType)
            val res = call(Request.Builder().url("$base/sync/push").post(body), token)
            val parsed = json.decodeFromString(PushResult.serializer(), res)
            val done = (parsed.applied + parsed.stale).toSet()
            chunk.filter { it.id in done }.forEach { table.clean(it.id, it.updatedAt) }
        }
    }

    private suspend fun pull(base: String, token: String) {
        var cursor = prefs.cursor()
        do {
            val url = "$base/sync/pull?limit=500" + if (cursor.isNotEmpty()) "&since=$cursor" else ""
            val res = json.decodeFromString(PullResult.serializer(), call(Request.Builder().url(url).get(), token))
            res.changes.forEach { apply(it) }
            cursor = res.cursor
            prefs.setCursor(cursor)
        } while (res.hasMore)
    }

    private suspend fun apply(change: WireChange) {
        @Suppress("UNCHECKED_CAST")
        val table = byType[change.type] as Table<Syncable>? ?: return
        val incoming = runCatching { json.decodeFromJsonElement(table.serializer, change.data) }.getOrNull() ?: return
        val local = table.get(change.id)
        if (local != null && local.updatedAt >= change.updatedAt) return
        table.save(markClean(incoming))
    }

    private fun markClean(row: Syncable): Syncable = when (row) {
        is ProfileEntity -> row.copy(dirty = false)
        is WeightEntity -> row.copy(dirty = false)
        is FoodEntity -> row.copy(dirty = false)
        is FoodLogEntity -> row.copy(dirty = false)
        is LadderStateEntity -> row.copy(dirty = false)
        is WorkoutSessionEntity -> row.copy(dirty = false)
        is SetLogEntity -> row.copy(dirty = false)
        is CardioEntity -> row.copy(dirty = false)
        is DailyActivityEntity -> row.copy(dirty = false)
        is SettingEntity -> row.copy(dirty = false)
        is CoachReviewEntity -> row.copy(dirty = false)
        is CoachMessageEntity -> row.copy(dirty = false)
        else -> row
    }

    private fun call(builder: Request.Builder, token: String): String {
        val req = builder.header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> throw SyncException("Token rejected; check Settings → Sync")
                !resp.isSuccessful -> throw SyncException("Server error ${resp.code}")
            }
            return body
        }
    }

    /** Quick reachability + token check for the Settings screen. */
    suspend fun test(base: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                call(Request.Builder().url("${base.trimEnd('/')}/sync/pull?limit=1&since=999999999999999").get(), token)
            } catch (e: IOException) {
                throw SyncException("Can't reach the server: ${e.message}")
            }
            Unit
        }
    }
}
