package com.koala.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlinx.coroutines.launch

/** Films et épisodes vus jusqu'au bout, partagés entre appareils (même format que l'appli LG). */
object Seen {
    private lateinit var sp: SharedPreferences
    private val m = HashMap<String, Long>()

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("koalatv_seen", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(sp.getString("m", "{}") ?: "{}")
            for (k in o.keys()) m[k] = o.optLong(k)
        } catch (e: Exception) {
            // illisible : rien de vu
        }
    }

    @Synchronized
    fun has(key: String) = m.containsKey(key)

    @Synchronized
    fun all(): Map<String, Long> = HashMap(m)

    @Synchronized
    fun mark(key: String) {
        if (m.containsKey(key)) return
        val at = System.currentTimeMillis()
        m[key] = at
        save()
        Sync.pushSeen(key, at)
    }

    /** Fin d'un film ou d'un épisode ; une série garde la date de son dernier épisode fini (« s » + identifiant). */
    fun finished(item: PlayItem) {
        val key = item.resumeKey ?: return
        mark(key)
        if (item.seriesId.isNotEmpty()) touch("s" + item.seriesId)
    }

    private val beforeDone = HashSet<String>()

    /** Un épisode regardé : tous ceux qui le précèdent dans la série comptent comme vus (une fois par épisode). */
    fun before(item: PlayItem) {
        val key = item.resumeKey ?: return
        if (item.seriesId.isEmpty() || !synchronized(beforeDone) { beforeDone.add(key) }) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val eps = try { Xtream.seriesEpisodes(item.seriesId) } catch (e: Exception) { return@launch }
            val at = System.currentTimeMillis() - 1000
            val added = ArrayList<String>()
            synchronized(this@Seen) {
                for (e in eps) {
                    val k = "e" + e.id
                    if (k == key) break
                    if (!m.containsKey(k)) { m[k] = at; added.add(k) }
                }
                if (added.isNotEmpty()) save()
            }
            for (k in added) Sync.pushSeen(k, at)
        }
    }

    @Synchronized
    fun touch(key: String, at: Long = System.currentTimeMillis()) {
        if ((m[key] ?: 0) >= at) return
        m[key] = at
        save()
        Sync.pushSeen(key, at)
    }

    @Synchronized
    fun at(key: String): Long = m[key] ?: 0

    /** Historique : films ou séries vus, du plus récent au plus ancien. */
    @Synchronized
    fun watched(items: List<Item>, kind: Kind): List<Item> {
        val pre = if (kind == Kind.SERIES) "s" else "m"
        return items.mapNotNull { i -> m[pre + i.id]?.let { i to it } }.sortedByDescending { it.second }.map { it.first }
    }

    /** Séries vues avant l'historique : retrouvées dans « Reprendre » (épisodes finis). */
    fun backfill() {
        for (e in History.all()) if (e.done && e.seriesId.isNotEmpty() && !has("s" + e.seriesId)) touch("s" + e.seriesId, e.at)
    }

    /** Ajoute ce qui a été vu ailleurs (date la plus récente). Renvoie true si quelque chose a changé. */
    @Synchronized
    fun merge(remote: Map<String, Long>): Boolean {
        var changed = false
        for ((k, at) in remote) if (at > (m[k] ?: 0)) {
            m[k] = at; changed = true
        }
        if (changed) save()
        return changed
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        sp.edit().putString("m", o.toString()).apply()
    }
}
