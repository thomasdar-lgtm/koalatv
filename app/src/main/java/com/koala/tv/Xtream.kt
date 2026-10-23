package com.koala.tv

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String, val count: Int = 0)

data class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String,
    val categoryId: String,
    val ext: String,
    val num: Int = 0,
    /** Jours de replay disponibles (0 = pas de replay). */
    val archive: Int = 0,
    val rating: String = "",
) {
    val favKey: String get() = "${kind.name}:$id"

    /** Nom en minuscules sans accents, calculé une seule fois pour la recherche. */
    val searchKey: String by lazy(LazyThreadSafetyMode.NONE) { fold(name) }
}

data class Episode(
    val id: String,
    val title: String,
    val ext: String,
    val season: Int,
    val num: Int,
    val plot: String,
)

data class MovieInfo(
    val title: String,
    val poster: String,
    val backdrop: String,
    val plot: String,
    val year: String,
    val minutes: Int,
    val genre: String,
    val rating: String,
    val director: String,
    val cast: String,
    val ext: String,
)

/** Programme du guide TV (heures en secondes depuis 1970). */
data class Program(val title: String, val start: Long, val end: Long, val desc: String = "") {
    fun progress(nowSec: Long): Int =
        if (end > start) (((nowSec - start) * 1000) / (end - start)).toInt().coerceIn(0, 1000) else 0
}

data class PlayItem(
    val title: String,
    val url: String,
    val live: Boolean,
    val resumeKey: String?,
    val streamId: String?,
    val icon: String = "",
    val seriesId: String = "",
    val seriesName: String = "",
    val num: Int = 0,
)

fun liveItem(it: Item) = PlayItem(it.name, Xtream.liveUrl(it.id), true, null, it.id, it.icon, num = it.num)

/** Liste de lecture d'une saison : les épisodes s'enchaînent. */
fun episodePlayList(seriesId: String, seriesName: String, cover: String, eps: List<Episode>): List<PlayItem> =
    eps.map { e ->
        val label = if (e.season > 0) "S${e.season} É${e.num}" else "É${e.num}"
        PlayItem("$seriesName · $label", Xtream.episodeUrl(e.id, e.ext), false, "e${e.id}", e.id, cover, seriesId, seriesName)
    }

/** Lit une valeur JSON en texte, en gérant les null / nombres renvoyés par certains panels. */
fun JSONObject.s(k: String): String {
    val v = opt(k)
    return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
}

