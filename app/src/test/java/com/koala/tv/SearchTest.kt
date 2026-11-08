package com.koala.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Recherche d'une lettre : la série « H » doit sortir en premier parmi des milliers de titres. */
@RunWith(RobolectricTestRunner::class)
class SearchTest {
    @Test
    fun titleCore() {
        val cases = mapOf(
            "|fr-4k dv| game of thrones" to "game of thrones", "fr - h (1998)" to "h", "|fr| h" to "h",
            "multi | h [vostfr]" to "h", "alien: romulus" to "alien: romulus", "hd | homeland" to "homeland",
        )
        for ((a, b) in cases) assertEquals(a, b, titleCore(a))
    }

    @Test
    fun singleLetterFindsH() {
        val names = (1..3000).map { "|FR| Hawaii $it" } + listOf("|FR| House of the Dragon", "FR - Hannibal", "|FR-4K DV| H (1998)", "|FR| The Hunt")
        val pool = names.mapIndexed { i, n -> Item(Kind.SERIES, "$i", n, "", "1", "") }
        val res = searchSort(pool, "h")
        println("premiers résultats : " + res.take(4).map { it.name })
        assertEquals("|FR-4K DV| H (1998)", res[0].name)
    }
}
