package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.loggedIn) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_home)

        val exp = Prefs.expiry.toLongOrNull()
        val expTxt = if (exp != null && exp > 0) {
            "expire le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(exp * 1000))
        } else "sans date d'expiration"
        findViewById<TextView>(R.id.tv_account).text = "Compte ${Prefs.user} · $expTxt\nv${appVersion()}"

        fun open(k: Kind) = startActivity(Intent(this, BrowseActivity::class.java).putExtra("kind", k.name))

        val live = findViewById<View>(R.id.tile_live)
        val movies = findViewById<View>(R.id.tile_movies)
        val series = findViewById<View>(R.id.tile_series)
        val settings = findViewById<View>(R.id.tile_settings)
        live.setOnClickListener { open(Kind.LIVE) }
        movies.setOnClickListener { open(Kind.MOVIE) }
        series.setOnClickListener { open(Kind.SERIES) }
        settings.setOnClickListener { showSettings() }
        listOf(live, movies, series, settings).forEach { it.focusZoom(1.06f) }
        live.requestFocus()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Format du direct : " + if (Prefs.liveFormat == "ts") "MPEG-TS (recommandé)" else "HLS (m3u8)",
            "Lecteur : " + if (Prefs.externalPlayer) "externe (VLC…)" else "intégré (recommandé)",
            "Recharger les listes de chaînes / films",
            "Se déconnecter",
        )
        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.liveFormat = if (Prefs.liveFormat == "ts") "m3u8" else "ts"
                        showSettings()
                    }
                    1 -> {
                        Prefs.externalPlayer = !Prefs.externalPlayer
                        showSettings()
                    }
                    2 -> {
                        Cache.clear()
                        toast("Les listes seront rechargées")
                    }
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Se déconnecter de ce compte ?")
                        .setPositiveButton("Oui") { _, _ ->
                            Prefs.logout()
                            Cache.clear()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .setNegativeButton("Non", null)
                        .show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