object Xtream {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val u = Uri.parse(s)
        val host = u.host ?: return s.trimEnd('/')
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://$host$port"
    }

    /** Si l'utilisateur colle un lien M3U (get.php?username=..&password=..), on en extrait les identifiants. */
    fun parseM3uLink(raw: String): Triple<String, String, String>? {
        val s = raw.trim()
        if (!s.contains("username=") || !s.contains("password=")) return null
        val u = Uri.parse(if (s.startsWith("http", true)) s else "http://$s")
        val user = u.getQueryParameter("username") ?: return null
        val pass = u.getQueryParameter("password") ?: return null
        return Triple(normalizeServer(s), user, pass)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(server: String, user: String, pass: String, action: String?, extra: String = ""): String =
        "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}" +
            (if (action != null) "&action=$action" else "") + extra

    private fun api(action: String, extra: String = "") =
        apiUrl(Prefs.server, Prefs.user, Prefs.pass, action, extra)

    private fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Prefs.userAgent).build()
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Erreur serveur HTTP ${r.code}")
                    return r.body?.string() ?: throw IOException("Réponse vide")
                }
            } catch (e: IOException) {
                last = e
                if (attempt < 2) Thread.sleep(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("Erreur réseau")
    }

    /** Lit une réponse en flux (grandes listes) avec 3 tentatives. */
    fun stream(action: String, block: (JsonReader) -> Unit) {
        val req = Request.Builder().url(api(action)).header("User-Agent", Prefs.userAgent).build()
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Erreur serveur HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Réponse vide")
                    JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { jr ->
                        jr.isLenient = true
                        block(jr)
                    }
                    return
                }
            } catch (e: IOException) {
                last = e
                if (attempt < 2) Thread.sleep(800L * (attempt + 1))
            } catch (e: IllegalStateException) {
                throw IOException("Réponse invalide du serveur")
            }
        }
        throw last ?: IOException("Erreur réseau")
    }

    data class Account(val expiry: String, val tzOffset: Long?)

    suspend fun login(server: String, user: String, pass: String): Account = withContext(Dispatchers.IO) {
        val txt = fetch(apiUrl(server, user, pass, null))
        val root = try {
            JSONObject(txt)
        } catch (e: Exception) {
            throw IOException("Réponse invalide : ce n'est pas un serveur Xtream")
        }
        val ui = root.optJSONObject("user_info") ?: throw IOException("Identifiants refusés")
        if (ui.s("auth") != "1") throw IOException("Identifiants refusés")
        val status = ui.s("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) throw IOException("Abonnement : $status")
        Account(ui.s("exp_date"), serverOffset(root.optJSONObject("server_info")))
    }

    /** Remet à jour l'expiration et le fuseau du serveur (une fois par lancement). */
    suspend fun refreshAccount() {
        try {
            val a = login(Prefs.server, Prefs.user, Prefs.pass)
            Prefs.expiry = a.expiry
            if (a.tzOffset != null) Prefs.tzOffset = a.tzOffset
        } catch (e: Exception) {
            // hors ligne : on garde les valeurs connues
        }
    }

    /** Décalage (secondes) entre l'heure locale du serveur et l'heure UTC : utile pour le replay. */
    private fun serverOffset(si: JSONObject?): Long? {
        if (si == null) return null
        val ts = si.s("timestamp_now").toLongOrNull()
        val local = si.s("time_now")
        if (ts != null && local.isNotEmpty()) {
            try {
                val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val asUtc = (f.parse(local)?.time ?: 0L) / 1000
                if (asUtc > 0) return Math.round((asUtc - ts) / 900.0) * 900
            } catch (e: Exception) {
                // format inattendu : on essaie le nom du fuseau
            }
        }
        val tz = si.s("timezone")
        if (tz.isNotEmpty()) {
            val z = java.util.TimeZone.getTimeZone(tz)
            if (z.id == tz) return z.getOffset(System.currentTimeMillis()) / 1000L
        }
        return null
    }

    /** Adresse de replay : début à l'heure du serveur, durée en minutes. */
    fun catchupUrl(streamId: String, startSec: Long, durationMin: Int): String {
        val off = Prefs.tzOffset ?: (java.util.TimeZone.getDefault().getOffset(startSec * 1000) / 1000L)
        val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val start = f.format(java.util.Date((startSec + off) * 1000))
        return "${Prefs.server}/timeshift/${creds()}/$durationMin/$start/$streamId.ts"
    }

    suspend fun seriesEpisodes(seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(fetch(api("get_series_info", "&series_id=$seriesId")))
        } catch (e: org.json.JSONException) {
            throw IOException("Réponse invalide du serveur")
        }
        val out = ArrayList<Episode>()
        fun addArr(a: JSONArray, seasonHint: Int) {
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val info = e.optJSONObject("info")
                out.add(
                    Episode(
                        id = e.s("id"),
                        title = e.s("title"),
                        ext = e.s("container_extension").ifEmpty { "mp4" },
                        season = e.s("season").toIntOrNull() ?: seasonHint,
                        num = e.s("episode_num").toIntOrNull() ?: (i + 1),
                        plot = info?.s("plot") ?: "",
                    ),
                )
            }
        }
        val eps = root.opt("episodes")
        if (eps is JSONObject) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = eps.optJSONArray(k)
                if (a != null) addArr(a, k.toIntOrNull() ?: 0)
            }
        } else if (eps is JSONArray) {
            for (i in 0 until eps.length()) {
                val a = eps.optJSONArray(i)
                if (a != null) addArr(a, i + 1)
            }
        }
        out.sortWith(compareBy<Episode>({ it.season }, { it.num }))
        out
    }

    /** Fiche d'un film (résumé, durée, note…). */
    suspend fun vodInfo(id: String): MovieInfo = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(fetch(api("get_vod_info", "&vod_id=$id")))
        } catch (e: org.json.JSONException) {
            throw IOException("Réponse invalide du serveur")
        }
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        val backdrop = when (val b = info.opt("backdrop_path")) {
            is JSONArray -> if (b.length() > 0) b.optString(0) else ""
            is String -> b
            else -> ""
        }
        val secs = info.s("duration_secs").toLongOrNull()
            ?: info.s("duration").split(':').let { p -> if (p.size == 3) p[0].toLongOrNull()?.let { h -> h * 3600 + (p[1].toLongOrNull() ?: 0) * 60 } else null }
            ?: info.s("episode_run_time").toLongOrNull()?.times(60)
            ?: 0L
        val date = info.s("releasedate").ifEmpty { info.s("release_date") }
        MovieInfo(
            title = info.s("name").ifEmpty { data.s("name") },
            poster = info.s("movie_image").ifEmpty { info.s("cover_big") },
            backdrop = backdrop,
            plot = info.s("plot").ifEmpty { info.s("description") },
            year = Regex("\\b(19|20)\\d{2}\\b").find(date)?.value ?: "",
            minutes = (secs / 60).toInt(),
            genre = info.s("genre"),
            rating = info.s("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.FRANCE, "%.1f", it) } ?: "",
            director = info.s("director"),
            cast = info.s("cast").ifEmpty { info.s("actors") },
            ext = data.s("container_extension"),
        )
    }

    /** Programmes à venir d'une chaîne (le premier est celui en cours). Liste vide si pas de guide. */
    suspend fun shortEpg(streamId: String, limit: Int): List<Program> = epgCall("get_short_epg", "&stream_id=$streamId&limit=$limit", false)

    /** Guide complet (passé compris) pour les chaînes avec replay. */
    suspend fun fullEpg(streamId: String): List<Program> = epgCall("get_simple_data_table", "&stream_id=$streamId", true)

    private suspend fun epgCall(action: String, extra: String, keepPast: Boolean): List<Program> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(fetch(api(action, extra)))
            val l = root.optJSONArray("epg_listings") ?: return@withContext emptyList()
            val now = System.currentTimeMillis() / 1000
            val out = ArrayList<Program>()
            for (i in 0 until l.length()) {
                val e = l.optJSONObject(i) ?: continue
                val title = b64(e.s("title"))
                val start = e.s("start_timestamp").toLongOrNull() ?: continue
                val end = e.s("stop_timestamp").toLongOrNull() ?: e.s("end_timestamp").toLongOrNull() ?: continue
                if (title.isEmpty() || end <= start || (!keepPast && end <= now)) continue
                out.add(Program(title, start, end, b64(e.s("description"))))
            }
            out.sortBy { it.start }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) {
        s
    }

    private fun creds() = "${Uri.encode(Prefs.user)}/${Uri.encode(Prefs.pass)}"

    fun liveUrl(id: String) = "${Prefs.server}/live/${creds()}/$id.${Prefs.liveFormat}"
    fun movieUrl(id: String, ext: String) = "${Prefs.server}/movie/${creds()}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "${Prefs.server}/series/${creds()}/$id.$ext"
}
