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
        Parental.init(this)
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
    var userAgent: String
        get() = str("ua", DEFAULT_UA)
        set(v) = put("ua", v)
    var externalPlayer: Boolean
        get() = sp.getBoolean("ext", false)
        set(v) = sp.edit().putBoolean("ext", v).apply()

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
        val s = favs().toMutableSet()
        val added = if (s.contains(key)) {
            s.remove(key); false
        } else {
            s.add(key); true
        }
        sp.edit().putStringSet("favs", s).apply()
        return added
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
