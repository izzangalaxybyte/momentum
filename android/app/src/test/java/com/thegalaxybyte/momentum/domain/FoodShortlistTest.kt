package com.thegalaxybyte.momentum.domain

import com.thegalaxybyte.momentum.data.FoodDef
import com.thegalaxybyte.momentum.data.coach.FoodShortlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoodShortlistTest {
    @Serializable
    private data class FoodFile(val foods: List<FoodDef>)

    private val all = Json { ignoreUnknownKeys = true }
        .decodeFromString<FoodFile>(File("../../data/foods_pk.json").readText()).foods
    private fun ids(text: String, photo: Boolean = false) = FoodShortlist.pick(text, all, emptyList(), photo).map { it.id }

    @Test fun matchesWhatWasTyped() {
        val got = ids("do roti, aadha bowl qeema aur ek cup chai")
        assertTrue("roti" in got && "qeema" in got && "chai" in got)
        assertTrue(got.size <= FoodShortlist.MAX_MATCHES + FoodShortlist.STAPLES.size)
    }

    @Test fun findsBrandsAndAliases() {
        assertTrue("kn-nuggets" in ids("kns nuggets 6"))
        assertTrue("dawn-crispy-fillet-burger" in ids("dawn crispy fillet burger with mayo"))
        assertTrue("kfc-zinger" in ids("zinger from KFC and a coke"))
        assertTrue("coca-cola-can" in ids("zinger from KFC and a coke"))
        assertTrue("savour-pulao-kabab" in ids("savour pulao"))
    }

    @Test fun keepsTheListSmall() {
        assertTrue(all.size > 700)
        assertTrue(ids("biryani or daal chawal, which is better tonight?").size < 120)
    }

    @Test fun photoWithoutTextSendsEverything() {
        assertEquals(all.size, ids("", photo = true).size)
        assertTrue(ids("", photo = false).size < 60)
    }
}
