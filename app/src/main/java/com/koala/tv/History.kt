package com.koala.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Films et épisodes commencés, du plus récent au plus ancien (rangée « Reprendre »). */
object History {
    private const val MAX = 20

    data class Entry(
        val key: String,
        val title: String,
        val url: String,
        val icon: String,
        val pos: Long,
        val dur: Long,
        val at: Long,
        /** Série : identifiant, nom et identifiant d'épisode (pour enchaîner la suite). */
        val seriesId: String = "",
        val seriesName: String = "",
    ) {
        val remainingMin: Int get() = (((dur - pos).coerceAtLeast(0)) / 60_000L).toInt()
        val progress: Int get() = if (dur > 0) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
    }

    private lateinit var sp: SharedPreferences
    private var cache: MutableList<Entry>? = null

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("koalatv_history", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<Entry> = ArrayList(load())

    @Synchronized
    fun update(item: PlayItem, pos: Long, dur: Long) {
        val key = item.resumeKey ?: return
        val l = load()
        l.removeAll { it.key == key }
        val e = Entry(key, item.title, item.url, item.icon, pos, dur, System.currentTimeMillis(), item.seriesId, item.seriesName)
        l.add(0, e)
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(l)
        Sync.push(e)
    }

    @Synchronized
    fun remove(key: String) {
        val l = load()
        if (l.removeAll { it.key == key }) save(l)
        val at = System.currentTimeMillis()
        sp.edit().putLong("gone_$key", at).apply()
        Sync.pushGone(key, at)
    }

    /**
     * Fusion avec l'historique partagé : pour chaque élément, la version la plus récente gagne
     * (y compris une suppression faite sur un autre appareil). Renvoie true si quelque chose a changé.
     */
    @Synchronized
    fun merge(remote: List<Sync.Remote>): Boolean {
        val l = load()
        var changed = false
        for (r in remote) {
            val local = l.firstOrNull { it.key == r.key }
            val goneAt = sp.getLong("gone_${r.key}", 0L)
            if (r.at <= (local?.at ?: 0L) || r.at <= goneAt) continue
            l.removeAll { it.key == r.key }
            if (r.entry == null) {
                Prefs.setPos(r.key, 0)
                sp.edit().putLong("gone_${r.key}", r.at).apply()
            } else {
                l.add(r.entry)
                Prefs.setPos(r.key, r.entry.pos)
            }
            changed = true
        }
        if (changed) {
            l.sortByDescending { it.at }
            while (l.size > MAX) l.removeAt(l.size - 1)
            save(l)
        }
        return changed
    }

    @Synchronized
    fun clear() {
        cache = mutableListOf()
        sp.edit().remove("list").apply()
    }

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val out = mutableListOf<Entry>()
        try {
            val a = JSONArray(sp.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        o.optString("k"), o.optString("t"), o.optString("u"), o.optString("i"),
                        o.optLong("p"), o.optLong("d"), o.optLong("a"), o.optString("sid"), o.optString("sn"),
                    ),
                )
            }
        } catch (e: Exception) {
            // historique illisible : on repart de zéro
        }
        cache = out
        return out
    }

    private fun save(l: List<Entry>) {
        val a = JSONArray()
        for (e in l) {
            a.put(
                JSONObject()
                    .put("k", e.key).put("t", e.title).put("u", e.url).put("i", e.icon)
                    .put("p", e.pos).put("d", e.dur).put("a", e.at).put("sid", e.seriesId).put("sn", e.seriesName),
            )
        }
        sp.edit().putString("list", a.toString()).apply()
    }
}
