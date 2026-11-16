package com.koala.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * Téléchargements : films et épisodes enregistrés sur l'appareil pour une lecture sans coupure,
 * ou sans connexion. Un seul fichier à la fois (l'abonnement n'autorise qu'une connexion),
 * mis en pause pendant une lecture en ligne sur cet appareil.
 *
 * Ordre de téléchargement : la fin du fichier d'abord (l'index des MKV et l'en-tête de certains MP4 s'y trouvent),
 * puis le début, d'un seul tenant. Le lecteur peut ainsi lire le fichier pendant qu'il se remplit.
 */

object Downloads {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    class Dl(
        val key: String,
        val title: String,
        val url: String,
        val icon: String,
        val seriesId: String,
        val seriesName: String,
        val path: String,
        @Volatile var total: Long = -1,
        @Volatile var done: Long = 0,
        @Volatile var tailFrom: Long = -1,
        @Volatile var tailDone: Boolean = false,
        @Volatile var state: State = State.QUEUED,
        @Volatile var error: String = "",
        /** Vitesse moyenne récente (octets par seconde), 0 si inconnue. */
        @Volatile var speed: Long = 0,
        val added: Long = System.currentTimeMillis(),
    ) {
        val file get() = File(path)
        /** Octets lisibles à partir de `pos` sans attendre (0 s'il faut attendre). */
        fun available(pos: Long): Long = when {
            state == State.DONE && total > 0 -> (total - pos).coerceAtLeast(0)
            pos < done -> done - pos
            tailDone && tailFrom in 0..pos && pos < total -> total - pos
            else -> 0
        }
        /** Octets restant à télécharger. */
        val remaining: Long get() = if (total <= 0) -1 else (total - done - if (tailDone) total - tailFrom else 0).coerceAtLeast(0)
        /** « 12 Mo/s · encore 7 min », vide tant que la vitesse est inconnue. */
        fun pace(): String {
            if (speed <= 0 || state != State.RUNNING) return ""
            val mbs = speed / 1048576.0
            val rate = if (mbs >= 10) "${mbs.toInt()} Mo/s" else String.format(java.util.Locale.FRANCE, "%.1f Mo/s", mbs)
            val left = remaining
            if (left < 0) return rate
            val s = left / speed
            val eta = when {
                s < 60 -> "moins d'une minute"
                s < 3600 -> "${s / 60} min"
                else -> "${s / 3600} h ${String.format("%02d", s / 60 % 60)}"
            }
            return "$rate · encore $eta"
        }
        val percent: Int get() = if (total > 0) ((done + if (tailDone) total - tailFrom else 0) * 100 / total).toInt().coerceIn(0, 100) else 0
        fun toPlayItem() = PlayItem(title, url, false, key, key.drop(1), icon, seriesId, seriesName)
    }

    private lateinit var ctx: Context
    private val list = ArrayList<Dl>()
    private val listeners = ArrayList<() -> Unit>()

    /** Une lecture en ligne a lieu sur cet appareil : le téléchargement attend (une seule connexion). */
    @Volatile var heldByPlayback = false
        private set

    fun init(c: Context) {
        ctx = c.applicationContext
        load()
        // un téléchargement interrompu (appli fermée) reprendra
        synchronized(list) { list.filter { it.state == State.RUNNING }.forEach { it.state = State.QUEUED } }
    }

    // ---------------- stockage ----------------

    /** Emplacements possibles : mémoire interne et supports amovibles (carte, clé USB, disque). */
    fun volumes(): List<File> = ctx.getExternalFilesDirs(null).filterNotNull().filter {
        try { android.os.Environment.getExternalStorageState(it) == android.os.Environment.MEDIA_MOUNTED } catch (e: Exception) { false }
    }.ifEmpty { listOf(ctx.filesDir) }

    fun freeBytes(dir: File): Long = try { StatFs(dir.path).availableBytes } catch (e: Exception) { 0 }

    fun isRemovable(dir: File): Boolean = try { android.os.Environment.isExternalStorageRemovable(dir) } catch (e: Exception) { false }

    /** Dossier choisi (réglage), sinon le support qui a le plus de place. */
    fun dir(): File {
        val vols = volumes()
        val chosen = Prefs.downloadDir
        val base = vols.firstOrNull { it.path == chosen } ?: vols.maxByOrNull { freeBytes(it) } ?: ctx.filesDir
        return File(base, "Téléchargements").apply { mkdirs() }
    }

