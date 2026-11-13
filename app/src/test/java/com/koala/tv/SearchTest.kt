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

    /** Historique « Déjà vus » : films et séries, du plus récent au plus ancien. */
    @Test
    fun watchedHistory() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<App>()
        Seen.init(app)
        val movies = (1..5).map { Item(Kind.MOVIE, "$it", "Film $it", "", "1", "") }
        Seen.touch("m3", 1000); Seen.touch("m1", 3000); Seen.touch("m5", 2000)
        assertEquals(listOf("Film 1", "Film 5", "Film 3"), Seen.watched(movies, Kind.MOVIE).map { it.name })
        val series = (1..3).map { Item(Kind.SERIES, "$it", "Série $it", "", "1", "") }
        Seen.finished(PlayItem("S2 E1", "u", false, "e21", "21", seriesId = "2", seriesName = "Série 2"))
        Seen.touch("s3", 500)
        assertEquals(listOf("Série 2", "Série 3"), Seen.watched(series, Kind.SERIES).map { it.name })
        // une date plus ancienne venue d'un autre appareil ne recule pas l'historique
        Seen.merge(mapOf("m1" to 10L))
        assertEquals(3000L, Seen.at("m1"))
    }
}
