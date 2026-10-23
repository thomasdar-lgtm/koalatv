package com.koala.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Catégories masquées, partagées entre tous les appareils du même abonnement.
 * Chaque catégorie garde son état et la date du dernier changement : le plus récent gagne.
 */
object Hidden {
    private class St(val hidden: Boolean, val at: Long)

    private lateinit var sp: SharedPreferences
    private val states = HashMap<String, St>()

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("koalatv_hidden", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(sp.getString("s", "{}") ?: "{}")
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                states[k] = St(v.optBoolean("h"), v.optLong("at"))
            }
        } catch (e: Exception) {
            // illisible : tout visible
        }
        // ancien réglage (code parental) : seules les catégories masquées sont reprises
        val old = c.applicationContext.getSharedPreferences("koalatv_parental", Context.MODE_PRIVATE)
        if (old.contains("cats")) {
            for (e in old.getStringSet("cats", emptySet()) ?: emptySet()) {
                val i = e.lastIndexOf('=')
                if (i > 0 && e.substring(i + 1) == "h") {
                    val (kind, id) = e.substring(0, i).split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    states[key(kind.lowercase(), id)] = St(true, 1L)
                }
            }
            old.edit().clear().apply()
            save()
        }
    }

    private fun key(kind: String, catId: String) = "$kind:$catId"
    private fun key(kind: Kind, catId: String) = key(kind.name.lowercase(), catId)

    @Synchronized
    fun isHidden(kind: Kind, catId: String): Boolean = states[key(kind, catId)]?.hidden == true

    @Synchronized
    fun set(kind: Kind, catId: String, hidden: Boolean) {
        val k = key(kind, catId)
        val s = St(hidden, System.currentTimeMillis())
        states[k] = s
        save()
        Sync.pushCat(k, s.hidden, s.at)
    }

    @Synchronized
    fun all(): Map<String, Pair<Boolean, Long>> = states.mapValues { it.value.hidden to it.value.at }

    /** Fusion avec l'état partagé. Renvoie true si quelque chose a changé. */
    @Synchronized
    fun merge(remote: Map<String, Pair<Boolean, Long>>): Boolean {
        var changed = false
        for ((k, v) in remote) {
            val local = states[k]
            if (local != null && local.at >= v.second) continue
            if (local?.hidden != v.first) changed = true
            states[k] = St(v.first, v.second)
        }
        save()
        return changed
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in states) o.put(k, JSONObject().put("h", v.hidden).put("at", v.at))
        sp.edit().putString("s", o.toString()).apply()
    }
}