    // ---------------- liste ----------------

    fun all(): List<Dl> = synchronized(list) { list.sortedByDescending { it.added } }
    fun get(key: String?): Dl? = if (key == null) null else synchronized(list) { list.firstOrNull { it.key == key } }
    fun done(key: String?): Dl? = get(key)?.takeIf { it.state == State.DONE && it.file.exists() }
    val active: Dl? get() = synchronized(list) { list.firstOrNull { it.state == State.RUNNING } }

    fun listen(f: () -> Unit) = synchronized(listeners) { listeners.add(f) }
    fun unlisten(f: () -> Unit) = synchronized(listeners) { listeners.remove(f) }
    private fun changed() {
        val l = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post { l.forEach { it() } }
    }

    /** Ajoute à la file (sans doublon) et lance le service. */
    fun add(items: List<PlayItem>): Int {
        var n = 0
        synchronized(list) {
            for (it in items) {
                val key = it.resumeKey ?: continue
                if (it.live || list.any { d -> d.key == key }) continue
                val ext = it.url.substringBefore('?').substringAfterLast('.', "mp4").take(5).ifEmpty { "mp4" }
                val name = it.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)
                val f = File(dir(), "$name-$key.$ext")
                list.add(Dl(key, it.title, it.url, it.icon, it.seriesId, it.seriesName, f.path))
                n++
            }
        }
        save()
        changed()
        kick()
        return n
    }

    fun remove(key: String) {
        val d = get(key) ?: return
        synchronized(list) { list.remove(d) }
        Worker.cancelIf(key)
        try { d.file.delete() } catch (e: Exception) { /* ignoré */ }
        save()
        changed()
    }

    fun retry(key: String) {
        val d = get(key) ?: return
        d.state = State.QUEUED
        d.error = ""
        save()
        changed()
        kick()
    }

    /** Lecture en ligne qui commence ou se termine sur cet appareil. */
    fun hold(on: Boolean) {
        heldByPlayback = on
        if (on) Worker.pauseNow() else kick()
        changed()
    }

    /** Démarre le service s'il y a quelque chose à télécharger. */
    fun kick() {
        if (heldByPlayback) return
        if (synchronized(list) { list.none { it.state == State.QUEUED || it.state == State.RUNNING } }) return
        try {
            val i = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            // démarrage refusé (appli en arrière-plan) : reprise à la prochaine ouverture
        }
    }

    fun next(): Dl? = synchronized(list) { list.filter { it.state == State.QUEUED }.minByOrNull { it.added } }

    /** Réseau autorisé : Wi-Fi ou Ethernet, sauf si les données mobiles sont permises. */
    fun networkOk(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return Prefs.downloadMobile || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---------------- enregistrement ----------------

    private fun store() = File(ctx.filesDir, "downloads.json")

    fun save() {
        val a = JSONArray()
        synchronized(list) {
            for (d in list) a.put(
                JSONObject().put("k", d.key).put("t", d.title).put("u", d.url).put("i", d.icon).put("sid", d.seriesId).put("sn", d.seriesName)
                    .put("p", d.path).put("tot", d.total).put("done", d.done).put("tf", d.tailFrom).put("td", d.tailDone)
                    .put("st", d.state.name).put("err", d.error).put("at", d.added),
            )
        }
        try { store().writeText(a.toString()) } catch (e: Exception) { /* ignoré */ }
    }

    private fun load() {
        val a = try { JSONArray(store().readText()) } catch (e: Exception) { return }
        synchronized(list) {
            list.clear()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(
                    Dl(
                        o.getString("k"), o.optString("t"), o.optString("u"), o.optString("i"), o.optString("sid"), o.optString("sn"), o.optString("p"),
                        o.optLong("tot", -1), o.optLong("done"), o.optLong("tf", -1), o.optBoolean("td"),
                        try { State.valueOf(o.optString("st")) } catch (e: Exception) { State.QUEUED }, o.optString("err"), o.optLong("at"),
                    ),
                )
            }
        }
    }

    fun notifyProgress() = changed()

    // ---------------- transfert ----------------

    class NoSpace(msg: String) : Exception(msg)

    /** Le transfert lui-même, indépendant du service (testable). */
    object Worker {
        @Volatile private var call: okhttp3.Call? = null
        @Volatile private var currentKey: String? = null
        @Volatile private var stopFlag = false

        fun pauseNow() { stopFlag = true; call?.cancel() }
        fun cancelIf(key: String) { if (currentKey == key) pauseNow() }

        private const val TAIL_MAX = 24L * 1024 * 1024

        /** Télécharge un fichier jusqu'au bout ; renvoie false s'il a été interrompu ou a échoué. */
        fun run(d: Dl, onProgress: () -> Unit): Boolean {
            stopFlag = false
            currentKey = d.key
            d.state = State.RUNNING
            d.error = ""
            save(); changed()
            var failures = 0
            try {
                while (!stopFlag) {
                    try {
                        step(d, onProgress)
                        if (d.total > 0 && d.done >= d.tailFrom && d.tailDone) {
                            d.state = State.DONE
                            save(); changed()
                            return true
                        }
                    } catch (e: NoSpace) {
                        d.state = State.FAILED
                        d.error = e.message ?: "Pas assez de place"
                        save(); changed()
                        return false
                    } catch (e: IOException) {
                        if (stopFlag) break
                        if (++failures >= 6) {
                            d.state = State.FAILED
                            d.error = e.message ?: "Erreur réseau"
                            save(); changed()
                            return false
                        }
                        Thread.sleep(2000L * failures)
                    }
                }
                // interrompu : reprendra plus tard
                if (d.state == State.RUNNING) d.state = State.QUEUED
                save(); changed()
                return false
            } finally {
                currentKey = null
                call = null
            }
        }

        /** Une étape : taille, puis fin du fichier, puis la suite à partir de `done`. */
        private fun step(d: Dl, onProgress: () -> Unit) {
            val f = d.file
            f.parentFile?.mkdirs()
            if (d.total <= 0) {
                val r = request(d.url, 0, 0)
                r.use {
                    val total = it.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: if (it.code == 200) it.body?.contentLength() ?: -1 else -1
                    if (total <= 0) throw IOException("Taille inconnue")
                    // place : le fichier entier, plus une marge
                    val free = freeBytes(f.parentFile ?: f)
                    if (free in 1 until total + (200L shl 20)) throw NoSpace("Pas assez de place : ${fmtSize(total)} nécessaires, ${fmtSize(free)} libres")
                    // le fichier existe avant que la taille soit connue du lecteur (lecture pendant le téléchargement)
                    RandomAccessFile(f, "rw").use { raf -> raf.setLength(total) }
                    // la fin d'abord : 2 % du fichier, 24 Mo au plus
                    val tail = (total - minOf(TAIL_MAX, maxOf(1L shl 20, total / 50))).coerceAtLeast(0)
                    d.tailFrom = tail
                    if (tail == 0L) d.tailDone = true
                    d.total = total
                }
                save()
            }
            if (!d.tailDone) {
                copy(d, d.tailFrom, d.total, onProgress) { }
                d.tailDone = true
                save(); onProgress()
            }
            if (d.done < d.tailFrom) copy(d, d.done, d.tailFrom, onProgress) { d.done = it }
        }

        /** Copie [from, to) du serveur dans le fichier ; `mark` reçoit la position atteinte. */
        private fun copy(d: Dl, from: Long, to: Long, onProgress: () -> Unit, mark: (Long) -> Unit) {
            if (from >= to) return
            val r = request(d.url, from, to - 1)
            r.use { resp ->
                if (resp.code != 206 && !(resp.code == 200 && from == 0L)) throw IOException("Le serveur refuse la reprise (${resp.code})")
                val body = resp.body ?: throw IOException("Réponse vide")
                RandomAccessFile(d.file, "rw").use { raf ->
                    raf.seek(from)
                    val input = body.byteStream()
                    val buf = ByteArray(256 * 1024)
                    var pos = from
                    var lastNote = 0L
                    var lastPos = from
                    while (pos < to) {
                        if (stopFlag) throw IOException("interrompu")
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n
                        mark(pos)
                        val now = System.currentTimeMillis()
                        if (now - lastNote > 1000) {
                            // vitesse lissée sur les dernières secondes
                            if (lastNote > 0) {
                                val inst = (pos - lastPos) * 1000 / (now - lastNote)
                                d.speed = if (d.speed <= 0) inst else (d.speed * 7 + inst * 3) / 10
                            }
                            lastNote = now
                            lastPos = pos
                            onProgress()
                        }
                    }
                    if (pos < to) throw IOException("Connexion coupée")
                    mark(pos)
                }
            }
        }

        private fun request(url: String, from: Long, to: Long): okhttp3.Response {
            val req = Request.Builder().url(url).header("User-Agent", Prefs.userAgent).header("Range", "bytes=$from-$to").build()
            val c = Xtream.http.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req)
            call = c
            val r = c.execute()
            if (r.code >= 400) { r.close(); throw IOException("Erreur du serveur (${r.code})") }
            return r
        }
    }
}

