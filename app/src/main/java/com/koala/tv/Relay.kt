package com.koala.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * PC de la maison (KoalaTV Relais) : il télécharge films et épisodes à la demande des applis,
 * puis les sert sur le réseau local.
 *  - adresse et jeton du PC : h/<compte>/r ;
 *  - ce qu'il a téléchargé ou va télécharger : h/<compte>/rl ;
 *  - demandes : h/<compte>/q/<clé> ({kind, id, ext, t, i, sid, sn}, ou {del: 1}, {retry: 1}).
 */
object Relay {
    class Entry(
        val key: String,
        val title: String,
        val icon: String,
        val state: String, // attente | cours | fini | echec
        val pct: Int,
        val total: Long,
        val speed: Long,
        val error: String,
        val ext: String,
        val seriesId: String,
        val seriesName: String,
        val at: Long,
    ) {
        val playable get() = state == "fini" || (state == "cours" && pct > 0) || (state == "attente" && pct > 0)
        /** Élément de lecture : l'adresse en ligne reste la vraie (repli si le PC est injoignable). */
        fun toPlayItem() = PlayItem(
            title, if (key.startsWith("e")) Xtream.episodeUrl(key.drop(1), ext) else Xtream.movieUrl(key.drop(1), ext),
            false, key, key.drop(1), icon, seriesId, seriesName,
        )
    }

    @Volatile var name = ""
        private set
    @Volatile var pauseWhy = ""
        private set
    @Volatile private var ips: List<String> = emptyList()
    @Volatile private var port = 0
    @Volatile private var token = ""
    @Volatile private var seenAt = 0L
    /** Adresse joignable du PC (« http://ip:port »), vide si le PC n'est pas sur ce réseau. */
    @Volatile var base = ""
        private set
    @Volatile private var lib: Map<String, Entry> = emptyMap()
    @Volatile private var checkedAt = 0L

    /** Un PC est relié au compte (vu récemment). */
    val known get() = token.isNotEmpty() && System.currentTimeMillis() - seenAt < 24 * 3600_000L

    /** Plus récent d'abord ; envoyés ensemble (une saison) : dans l'ordre des titres. */
    fun all(): List<Entry> = lib.values.sortedWith(compareByDescending<Entry> { it.at / 60_000 }.thenBy { it.title })
    fun get(key: String?): Entry? = if (key == null) null else lib[key]

    private val ping by lazy { Xtream.http.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build() }

    /** Relit l'adresse du PC et sa bibliothèque, puis vérifie qu'il est joignable sur ce réseau. */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val r = Sync.getRaw("r") ?: return@withContext
        if (r.length() == 0) { token = ""; base = ""; lib = emptyMap(); return@withContext }
        name = r.optString("n")
        pauseWhy = r.optString("pause")
        port = r.optInt("port")
        token = r.optString("tok")
        seenAt = r.optLong("at")
        ips = r.optJSONArray("ip")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val rl = Sync.getRaw("rl") ?: JSONObject()
        val m = HashMap<String, Entry>()
        for (k in rl.keys()) {
            val o = rl.optJSONObject(k) ?: continue
            m[k] = Entry(
                k, o.optString("t"), o.optString("i"), o.optString("st"), o.optInt("pct"), o.optLong("tot"), o.optLong("sp"),
                o.optString("err"), o.optString("ext", "mkv"), o.optString("sid"), o.optString("sn"), o.optLong("at"),
            )
        }
        lib = m
        if (force || base.isEmpty() || System.currentTimeMillis() - checkedAt > 60_000) {
            checkedAt = System.currentTimeMillis()
            base = ips.firstOrNull { ip -> reachable("http://$ip:$port") }?.let { "http://$it:$port" } ?: ""
        }
    }

    private fun reachable(b: String): Boolean = try {
        ping.newCall(Request.Builder().url("$b/ping?t=$token").build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /** Adresse de lecture depuis le PC, ou null (pas téléchargé, ou PC injoignable d'ici). */
    fun playUrl(key: String?): String? {
        val e = get(key) ?: return null
        if (!e.playable || base.isEmpty()) return null
        return "$base/v/${e.key}.${e.ext}?t=$token"
    }

    /** Demande au PC de télécharger ces films ou épisodes. */
    fun send(items: List<PlayItem>): Int {
        var n = 0
        for (it in items) {
            val key = it.resumeKey ?: continue
            if (it.live || lib.containsKey(key)) continue
            val ext = it.url.substringBefore('?').substringAfterLast('.', "mkv").take(5)
            val o = JSONObject().put("kind", if (key.startsWith("e")) "series" else "movie").put("id", key.drop(1)).put("ext", ext)
                .put("t", it.title).put("i", it.icon).put("sid", it.seriesId).put("sn", it.seriesName).put("at", System.currentTimeMillis())
            Sync.putRaw("q/$key", o.toString())
            // affiché tout de suite, avant la confirmation du PC
            lib = lib + (key to Entry(key, it.title, it.icon, "attente", 0, 0, 0, "", ext, it.seriesId, it.seriesName, System.currentTimeMillis()))
            n++
        }
        return n
    }

    fun remove(key: String) {
        Sync.putRaw("q/$key", JSONObject().put("del", 1).toString())
        lib = lib - key
    }

    fun retry(key: String) = Sync.putRaw("q/$key", JSONObject().put("retry", 1).toString())

    fun refreshSoon(after: () -> Unit = {}) {
        Lists.scope.launch {
            refresh()
            withContext(Dispatchers.Main) { after() }
        }
    }

    /** « 12 Mo/s · encore 7 min » pour un téléchargement en cours sur le PC. */
    fun stateText(e: Entry): String = when (e.state) {
        "fini" -> "Sur le PC · ${fmtSize(e.total)}"
        "cours" -> "Téléchargement sur le PC · ${e.pct} %" + (if (e.total > 0) " de ${fmtSize(e.total)}" else "") +
            (if (e.speed > 0) " · " + String.format(java.util.Locale.FRANCE, "%.1f Mo/s", e.speed / 1048576.0) else "")
        "echec" -> "Échec sur le PC : ${e.error}"
        else -> if (pauseWhy.isNotEmpty()) "Sur le PC : en pause" + (if (e.pct > 0) " · ${e.pct} %" else "") else "En attente sur le PC" + (if (e.pct > 0) " · ${e.pct} %" else "")
    }
}
