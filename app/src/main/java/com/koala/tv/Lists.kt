package com.koala.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Listes de chaînes / films / séries.
 * Ordre : mémoire → fichier sur l'appareil (affichage instantané) → serveur.
 * Une liste du disque de plus de 6 h est réaffichée tout de suite puis rafraîchie en arrière-plan.
 */
object Lists {
    private const val FORMAT = "ktv2"
    private const val MAX_AGE_MS = 6 * 3600 * 1000L

    class Data(val cats: List<Category>, val items: List<Item>, val savedAt: Long)

    private lateinit var dir: File
    private val mem = HashMap<Kind, Data>()
    private val locks = Kind.values().associateWith { Mutex() }
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Appelé sur le fil principal quand une liste a été rafraîchie en arrière-plan. */
    var onUpdated: ((Kind) -> Unit)? = null

    fun init(c: Context) {
        dir = File(c.filesDir, "lists").apply { mkdirs() }
    }

    private fun account() = "${Prefs.server}|${Prefs.user}"

    fun peek(kind: Kind): Data? = synchronized(mem) { mem[kind] }

    /** Liste prête à afficher (jamais vide si le cache existe). */
    suspend fun get(kind: Kind): Data = withContext(Dispatchers.IO) {
        peek(kind)?.let { return@withContext it }
        locks.getValue(kind).withLock {
            peek(kind)?.let { return@withLock it }
            val disk = readDisk(kind)
            if (disk != null) {
                synchronized(mem) { mem[kind] = disk }
                if (System.currentTimeMillis() - disk.savedAt > MAX_AGE_MS) refreshLater(kind)
                return@withLock disk
            }
            val fresh = download(kind)
            synchronized(mem) { mem[kind] = fresh }
            writeDisk(kind, fresh)
            fresh
        }
    }

    /** Prépare les trois listes en arrière-plan (accueil). */
    fun warmAll() {
        scope.launch {
            for (k in Kind.values()) {
                try { get(k) } catch (e: Exception) { /* sera retenté à l'ouverture */ }
            }
        }
    }

    /** Force le téléchargement de toutes les listes. */
    fun refreshAll(done: (Boolean) -> Unit) {
        scope.launch {
            var ok = true
            for (k in Kind.values()) {
                try { refreshNow(k) } catch (e: Exception) { ok = false }
            }
            main.post { done(ok) }
        }
    }

    private fun refreshLater(kind: Kind) {
        scope.launch { try { refreshNow(kind) } catch (e: Exception) { /* on garde la liste du disque */ } }
    }

    private suspend fun refreshNow(kind: Kind) {
        val fresh = download(kind)
        val old = peek(kind)
        synchronized(mem) { mem[kind] = fresh }
        writeDisk(kind, fresh)
        if (old == null || !sameContent(old, fresh)) main.post { onUpdated?.invoke(kind) }
    }

    private fun sameContent(a: Data, b: Data): Boolean {
        if (a.items.size != b.items.size || a.cats.size != b.cats.size) return false
        for (i in a.items.indices) if (a.items[i] != b.items[i]) return false
        for (i in a.cats.indices) if (a.cats[i] != b.cats[i]) return false
        return true
    }

