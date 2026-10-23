package com.koala.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

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

    /** Ajoute ce qui a été vu ailleurs. Renvoie true si quelque chose a changé. */
    @Synchronized
    fun merge(remote: Map<String, Long>): Boolean {
        var changed = false
        for ((k, at) in remote) if (!m.containsKey(k) && at > 0) {
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
