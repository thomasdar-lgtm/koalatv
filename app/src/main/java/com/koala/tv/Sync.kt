package com.koala.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Historique « Reprendre » partagé entre tous les appareils connectés au même abonnement.
 * Stocké dans une base Firebase (Realtime Database), à une adresse calculée à partir des identifiants :
 * impossible à deviner sans eux. Chaque élément est écrit séparément, la version la plus récente gagne.
 */
object Sync {
    /** Adresse de la base Firebase (vide = partage désactivé). */
    const val DB = ""

    /** Élément distant : `entry` null = supprimé sur un autre appareil. */
    class Remote(val key: String, val at: Long, val entry: History.Entry?)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val enabled get() = DB.isNotEmpty() && Prefs.loggedIn

    /** Identifiant du compte : empreinte des identifiants Xtream. */
    private fun account(): String {
        val raw = "koalatv|${Prefs.server.lowercase()}|${Prefs.user}|${Prefs.pass}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun url(path: String) = "${DB.trimEnd('/')}/h/${account()}/$path.json"

    private fun encode(e: History.Entry): String = JSONObject()
        .put("t", e.title).put("u", e.url).put("i", e.icon)
        .put("p", e.pos / 1000).put("d", e.dur / 1000).put("at", e.at)
        .put("sid", e.seriesId).put("sn", e.seriesName)
        .toString()

    private fun put(path: String, body: String) {
        if (!enabled) return
        Lists.scope.launch {
            try {
                val req = Request.Builder().url(url(path)).put(body.toRequestBody(JSON)).build()
                Xtream.http.newCall(req).execute().close()
            } catch (e: Exception) {
                // hors ligne : la prochaine écriture rattrapera
            }
        }
    }

    fun push(e: History.Entry) = put("e/${e.key}", encode(e))

    fun pushGone(key: String, at: Long) = put("e/$key", JSONObject().put("gone", true).put("at", at).toString())

    /** Récupère l'historique partagé et le fusionne. Renvoie true si l'affichage doit être rafraîchi. */
    suspend fun pull(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        try {
            val body = Xtream.http.newCall(Request.Builder().url(url("e")).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                r.body?.string() ?: return@withContext false
            }
            val o = if (body.trim() == "null") JSONObject() else JSONObject(body)
            val out = ArrayList<Remote>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optJSONObject(k) ?: continue
                val at = v.optLong("at")
                if (at <= 0) continue
                if (v.optBoolean("gone")) {
                    out.add(Remote(k, at, null))
                } else {
                    out.add(
                        Remote(
                            k, at,
                            History.Entry(
                                k, v.optString("t"), v.optString("u"), v.optString("i"),
                                v.optLong("p") * 1000, v.optLong("d") * 1000, at, v.optString("sid"), v.optString("sn"),
                            ),
                        ),
                    )
                }
            }
            val changed = History.merge(out)
            // éléments locaux plus récents (ou absents) : on les envoie
            val remoteAt = out.associate { it.key to it.at }
            for (e in History.all()) if ((remoteAt[e.key] ?: 0L) < e.at) push(e)
            changed
        } catch (e: Exception) {
            false
        }
    }
}