/** Service au premier plan : traite la file, un fichier à la fois, avec une notification de progression. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val chan = "downloads"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(note("Préparation…", 0, true))
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (Downloads.heldByPlayback) break
            val d = Downloads.next() ?: break
            if (!Downloads.networkOk()) {
                d.error = if (Prefs.downloadMobile) "Pas de connexion" else "En attente du Wi-Fi"
                Downloads.save(); Downloads.notifyProgress()
                break
            }
            Presence.start("Téléchargement : ${d.title}")
            Downloads.Worker.run(d) {
                manager().notify(1, note(d.title + (d.pace().let { if (it.isEmpty()) "" else " · $it" }), d.percent, false))
                Downloads.notifyProgress()
            }
            if (!Downloads.heldByPlayback) Presence.stopDownload()
            delay(300)
        }
        Presence.stopDownload()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        Downloads.Worker.pauseNow()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun note(text: String, pct: Int, indeterminate: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= 26 && manager().getNotificationChannel(chan) == null) {
            manager().createNotificationChannel(NotificationChannel(chan, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, chan)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Téléchargement")
            .setContentText(if (indeterminate) text else "$text · $pct %")
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }
}

/**
 * Lecture d'un fichier en cours de téléchargement : les octets manquants sont attendus.
 * Adresse « ktvdl://<clé> ».
 */
