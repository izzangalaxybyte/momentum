package com.thegalaxybyte.momentum.data

import android.content.Context
import com.thegalaxybyte.momentum.data.db.FoodEntity
import com.thegalaxybyte.momentum.domain.ExerciseCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FoodDef(
    val id: String,
    val nameEn: String,
    val nameUrdu: String = "",
    val aliases: List<String> = emptyList(),
    val unit: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val category: String,
    val custom: Boolean = false,
) {
    private val haystack: String by lazy { (listOf(nameEn, nameUrdu) + aliases).joinToString(" ").lowercase() }

    /** Every word of the query must appear somewhere in the names/aliases. */
    fun matches(query: String): Boolean =
        query.lowercase().split(' ').filter { it.isNotBlank() }.all { haystack.contains(it) }

    fun rank(query: String): Int {
        val q = query.lowercase().trim()
        return when {
            nameEn.lowercase().startsWith(q) || nameUrdu.lowercase().startsWith(q) -> 0
            aliases.any { it.lowercase().startsWith(q) } -> 1
            else -> 2
        }
    }
}

@Serializable
private data class FoodFile(val version: Int, val foods: List<FoodDef>)

fun FoodEntity.toDef() = FoodDef(
    id = id, nameEn = nameEn, nameUrdu = nameUrdu,
    aliases = aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    unit = unit, grams = grams, kcal = kcal, protein = protein, carbs = carbs, fat = fat,
    category = category, custom = true,
)

/** Bundled data from the repo's data/ folder (packaged as app assets). */
class Catalogs(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = context.assets

    val exercises: ExerciseCatalog by lazy {
        json.decodeFromString(assets.open("exercises.json").bufferedReader().use { it.readText() })
    }

    val foods: List<FoodDef> by lazy {
        json.decodeFromString<FoodFile>(assets.open("foods_pk.json").bufferedReader().use { it.readText() }).foods
    }
    private val foodsById by lazy { foods.associateBy { it.id } }

    fun builtInFood(id: String): FoodDef? = foodsById[id]

    companion object {
        fun assetUri(path: String) = "file:///android_asset/$path"
    }
}
