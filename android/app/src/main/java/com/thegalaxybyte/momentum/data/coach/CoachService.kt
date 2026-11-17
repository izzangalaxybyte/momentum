package com.thegalaxybyte.momentum.data.coach

import com.thegalaxybyte.momentum.data.PlanService
import com.thegalaxybyte.momentum.data.Repo
import com.thegalaxybyte.momentum.data.db.CoachReviewEntity
import com.thegalaxybyte.momentum.domain.CalorieEngine
import com.thegalaxybyte.momentum.domain.CardioPhase
import com.thegalaxybyte.momentum.domain.CardioPlanner
import com.thegalaxybyte.momentum.domain.CardioState
import com.thegalaxybyte.momentum.domain.Days
import com.thegalaxybyte.momentum.domain.LadderState
import com.thegalaxybyte.momentum.domain.Sex
import kotlinx.serialization.json.Json
import java.util.UUID

/** Coach features on the phone: ask for reviews, chat, parse meals, and apply accepted proposals safely. */
class CoachService(private val client: CoachClient, private val repo: Repo, private val plan: PlanService) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao get() = repo.dao

    fun body(r: CoachReviewEntity): ReviewBody =
        runCatching { json.decodeFromString(ReviewBody.serializer(), r.body) }.getOrDefault(ReviewBody(summary = "(couldn't read this review)"))

    suspend fun requestReview() {
        dao.upsertCoachReview(client.review(Days.today()))
    }

    suspend fun send(text: String) {
        client.chat(UUID.randomUUID().toString(), text, Days.today()).forEach { dao.upsertCoachMessage(it) }
    }

    suspend fun parseMeal(text: String, photoJpegB64: String?): MealResult {
        val custom = repo.dao.allFoods()
        return client.meal(text, photoJpegB64, repo.searchFoods("", custom))
    }

    /** Log confirmed items. [quantities] may differ from what the coach suggested. */
    suspend fun logMeal(day: Long, meal: String, items: List<Pair<MealItem, Double>>) {
        val custom = repo.dao.allFoods()
        for ((item, qty) in items) {
            val food = item.foodId?.let { repo.food(it, custom) }
            if (food != null) {
                repo.logFood(day, meal, food, qty)
            } else {
                val f = if (item.quantity > 0) qty / item.quantity else 1.0
                repo.logEstimate(
                    day, meal, item.name, item.unit, qty,
                    (item.kcal ?: 0.0) * f, (item.protein ?: 0.0) * f, (item.carbs ?: 0.0) * f, (item.fat ?: 0.0) * f,
                )
            }
        }
    }

    /** Accept (apply within the engines' limits) or dismiss one proposal, and record the outcome on the review. */
    suspend fun decide(review: CoachReviewEntity, proposalId: String, accept: Boolean) {
        val body = body(review)
        val p = body.proposals.firstOrNull { it.id == proposalId && it.status == "pending" } ?: return
        val result = if (accept) apply(p) else null
        val updated = body.copy(proposals = body.proposals.map {
            if (it.id == proposalId) it.copy(status = if (accept) "accepted" else "dismissed", result = result) else it
        })
        repo.saveCoachReview(review.copy(body = json.encodeToString(ReviewBody.serializer(), updated)))
    }

    private suspend fun apply(p: Proposal): String {
        val profile = dao.profileNow() ?: return "No profile yet; nothing changed."
        return when (p.kind) {
            "calorie_target" -> {
                val current = profile.targetKcal ?: plan.today()?.targets?.target ?: return "Nothing changed."
                val sex = if (profile.sex == "FEMALE") Sex.FEMALE else Sex.MALE
                val new = CalorieEngine.clampProposal(p.value ?: current, current, sex)
                repo.saveProfile(profile.copy(targetKcal = new, targetSetEpochDay = Days.today()))
                if (new == current) "Calorie target stays at $current kcal (already at the limit)."
                else "Calorie target: $current → $new kcal a day."
            }
            "exercise_easier", "exercise_harder" -> {
                val ladder = p.target ?: return "Nothing changed."
                val engine = plan.engine(profile)
                val stored = dao.ladderStatesNow().firstOrNull { it.id == ladder }
                val state = stored?.let { LadderState(it.id, it.step, it.loadKg, it.stage, it.misses, it.holdSec) }
                    ?: runCatching { engine.initial(ladder) }.getOrNull() ?: return "Unknown exercise; nothing changed."
                val outcome = engine.nudge(state, harder = p.kind == "exercise_harder")
                repo.saveLadder(outcome.state)
                outcome.message
            }
            "cardio_easier", "cardio_harder" -> {
                val state = plan.cardioState() ?: CardioPlanner.initial(Days.monday(Days.today()), 0)
                val new = if (p.kind == "cardio_harder") CardioPlanner.harder(state) else CardioPlanner.easier(state)
                plan.saveCardioState(new)
                "Cardio plan: " + describe(new)
            }
            else -> "Not something the app can change; nothing changed."
        }
    }

    private fun describe(s: CardioState): String = when (s.phase) {
        CardioPhase.WALK -> "about ${s.baseWeeklyMin} min of walking a week."
        CardioPhase.INTERVALS -> "walk/jog ${CardioPlanner.INTERVALS[s.intervalLevel].label()}."
        CardioPhase.RUN -> "about ${s.baseWeeklyMin} min of running a week."
    }
}