class GrowingFileDataSource : BaseDataSource(false) {
    private var raf: RandomAccessFile? = null
    private var dl: Downloads.Dl? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    @Volatile private var closed = false

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        closed = false
        val d = Downloads.get(spec.uri.host) ?: throw IOException("Téléchargement introuvable")
        dl = d
        // taille connue dès que le téléchargement a commencé
        waitFor { d.total > 0 }
        transferInitializing(spec)
        raf = RandomAccessFile(d.file, "r")
        pos = spec.position
        left = if (spec.length != C.LENGTH_UNSET.toLong()) spec.length else d.total - pos
        transferStarted(spec)
        return left
    }

    private fun waitFor(ok: () -> Boolean) {
        var waited = 0
        while (!ok()) {
            if (closed) throw IOException("fermé")
            val d = dl
            if (d != null && d.state == Downloads.State.FAILED) throw IOException(d.error.ifEmpty { "Téléchargement en échec" })
            // téléchargement arrêté (pause, réseau) depuis 2 min : on abandonne
            if (waited > 120_000) throw IOException("Téléchargement interrompu")
            Thread.sleep(100)
            if (d == null || d.state != Downloads.State.RUNNING) waited += 100
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        val d = dl ?: return C.RESULT_END_OF_INPUT
        waitFor { d.available(pos) > 0 }
        val n = minOf(length.toLong(), left, d.available(pos)).toInt()
        val f = raf ?: throw IOException("fermé")
        f.seek(pos)
        val r = f.read(buffer, offset, n)
        if (r < 0) return C.RESULT_END_OF_INPUT
        pos += r
        left -= r
        bytesTransferred(r)
        return r
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        try { raf?.close() } catch (e: Exception) { /* ignoré */ }
        raf = null
        if (uri != null) { uri = null; transferEnded() }
    }

    /** Fabrique : « ktvdl:// » pour les fichiers en cours, le reste au lecteur habituel. */
    class Factory(private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Switch(fallback.createDataSource())
    }

    private class Switch(private val other: DataSource) : DataSource {
        private var active: DataSource? = null
        private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
        override fun addTransferListener(l: androidx.media3.datasource.TransferListener) { other.addTransferListener(l); listeners.add(l) }
        override fun open(spec: DataSpec): Long {
            val ds = if (spec.uri.scheme == "ktvdl") GrowingFileDataSource().also { g -> listeners.forEach { g.addTransferListener(it) } } else other
            active = ds
            return ds.open(spec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = active?.uri
        override fun close() { active?.close(); active = null }
    }
}

/** Taille lisible : « 3,4 Go », « 820 Mo ». */
fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b >= 1L shl 30 -> String.format(java.util.Locale.FRANCE, "%.1f Go", b / (1L shl 30).toDouble())
    else -> "${b / (1L shl 20)} Mo"
}
