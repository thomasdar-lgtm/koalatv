package com.koala.tv

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Test de connexion : internet, serveur Xtream, abonnement et débit réel du serveur, avec un verdict. */
class DiagActivity : BaseActivity() {

    private enum class St { WAIT, OK, WARN, BAD }

    private class Row(val v: View) {
        val dot: View = v.findViewById(R.id.dot)
        val value: TextView = v.findViewById(R.id.v)
    }

    private lateinit var net: Row
    private lateinit var srv: Row
    private lateinit var acc: Row
    private lateinit var str: Row
    private lateinit var verdict: TextView
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diag)
        setupBack()
        val box = findViewById<View>(R.id.box)
        box.layoutParams = box.layoutParams.apply { width = minOf(dp(560), resources.displayMetrics.widthPixels - dp(32)) }
        fun row(id: Int, label: String) = Row(findViewById(id)).also { it.v.findViewById<TextView>(R.id.k).text = label }
        net = row(R.id.r_net, "Connexion internet")
        srv = row(R.id.r_srv, "Serveur Xtream")
        acc = row(R.id.r_acc, "Abonnement")
        str = row(R.id.r_str, "Débit du serveur")
        verdict = findViewById(R.id.tv_verdict)
        findViewById<View>(R.id.btn_again).setOnClickListener { run() }
        run()
    }

    private fun set(r: Row, st: St, text: String) {
        r.value.text = text
        r.value.setTextColor(getColor(if (st == St.WAIT) R.color.faint else R.color.text_soft))
        val c = when (st) {
            St.WAIT -> 0xFF4C9AFF.toInt()
            St.OK -> 0xFF3DDC97.toInt()
            St.WARN -> 0xFFFFB347.toInt()
            St.BAD -> 0xFFFF6B6B.toInt()
        }
        r.dot.backgroundTintList = ColorStateList.valueOf(c)
    }

    private class Speed(val ttfb: Long, val mbps: Double)

    /** Télécharge l'adresse pendant `seconds` secondes au plus et mesure le débit. */
    private suspend fun speed(url: String, seconds: Int): Speed? = withContext(Dispatchers.IO) {
        try {
            val c = Xtream.http.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout((seconds + 12).toLong(), TimeUnit.SECONDS).build()
            val t0 = SystemClock.elapsedRealtime()
            c.newCall(Request.Builder().url(url).header("User-Agent", Prefs.userAgent).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val input = r.body?.byteStream() ?: return@withContext null
                val buf = ByteArray(64 * 1024)
                var n = input.read(buf)
                val tFirst = SystemClock.elapsedRealtime()
                var bytes = 0L
                while (n >= 0) {
                    bytes += n
                    if (SystemClock.elapsedRealtime() - tFirst >= seconds * 1000L) break
                    n = input.read(buf)
                }
                val ms = (SystemClock.elapsedRealtime() - tFirst).coerceAtLeast(1)
                Speed(tFirst - t0, bytes * 8.0 / ms / 1000.0)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fmt(v: Double) = if (v >= 100) "${v.toInt()} Mbit/s" else String.format(Locale.FRANCE, "%.1f Mbit/s", v)

    private fun run() {
        job?.cancel()
        listOf(net, srv, acc, str).forEach { set(it, St.WAIT, "En attente") }
        set(net, St.WAIT, "Mesure…")
        verdict.text = ""
        job = lifecycleScope.launch {
            // 1. internet
            val ping = speed(NET + "0", 1)
            val n = speed(NET + "50000000", 6)
            val netMbps = n?.mbps ?: -1.0
            if (n == null) set(net, St.BAD, "Pas d'accès à internet")
            else set(net, if (netMbps < 8) St.WARN else St.OK, "${fmt(netMbps)} · réponse en ${ping?.ttfb ?: n.ttfb} ms")
            // 2. serveur et abonnement
            set(srv, St.WAIT, "Mesure…")
            val t0 = SystemClock.elapsedRealtime()
            val ui = Xtream.userInfo()
            val srvMs = SystemClock.elapsedRealtime() - t0
            if (ui == null) {
                set(srv, St.BAD, "Ne répond pas")
                set(acc, St.BAD, "Inconnu")
                set(str, St.BAD, "Non mesuré")
                verdict.text = if (n == null) "Pas d'accès à internet : vérifie ta box ou le Wi-Fi."
                else "Internet fonctionne, mais le serveur Xtream ne répond pas : le souci vient du fournisseur."
                return@launch
            }
            set(srv, if (srvMs > 2000) St.WARN else St.OK, "Répond en $srvMs ms")
            val auth = ui.optString("auth") == "1" && (ui.optString("status").isEmpty() || ui.optString("status").equals("Active", true))
            val cons = ui.optString("active_cons").toIntOrNull() ?: 0
            val max = ui.optString("max_connections").toIntOrNull() ?: 0
            var txt = if (auth) "Actif" else "Inactif"
            if (max > 0) txt += " · $cons / $max connexion" + if (max > 1) "s" else ""
            ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let {
                txt += " · expire le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000))
            }
            set(acc, if (!auth) St.BAD else if (max in 1..cons) St.WARN else St.OK, txt)
            // 3. débit réel du serveur : un film (le plus vite possible), sinon une chaîne
            set(str, St.WAIT, "Mesure…")
            val movie = try { Lists.get(Kind.MOVIE).items.firstOrNull() } catch (e: Exception) { null }
            val live = if (movie == null) (try { Lists.get(Kind.LIVE).items.firstOrNull() } catch (e: Exception) { null }) else null
            val url = when {
                movie != null -> Xtream.movieUrl(movie.id, movie.ext.ifEmpty { "mp4" })
                live != null -> "${Prefs.server}/live/${android.net.Uri.encode(Prefs.user)}/${android.net.Uri.encode(Prefs.pass)}/${live.id}.ts"
                else -> null
            }
            val s = if (url != null) speed(url, 6) else null
            val strMbps = s?.mbps ?: -1.0
            when {
                url == null -> set(str, St.WARN, "Aucun contenu pour mesurer")
                s == null -> set(str, St.BAD, "Le serveur n'envoie pas de vidéo")
                else -> set(str, if (strMbps < 6) St.WARN else St.OK, fmt(strMbps) + if (movie == null) " (chaîne en direct)" else "")
            }
            verdict.text = when {
                n == null -> "Pas d'accès à internet : vérifie ta box ou le Wi-Fi."
                !auth -> "L'abonnement Xtream est refusé ou inactif : contacte ton fournisseur."
                max in 1..cons -> "L'abonnement est déjà utilisé au maximum ($cons / $max) : ferme l'appli sur un autre appareil."
                netMbps < 8 -> "Ta connexion internet est lente (${fmt(netMbps)}) : la HD demande au moins 8 Mbit/s."
                url != null && s == null -> "Internet fonctionne, mais le serveur Xtream n'envoie pas de vidéo : le souci vient du fournisseur."
                s != null && movie != null && strMbps < 6 -> "Le serveur Xtream est lent (${fmt(strMbps)} contre ${fmt(netMbps)} pour internet) : le souci vient du fournisseur."
                srvMs > 2000 -> "Le serveur Xtream met du temps à répondre : les listes et le zapping peuvent être lents."
                else -> "Tout va bien : ta connexion et le serveur Xtream sont rapides."
            }
        }
    }

    companion object {
        /** Serveur de référence pour mesurer internet (rapide, partout). */
        const val NET = "https://speed.cloudflare.com/__down?bytes="
    }
}