    /** Efface tout (déconnexion, changement de compte). */
    fun clear() {
        synchronized(mem) { mem.clear() }
        if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() }
    }

    // ---------- Téléchargement (lecture en flux, sans charger toute la réponse en mémoire) ----------

    private fun download(kind: Kind): Data {
        val (catAction, listAction) = when (kind) {
            Kind.LIVE -> "get_live_categories" to "get_live_streams"
            Kind.MOVIE -> "get_vod_categories" to "get_vod_streams"
            Kind.SERIES -> "get_series_categories" to "get_series"
        }
        val cats = ArrayList<Category>()
        Xtream.stream(catAction) { r ->
            cats.clear() // nouvelle tentative après coupure : on repart de zéro
            readArray(r) { o -> cats.add(Category(o["category_id"] ?: "", o["category_name"] ?: "")) }
        }
        val items = ArrayList<Item>()
        Xtream.stream(listAction) { r ->
            items.clear()
            readArray(r) { o ->
                val id = (if (kind == Kind.SERIES) o["series_id"] else o["stream_id"]) ?: ""
                if (id.isEmpty()) return@readArray
                items.add(
                    Item(
                        kind = kind,
                        id = id,
                        name = o["name"] ?: "",
                        icon = (if (kind == Kind.SERIES) o["cover"] else o["stream_icon"]) ?: "",
                        categoryId = o["category_id"] ?: "",
                        ext = if (kind == Kind.MOVIE) (o["container_extension"] ?: "").ifEmpty { "mp4" } else "",
                        num = o["num"]?.toIntOrNull() ?: 0,
                        archive = if (o["tv_archive"] == "1") (o["tv_archive_duration"]?.toIntOrNull() ?: 0) else 0,
                        rating = o["rating"] ?: "",
                    ),
                )
            }
        }
        return Data(cats, items, System.currentTimeMillis())
    }

    private val WANTED = setOf(
        "category_id", "category_name", "stream_id", "series_id", "name", "stream_icon", "cover",
        "container_extension", "num", "tv_archive", "tv_archive_duration", "rating",
    )

    /** Parcourt un tableau JSON d'objets ; chaque objet est réduit aux champs utiles, en texte. */
    private fun readArray(r: JsonReader, each: (Map<String, String>) -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue(); return
        }
        r.beginArray()
        val o = HashMap<String, String>(16)
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue(); continue
            }
            o.clear()
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                when {
                    k == "category_ids" && r.peek() == JsonToken.BEGIN_ARRAY -> {
                        r.beginArray()
                        var first: String? = null
                        while (r.hasNext()) {
                            val v = value(r)
                            if (first == null && v.isNotEmpty()) first = v
                        }
                        r.endArray()
                        if (first != null && o["category_id"].isNullOrEmpty()) o["category_id"] = first
                    }
                    k in WANTED -> {
                        val v = value(r)
                        if (!(k == "category_id" && v.isEmpty() && o.containsKey(k))) o[k] = v
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            each(o)
        }
        r.endArray()
    }

    private fun value(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString().trim()
        JsonToken.BOOLEAN -> if (r.nextBoolean()) "1" else "0"
        JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    // ---------- Fichiers sur l'appareil ----------

    private fun file(kind: Kind) = File(dir, "${kind.name.lowercase()}.tsv")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun writeDisk(kind: Kind, d: Data) {
        try {
            val tmp = File(dir, "${kind.name.lowercase()}.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("$FORMAT\t${d.savedAt}\t${clean(account())}\t${d.cats.size}\n")
                for (c in d.cats) w.write("${clean(c.id)}\t${clean(c.name)}\n")
                for (i in d.items) {
                    w.write(clean(i.id)); w.write('\t'.code)
                    w.write(clean(i.name)); w.write('\t'.code)
                    w.write(clean(i.icon)); w.write('\t'.code)
                    w.write(clean(i.categoryId)); w.write('\t'.code)
                    w.write(clean(i.ext)); w.write('\t'.code)
                    w.write(i.num.toString()); w.write('\t'.code)
                    w.write(i.archive.toString()); w.write('\t'.code)
                    w.write(clean(i.rating)); w.write('\n'.code)
                }
            }
            if (!tmp.renameTo(file(kind))) tmp.delete()
        } catch (e: IOException) {
            // disque plein : on continue sans cache
        }
    }

    private fun readDisk(kind: Kind): Data? {
        val f = file(kind)
        if (!f.exists()) return null
        return try {
            f.bufferedReader(Charsets.UTF_8).use { r ->
                val head = (r.readLine() ?: return null).split('\t')
                if (head.size < 4 || head[0] != FORMAT || head[2] != clean(account())) return null
                val savedAt = head[1].toLongOrNull() ?: return null
                val nCats = head[3].toIntOrNull() ?: return null
                val cats = ArrayList<Category>(nCats)
                repeat(nCats) {
                    val p = (r.readLine() ?: return null).split('\t')
                    cats.add(Category(p[0], p.getOrElse(1) { "" }))
                }
                val items = ArrayList<Item>()
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split('\t')
                    if (p.size < 8) continue
                    items.add(Item(kind, p[0], p[1], p[2], p[3], p[4], p[5].toIntOrNull() ?: 0, p[6].toIntOrNull() ?: 0, p[7]))
                }
                Data(cats, items, savedAt)
            }
        } catch (e: Exception) {
            null
        }
    }
}
