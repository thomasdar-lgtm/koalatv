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
    const val DB = "https://koalatv-dae7b-default-rtdb.europe-west1.firebasedatabase.app"

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

    /** Écriture brute (présence de lecture). */
    fun putRaw(path: String, body: String) = put(path, body)

    /** Lecture brute d'un nœud de l'historique partagé, ou null. */
    suspend fun getRaw(path: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        try {
            Xtream.http.newCall(Request.Builder().url(url(path)).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val t = r.body?.string()?.trim() ?: return@withContext null
                if (t == "null") JSONObject() else JSONObject(t)
            }
        } catch (e: Exception) {
            null
        }
    }

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

    fun pushSeen(key: String, at: Long) = put("w/$key", JSONObject().put("at", at).toString())

    /** Récupère les films et épisodes vus ailleurs. Renvoie true si quelque chose a changé ici. */
    suspend fun pullSeen(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        try {
            val body = Xtream.http.newCall(Request.Builder().url(url("w")).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                r.body?.string() ?: return@withContext false
            }
            val o = if (body.trim() == "null") JSONObject() else JSONObject(body)
            val remote = HashMap<String, Long>()
            for (k in o.keys()) o.optJSONObject(k)?.optLong("at")?.takeIf { it > 0 }?.let { remote[k] = it }
            val changed = Seen.merge(remote)
            for ((k, at) in Seen.all()) if (!remote.containsKey(k)) pushSeen(k, at)
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** Favoris : clé partagée « live:123 » (identique sur LG), clé locale « LIVE:123 ». */
    private fun favPath(localKey: String) = "f/" + localKey.lowercase().replace(Regex("[.$#\\[\\]/:]"), "_")

    fun pushFav(localKey: String, on: Boolean, at: Long) =
        put(favPath(localKey), JSONObject().put("k", localKey.lowercase()).put("on", on).put("at", at).toString())

    /** Récupère les favoris partagés. Renvoie true si quelque chose a changé ici. */
    suspend fun pullFavs(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        try {
            val body = Xtream.http.newCall(Request.Builder().url(url("f")).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                r.body?.string() ?: return@withContext false
            }
            val o = if (body.trim() == "null") JSONObject() else JSONObject(body)
            val remoteAt = HashMap<String, Long>()
            var changed = false
            val favs = Prefs.favs()
            for (p in o.keys()) {
                val v = o.optJSONObject(p) ?: continue
                val k = v.optString("k")
                val at = v.optLong("at")
                val i = k.indexOf(':')
                if (i <= 0 || at <= 0) continue
                val local = k.substring(0, i).uppercase() + k.substring(i)
                remoteAt[local] = at
                if (at > Prefs.favAt(local)) {
                    val on = v.optBoolean("on")
                    if (favs.contains(local) != on) changed = true
                    Prefs.setFav(local, on, at)
                }
            }
            for ((k, at) in Prefs.favAll()) {
                if ((remoteAt[k] ?: 0L) < at) pushFav(k, Prefs.favs().contains(k), at)
            }
            changed
        } catch (e: Exception) {
            false
        }
    }

    /** Clé Firebase d'une catégorie (les caractères . $ # [ ] / y sont interdits). */
    private fun catPath(k: String) = "c/" + k.replace(Regex("[.$#\\[\\]/:]"), "_")

    fun pushCat(k: String, hidden: Boolean, at: Long) =
        put(catPath(k), JSONObject().put("k", k).put("h", hidden).put("at", at).toString())

    /** Récupère les catégories masquées partagées. Renvoie true si les listes doivent être reconstruites. */
    suspend fun pullCats(): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        try {
            val body = Xtream.http.newCall(Request.Builder().url(url("c")).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                r.body?.string() ?: return@withContext false
            }
            val o = if (body.trim() == "null") JSONObject() else JSONObject(body)
            val remote = HashMap<String, Pair<Boolean, Long>>()
            for (p in o.keys()) {
                val v = o.optJSONObject(p) ?: continue
                val k = v.optString("k")
                val at = v.optLong("at")
                if (k.isEmpty() || at <= 0) continue
                remote[k] = v.optBoolean("h") to at
            }
            val changed = Hidden.merge(remote)
            for ((k, v) in Hidden.all()) if ((remote[k]?.second ?: 0L) < v.second) pushCat(k, v.first, v.second)
            changed
        } catch (e: Exception) {
            false
        }
    }

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
