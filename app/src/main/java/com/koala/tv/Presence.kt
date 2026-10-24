package com.koala.tv

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Lecture sur les autres appareils : chaque appareil KoalaTV signale ce qu'il lit (toutes les 30 s),
 * et le serveur Xtream donne le nombre de connexions actives de l'abonnement. Même format que l'appli LG.
 */
object Presence {
    class Other(val name: String, val title: String)
    class State(val others: List<Other>, val cons: Int, val max: Int) {
        val busy: Boolean get() = others.size >= maxOf(1, max) || (max > 0 && cons >= max)
    }

    private var id = ""
    var name = "Android"
        private set
    /** Fin de la dernière lecture : le serveur peut encore compter cet appareil un moment. */
    var lastStop = 0L
        private set
    private var beat: Job? = null

    fun init(c: Context) {
        val sp = c.getSharedPreferences("koalatv_presence", Context.MODE_PRIVATE)
        id = sp.getString("id", null) ?: ("an" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()).also {
            sp.edit().putString("id", it).apply()
        }
        name = try {
            android.provider.Settings.Global.getString(c.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
    }

    fun start(title: String) {
        beat?.cancel()
        beat = Lists.scope.launch {
            while (true) {
                Sync.putRaw("p/$id", JSONObject().put("n", name).put("t", title).put("at", System.currentTimeMillis()).toString())
                delay(30_000)
            }
        }
    }

    fun stop() {
        if (beat == null) return
        beat?.cancel()
        beat = null
        lastStop = System.currentTimeMillis()
        Sync.putRaw("p/$id", "null")
    }

    /** Autres lectures en cours (2,5 s au plus) ; `server` : demander aussi les connexions actives au serveur Xtream. */
    suspend fun check(server: Boolean): State = coroutineScope {
        val pres = async {
            withTimeoutOrNull(2500) { Sync.getRaw("p") }
        }
        val ui = async { if (server) Xtream.userInfo(2500) else null }
        val o = pres.await()
        val others = ArrayList<Other>()
        if (o != null) for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            if (k != id && System.currentTimeMillis() - v.optLong("at") < 75_000) others.add(Other(v.optString("n"), v.optString("t")))
        }
        val u = ui.await()
        State(others, u?.optString("active_cons")?.toIntOrNull() ?: 0, u?.optString("max_connections")?.toIntOrNull() ?: 0)
    }
}
