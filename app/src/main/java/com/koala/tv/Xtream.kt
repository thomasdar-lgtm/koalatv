package com.koala.tv

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String)

data class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String,
    val categoryId: String,
    val ext: String,
) {
    val favKey: String get() = "${kind.name}:$id"
}

data class Episode(
    val id: String,
    val title: String,
    val ext: String,
    val season: Int,
    val num: Int,
    val plot: String,
)

data class PlayItem(
    val title: String,
    val url: String,
    val live: Boolean,
    val resumeKey: String?,
    val streamId: String?,
)

object Cache {
    val cats = HashMap<Kind, List<Category>>()
    val items = HashMap<Kind, List<Item>>()
    fun clear() {
        cats.clear(); items.clear()
    }
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

    private fun arr(txt: String): JSONArray = try {
        JSONArray(txt)
    } catch (e: Exception) {
        JSONArray()
    }

    suspend fun login(server: String, user: String, pass: String): JSONObject = withContext(Dispatchers.IO) {
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
        ui
    }

    suspend fun categories(kind: Kind): List<Category> = withContext(Dispatchers.IO) {
        Cache.cats[kind]?.let { return@withContext it }
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val a = arr(fetch(api(action)))
        val out = ArrayList<Category>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Category(o.s("category_id"), o.s("category_name")))
        }
        Cache.cats[kind] = out
        out
    }

    suspend fun items(kind: Kind): List<Item> = withContext(Dispatchers.IO) {
        Cache.items[kind]?.let { return@withContext it }
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val a = arr(fetch(api(action)))
        val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val item = when (kind) {
                Kind.LIVE -> Item(kind, o.s("stream_id"), o.s("name"), o.s("stream_icon"), o.s("category_id"), "")
                Kind.MOVIE -> Item(
                    kind, o.s("stream_id"), o.s("name"), o.s("stream_icon"), o.s("category_id"),
                    o.s("container_extension").ifEmpty { "mp4" },
                )
                Kind.SERIES -> Item(kind, o.s("series_id"), o.s("name"), o.s("cover"), o.s("category_id"), "")
            }
            if (item.id.isNotEmpty()) out.add(item)
        }
        Cache.items[kind] = out
        out
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

    /** Programme en cours / suivant (EPG courte). Renvoie "" si indisponible. */
    suspend fun nowPlaying(streamId: String): String = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(fetch(api("get_short_epg", "&stream_id=$streamId&limit=2")))
            val l = root.optJSONArray("epg_listings") ?: return@withContext ""
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
            val sb = StringBuilder()
            for (i in 0 until l.length()) {
                val e = l.optJSONObject(i) ?: continue
                val title = b64(e.s("title"))
                if (title.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                val start = e.s("start_timestamp").toLongOrNull()
                if (start != null) sb.append(fmt.format(Date(start * 1000))).append("  ")
                sb.append(title)
            }
            sb.toString()
        } catch (e: Exception) {
            ""
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
