package com.koala.tv

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Programme en cours des chaînes affichées à l'écran.
 * Chargé à la demande (seulement les lignes visibles), 4 requêtes à la fois, gardé jusqu'à la fin du programme.
 */
object Epg {
    private class Slot(val now: Program?, val validUntil: Long)

    private val cache = HashMap<String, Slot>()
    private val wanted = LinkedHashSet<String>()
    private val running = HashSet<String>()
    private val gate = Semaphore(4)
    private val main = Handler(Looper.getMainLooper())
    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    /** Appelé quand le programme d'une chaîne est arrivé. */
    var onLoaded: ((String) -> Unit)? = null

    private fun nowSec() = System.currentTimeMillis() / 1000

    /** Programme en cours si connu ; sinon lance le chargement et renvoie null. */
    fun now(id: String): Program? {
        val s = cache[id]
        val t = nowSec()
        if (s != null && t < s.validUntil) return s.now?.takeIf { it.end > t }
        if (id !in running) {
            wanted.add(id)
            pump()
        }
        return null
    }

    /** La ligne n'est plus à l'écran : inutile de charger son programme. */
    fun drop(id: String) {
        wanted.remove(id)
    }

    fun clear() {
        cache.clear(); wanted.clear()
    }

    private fun pump() {
        while (running.size < 4 && wanted.isNotEmpty()) {
            // les dernières lignes demandées (celles à l'écran) passent en premier
            val id = wanted.last()
            wanted.remove(id)
            running.add(id)
            Lists.scope.launch {
                val list = gate.withPermit { Xtream.shortEpg(id, 2) }
                val t = nowSec()
                val cur = list.firstOrNull { it.start <= t && it.end > t }
                // pas de guide : on ne redemande pas avant 30 min
                val until = cur?.end ?: list.firstOrNull()?.start ?: (t + 1800)
                main.post {
                    cache[id] = Slot(cur, until)
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
