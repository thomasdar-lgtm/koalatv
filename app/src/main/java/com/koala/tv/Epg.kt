package com.koala.tv

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guide TV des chaînes affichées à l'écran (liste des chaînes et grille).
 * Chargé à la demande, 4 requêtes à la fois, les lignes visibles en premier ;
 * gardé jusqu'à la fin du programme en cours.
 */
object Epg {
    private class Slot(val list: List<Program>, val validUntil: Long)

    private const val LIMIT = 24
    private val cache = HashMap<String, Slot>()
    private val wanted = LinkedHashSet<String>()
    private val running = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    /** Appelé quand le guide d'une chaîne est arrivé. */
    var onLoaded: ((String) -> Unit)? = null

    fun nowSec() = System.currentTimeMillis() / 1000

    /** Programmes à venir (le premier peut être en cours) ; null = en cours de chargement. */
    fun programs(id: String): List<Program>? {
        val s = cache[id]
        val t = nowSec()
        if (s != null && t < s.validUntil) return s.list.filter { it.end > t }
        if (id !in running) {
            wanted.add(id)
            pump()
        }
        return s?.list?.filter { it.end > t }
    }

    /** Programme en cours si connu ; sinon lance le chargement et renvoie null. */
    fun now(id: String): Program? {
        val t = nowSec()
        return programs(id)?.firstOrNull { it.start <= t && it.end > t }
    }

    /** La ligne n'est plus à l'écran : inutile de charger son guide. */
    fun drop(id: String) {
        wanted.remove(id)
    }

    fun clear() {
        cache.clear(); wanted.clear()
    }

    private fun pump() {
        while (running.size < 4 && wanted.isNotEmpty()) {
            val id = wanted.last()
            wanted.remove(id)
            running.add(id)
            Lists.scope.launch {
                val list = Xtream.shortEpg(id, LIMIT)
                val t = nowSec()
                // pas de guide : on ne redemande pas avant 30 min
                val until = list.firstOrNull { it.end > t }?.end ?: (t + 1800)
                main.post {
                    cache[id] = Slot(list, until)
                    running.remove(id)
                    onLoaded?.invoke(id)
                    pump()
                }
            }
        }
    }

    fun hm(sec: Long): String = hm.format(Date(sec * 1000))

    fun line(p: Program): String = "${hm(p.start)} – ${hm(p.end)} · ${p.title}"
}
