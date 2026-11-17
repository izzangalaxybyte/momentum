package com.thegalaxybyte.momentum.data.coach

import com.thegalaxybyte.momentum.data.FoodDef

/**
 * Picks the foods worth sending to the coach for one request, so a 750-item food list doesn't cost
 * ~18k tokens on every call. Text requests get the foods whose names/aliases match the words typed,
 * plus the user's own and recent foods and a few staples; photo-only requests get the whole list.
 */
object FoodShortlist {
    const val MAX_MATCHES = 80

    /** Always sent: everyday foods a description may mention without naming them exactly. */
    val STAPLES = setOf(
        "roti", "naan", "paratha", "rice-plain", "chai", "chai-doodh-patti", "daal-masoor", "daal-chana",
        "chicken-salan", "qeema", "aloo-sabzi", "dahi", "raita", "salad", "egg-boiled", "omelette",
        "milk-full", "sugar", "soft-drink", "chicken-biryani", "bread-white", "banana", "apple",
    )

    /** Words that say nothing about which food it is (amounts, units, filler, in English and Roman Urdu). */
    private val STOP = setOf(
        "and", "aur", "with", "ke", "ki", "ka", "ko", "se", "the", "of", "in", "or", "ya", "for", "me", "mein",
        "one", "two", "three", "four", "half", "ek", "do", "teen", "char", "chaar", "aadha", "adha", "pao",
        "plate", "plates", "bowl", "bowls", "cup", "cups", "glass", "piece", "pieces", "slice", "slices",
        "small", "large", "big", "medium", "little", "some", "how", "many", "much", "calories", "calorie",
        "kcal", "which", "better", "is", "it", "this", "that", "today", "tonight", "now", "had", "ate",
        "khaya", "khaye", "piya", "pee", "eat", "eating", "lunch", "dinner", "breakfast", "snack", "nashta",
        "for", "about", "what", "should", "can", "would", "my", "kya", "kitni", "kitne", "hai", "hain",
    )

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9&']+")).filter { it.length >= 3 && it !in STOP }

    fun pick(text: String, all: List<FoodDef>, recentIds: List<String>, hasPhoto: Boolean): List<FoodDef> {
        val query = words(text)
        if (query.isEmpty() && hasPhoto) return all
        val scored = all.mapNotNull { f ->
            val hay = words((listOf(f.nameEn, f.nameUrdu, f.id.replace('-', ' ')) + f.aliases).joinToString(" "))
            val score = query.count { q -> hay.any { it.startsWith(q) || q.startsWith(it) && it.length >= 4 } }
            if (score > 0) f to score else null
        }.sortedWith(compareByDescending<Pair<FoodDef, Int>> { it.second }.thenBy { it.first.nameEn.length })
            .take(MAX_MATCHES).map { it.first }
        val recent = recentIds.toSet()
        val extra = all.filter { it.custom || it.id in recent || it.id in STAPLES }
        return (scored + extra).distinctBy { it.id }
    }
}
