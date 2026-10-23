package com.koala.tv

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Lists.init(this)
        History.init(this)
        Hidden.init(this)
        Seen.init(this)
    }
}

object Prefs {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("koalatv", Context.MODE_PRIVATE)
    }

    private fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var server: String
        get() = str("server")
        set(v) = put("server", v)
    var user: String
        get() = str("user")
        set(v) = put("user", v)
    var pass: String
        get() = str("pass")
        set(v) = put("pass", v)
    var expiry: String
        get() = str("expiry")
        set(v) = put("expiry", v)
    var liveFormat: String
        get() = str("live_format", "ts")
        set(v) = put("live_format", v)
    /** Dernière langue audio choisie (vide = français par défaut). */
    var audioLang: String
        get() = str("audio_lang", "")
        set(v) = put("audio_lang", v)
    /** Derniers sous-titres choisis : code de langue, « off », ou vide (par défaut). */
    var textLang: String
        get() = str("text_lang", "")
        set(v) = put("text_lang", v)
    var userAgent: String
        get() = str("ua", DEFAULT_UA)
        set(v) = put("ua", v)
    var externalPlayer: Boolean
        get() = sp.getBoolean("ext", false)
        set(v) = sp.edit().putBoolean("ext", v).apply()

    /** Décalage horaire du serveur en secondes (null = inconnu, on prend celui de l'appareil). */
    var tzOffset: Long?
        get() = if (sp.contains("tz_off")) sp.getLong("tz_off", 0L) else null
        set(v) {
            if (v == null) sp.edit().remove("tz_off").apply() else sp.edit().putLong("tz_off", v).apply()
        }

    var lastUpdateCheck: Long
        get() = sp.getLong("upd_check", 0L)
        set(v) = sp.edit().putLong("upd_check", v).apply()

    /** Dernière version trouvée sur GitHub (version|url|taille). */
    var pendingUpdate: Updater.Release?
        get() {
            val p = str("upd_rel").split('|')
            return if (p.size == 3) Updater.Release(p[0], p[1], p[2].toLongOrNull() ?: 0L) else null
        }
        set(v) = put("upd_rel", if (v == null) "" else "${v.version}|${v.apkUrl}|${v.size}")

    val loggedIn: Boolean
        get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()

    fun logout() {
        sp.edit().remove("server").remove("user").remove("pass").remove("expiry").apply()
    }

    fun favs(): Set<String> = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())

    fun toggleFav(key: String): Boolean {
        val added = !favs().contains(key)
        setFav(key, added, System.currentTimeMillis())
        Sync.pushFav(key, added, favAt(key))
        return added
    }

    /** Date du dernier changement de chaque favori (pour le partage entre appareils : le plus récent gagne). */
    fun favAt(key: String): Long = try {
        org.json.JSONObject(sp.getString("fav_at", "{}") ?: "{}").optLong(key)
    } catch (e: Exception) {
        0L
    }

    fun favAll(): Map<String, Long> = try {
        val o = org.json.JSONObject(sp.getString("fav_at", "{}") ?: "{}")
        val m = HashMap<String, Long>()
        for (k in o.keys()) m[k] = o.optLong(k)
        // favoris d'avant le partage : date minimale, pour être envoyés sans écraser un changement plus récent
        for (k in favs()) if (!m.containsKey(k)) m[k] = 1L
        m
    } catch (e: Exception) {
        emptyMap()
    }

    @Synchronized
    fun setFav(key: String, on: Boolean, at: Long) {
        val s = favs().toMutableSet()
        if (on) s.add(key) else s.remove(key)
        val o = try {
            org.json.JSONObject(sp.getString("fav_at", "{}") ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        o.put(key, at)
        sp.edit().putStringSet("favs", s).putString("fav_at", o.toString()).apply()
    }

    fun getPos(key: String): Long = sp.getLong("pos_$key", 0L)

    fun setPos(key: String, p: Long) {
        if (p <= 0L) sp.edit().remove("pos_$key").apply()
        else sp.edit().putLong("pos_$key", p).apply()
    }
}

/** Liste de lecture partagée entre les écrans et le lecteur. */
object Playback {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0
    /** Position de départ choisie avant la lecture (fiche film) : -1 = demander s'il y a une reprise. */
    var startAt: Long = -1
}

object Nav {
    /** Fiche d'un film ou d'une série. */
    fun openDetails(a: android.app.Activity, item: Item) {
        when (item.kind) {
            Kind.MOVIE -> a.startActivity(
                android.content.Intent(a, MovieActivity::class.java)
                    .putExtra("id", item.id).putExtra("name", item.name).putExtra("icon", item.icon)
                    .putExtra("ext", item.ext).putExtra("rating", item.rating),
            )
            Kind.SERIES -> a.startActivity(
                android.content.Intent(a, SeriesActivity::class.java)
                    .putExtra("id", item.id).putExtra("name", item.name).putExtra("cover", item.icon),
            )
            Kind.LIVE -> {}
        }
    }

    fun play(a: Activity, items: List<PlayItem>, index: Int, startAt: Long = -1) {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.size - 1)
        if (Prefs.externalPlayer && openExternal(a, items[i])) return
        Playback.items = items
        Playback.index = i
        Playback.startAt = startAt
        a.startActivity(Intent(a, PlayerActivity::class.java))
    }

    fun openExternal(a: Activity, item: PlayItem): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.url), "video/*")
            .putExtra("title", item.title)
        return try {
            a.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, "Aucun lecteur externe installé (installe VLC)", Toast.LENGTH_LONG).show()
            false
        }
    }
}

fun View.focusZoom(scale: Float) {
    setOnFocusChangeListener { v, f ->
        val s = if (f) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun fmtTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
