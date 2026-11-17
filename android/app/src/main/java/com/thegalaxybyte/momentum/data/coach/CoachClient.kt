package com.thegalaxybyte.momentum.data.coach

import com.thegalaxybyte.momentum.data.FoodDef
import com.thegalaxybyte.momentum.data.Prefs
import com.thegalaxybyte.momentum.data.TokenStore
import com.thegalaxybyte.momentum.data.db.CoachMessageEntity
import com.thegalaxybyte.momentum.data.db.CoachReviewEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A weekly review's content (stored as JSON in [CoachReviewEntity.body]). */
@Serializable
data class ReviewBody(
    val summary: String = "",
    val wins: List<String> = emptyList(),
    val concerns: List<String> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val tip: String = "",
    val period: String = "",
    val model: String = "",
)

/** A suggested plan change. Applied (through the domain engines' limits) only if the user accepts. */
@Serializable
data class Proposal(
    val id: String,
    val kind: String,
    val target: String? = null,
    val value: Int? = null,
    val reason: String = "",
    /** pending, accepted or dismissed */
    val status: String = "pending",
    /** What was actually changed, after clamping. */
    val result: String? = null,
)

/** One food the coach recognised. Macros are null for foods from the app's list (computed on the phone). */
@Serializable
data class MealItem(
    val foodId: String? = null,
    val name: String,
    val unit: String,
    val quantity: Double,
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    /** A brand or restaurant item (not mapped onto a generic list food); worth saving to "my foods". */
    val branded: Boolean = false,
    /** Where the numbers were found online, e.g. "carrefour.pk". */
    val source: String? = null,
)

@Serializable
data class MealResult(val items: List<MealItem> = emptyList(), val note: String = "")

/** The answer to "how many calories / which is better?". [best] indexes [options]. */
@Serializable
data class AskResult(val options: List<MealItem> = emptyList(), val best: Int? = null, val answer: String = "")

@Serializable
private data class CatalogFood(val id: String, val name: String, val unit: String, val kcal: Double, val protein: Double)

@Serializable
private data class Photo(val mediaType: String, val data: String)

@Serializable
private data class MealRequest(val text: String, val image: Photo?, val foods: List<CatalogFood>)

@Serializable
private data class AskRequest(val question: String, val image: Photo?, val foods: List<CatalogFood>, val kcalLeft: Double?, val proteinLeft: Double?)

@Serializable
private data class ChatRequest(val id: String, val text: String, val today: Long)

@Serializable
private data class ReviewRequest(val today: Long)

@Serializable
private data class ReviewResponse(val review: CoachReviewEntity)

@Serializable
private data class ChatResponse(val messages: List<CoachMessageEntity>)

class CoachException(message: String) : Exception(message)

/** Talks to the coach endpoints of the Momentum API (the OpenRouter key stays in AWS). */
class CoachClient(private val prefs: Prefs, private val tokens: TokenStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    suspend fun meal(text: String, photoJpegB64: String?, foods: List<FoodDef>): MealResult {
        val body = MealRequest(
            text = text.trim(),
            image = photoJpegB64?.let { Photo("image/jpeg", it) },
            foods = catalog(foods),
        )
        return json.decodeFromString(MealResult.serializer(), post("/coach/meal", json.encodeToString(MealRequest.serializer(), body)))
    }

    suspend fun ask(question: String, photoJpegB64: String?, foods: List<FoodDef>, kcalLeft: Double?, proteinLeft: Double?): AskResult {
        val body = AskRequest(question.trim(), photoJpegB64?.let { Photo("image/jpeg", it) }, catalog(foods), kcalLeft, proteinLeft)
        return json.decodeFromString(AskResult.serializer(), post("/coach/ask", json.encodeToString(AskRequest.serializer(), body)))
    }

    private fun catalog(foods: List<FoodDef>) = foods.map {
        CatalogFood(it.id, it.nameEn + if (it.nameUrdu.isNotBlank()) " / ${it.nameUrdu}" else "", it.unit, it.kcal, it.protein)
    }

    suspend fun review(today: Long): CoachReviewEntity {
        val res = post("/coach/review", json.encodeToString(ReviewRequest.serializer(), ReviewRequest(today)))
        return json.decodeFromString(ReviewResponse.serializer(), res).review
    }

    suspend fun chat(id: String, text: String, today: Long): List<CoachMessageEntity> {
        val res = post("/coach/chat", json.encodeToString(ChatRequest.serializer(), ChatRequest(id, text, today)))
        return json.decodeFromString(ChatResponse.serializer(), res).messages
    }

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val base = prefs.apiUrlNow()
        val token = tokens.get()
        if (base.isBlank() || token.isNullOrBlank()) throw CoachException("Set up Cloud sync in Settings to use the coach.")
        val req = Request.Builder().url(base + path).post(body.toRequestBody(jsonType))
            .header("Authorization", "Bearer $token").build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return@withContext text
                if (resp.code == 401) throw CoachException("Sync token rejected; check Settings → Cloud sync.")
                val msg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw CoachException(msg ?: "The coach is unavailable right now (error ${resp.code}).")
            }
        } catch (e: IOException) {
            throw CoachException("Couldn't reach the coach. Check your internet connection.")
        }
    }
}
