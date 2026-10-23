package com.koala.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Vérifie la dernière version publiée sur GitHub et l'installe. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/thomasdar-lgtm/koalatv/releases/latest"
    private const val CHECK_EVERY_MS = 12 * 3600 * 1000L

    data class Release(val version: String, val apkUrl: String, val size: Long)

    fun currentVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** « 1.10.0 » > « 1.9.2 » : comparaison nombre par nombre. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Renvoie la nouvelle version si elle existe ; `force` ignore le délai entre deux vérifications. */
    suspend fun check(c: Context, force: Boolean): Release? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.lastUpdateCheck < CHECK_EVERY_MS) {
            val cached = Prefs.pendingUpdate
            return@withContext if (cached != null && isNewer(cached.version, currentVersion(c))) cached else null
        }
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KoalaTV")
            .build()
        val body = Xtream.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Réponse vide")
        }
        val j = JSONObject(body)
        val version = j.optString("tag_name").removePrefix("v")
        val assets = j.optJSONArray("assets")
        var url = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
        }
        Prefs.lastUpdateCheck = now
        val rel = if (version.isNotEmpty() && url.isNotEmpty()) Release(version, url, size) else null
        Prefs.pendingUpdate = rel
        if (rel != null && isNewer(rel.version, currentVersion(c))) rel else null
    }

    private fun dir(c: Context): File {
        // Avant Android 7, l'installeur doit pouvoir lire le fichier directement
        val base = if (Build.VERSION.SDK_INT < 24) (c.getExternalFilesDir(null) ?: c.cacheDir) else c.cacheDir
        return File(base, "updates").apply { mkdirs() }
    }

    /** Télécharge l'APK ; `progress` reçoit 0..100. */
    suspend fun download(c: Context, rel: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val d = dir(c)
        d.listFiles()?.forEach { it.delete() }
        val out = File(d, "KoalaTV-${rel.version}.apk")
        val tmp = File(d, "download.tmp")
        val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "KoalaTV").build()
        Xtream.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("Réponse vide")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                withContext(Dispatchers.Main) { progress(pct) }
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Téléchargement incomplet")
        }
        if (!tmp.renameTo(out)) throw IOException("Écriture impossible")
        if (Build.VERSION.SDK_INT < 24) out.setReadable(true, false)
        out
    }

    /** Android 8+ : l'appli doit être autorisée à installer. Renvoie false si on a ouvert l'écran d'autorisation. */
    fun ensureAllowed(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26 || a.packageManager.canRequestPackageInstalls()) return true
        return try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            false
        } catch (e: ActivityNotFoundException) {
            true // certains téléviseurs n'ont pas cet écran : l'installeur demandera lui-même
        }
    }

    fun install(a: Activity, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(a, "${a.packageName}.files", apk)
        } else {
            Uri.fromFile(apk)
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
    }
}
