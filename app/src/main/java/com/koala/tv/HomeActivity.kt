package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    companion object {
        private var accountRefreshed = false
    }

    private lateinit var updateBar: View
    private lateinit var updateText: TextView
    private lateinit var updateAction: TextView
    private var release: Updater.Release? = null
    private var downloading = false
    private var waitingPermission = false
    private lateinit var resumeBox: View
    private lateinit var resumeAdapter: ResumeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.loggedIn) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_home)
        findViewById<TextView>(R.id.brand).text = brandText()

        val exp = Prefs.expiry.toLongOrNull()
        var account = Prefs.user
        if (exp != null && exp > 0) {
            account += " · expire le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(exp * 1000))
        }
        findViewById<TextView>(R.id.tv_account).text = account

        fun open(k: Kind) = startActivity(Intent(this, BrowseActivity::class.java).putExtra("kind", k.name))

        val live = findViewById<View>(R.id.tile_live)
        val movies = findViewById<View>(R.id.tile_movies)
        val series = findViewById<View>(R.id.tile_series)
        val settings = findViewById<View>(R.id.tile_settings)
        live.setOnClickListener { open(Kind.LIVE) }
        movies.setOnClickListener { open(Kind.MOVIE) }
        series.setOnClickListener { open(Kind.SERIES) }
        settings.setOnClickListener { showSettings() }
        listOf(live, movies, series, settings).forEach { it.focusZoom(1.05f) }

        updateBar = findViewById(R.id.update_bar)
        updateText = findViewById(R.id.update_text)
        updateAction = findViewById(R.id.update_action)
        updateBar.setOnClickListener { startUpdate() }

        resumeBox = findViewById(R.id.resume_box)
        resumeAdapter = ResumeAdapter(::resume, ::forget)
        findViewById<RecyclerView>(R.id.rv_resume).apply {
            val vertical = tag == "vertical" // téléphone en paysage : colonne à droite
            layoutManager = LinearLayoutManager(this@HomeActivity, if (vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false)
            adapter = resumeAdapter
            itemAnimator = null
        }
        // TV / tablette : les tuiles se réduisent pour laisser la place à la rangée « Reprendre »
        val area = findViewById<View?>(R.id.tiles_area)
        val row = findViewById<View?>(R.id.tiles_row)
        if (area != null && row != null) {
            val maxH = resources.getDimensionPixelSize(R.dimen.tile_row)
            area.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    val h = minOf(maxH, bottom - top - dp(24)).coerceAtLeast(dp(120))
                    if (row.layoutParams.height != h) row.post { row.layoutParams = row.layoutParams.apply { height = h } }
                }
            }
        }

        live.requestFocus()
        Lists.warmAll()
        if (!accountRefreshed) {
            accountRefreshed = true
            Lists.scope.launch { Xtream.refreshAccount() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::updateBar.isInitialized) return
        refreshResume()
        // historique partagé avec les autres appareils
        lifecycleScope.launch { if (Sync.pull()) refreshResume() }
        if (waitingPermission) {
            waitingPermission = false
            startUpdate()
        } else if (!downloading) {
            checkUpdate(force = false)
        }
    }

    private fun refreshResume() {
        val l = History.all()
        resumeAdapter.submit(l)
        resumeBox.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun resume(e: History.Entry) {
        if (e.seriesId.isEmpty()) {
            Nav.play(this, listOf(PlayItem(e.title, e.url, false, e.key, e.key.removePrefix("m"), e.icon)), 0)
            return
        }
        // Épisode : on recharge la saison pour enchaîner les épisodes suivants
        lifecycleScope.launch {
            val eps = try {
                Xtream.seriesEpisodes(e.seriesId)
            } catch (ex: Exception) {
                emptyList()
            }
            val epId = e.key.removePrefix("e")
            val ep = eps.firstOrNull { it.id == epId }
            if (ep == null) {
                Nav.play(this@HomeActivity, listOf(PlayItem(e.title, e.url, false, e.key, epId, e.icon, e.seriesId, e.seriesName)), 0)
                return@launch
            }
            val season = eps.filter { it.season == ep.season }
            Nav.play(this@HomeActivity, episodePlayList(e.seriesId, e.seriesName, e.icon, season), season.indexOf(ep))
        }
    }

    private fun forget(e: History.Entry) {
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf("Retirer de « Reprendre »", "Annuler")) { _, which ->
                if (which == 0) {
                    History.remove(e.key)
                    Prefs.setPos(e.key, 0)
                    refreshResume()
                }
            }
            .show()
    }

    private fun checkUpdate(force: Boolean) {
        lifecycleScope.launch {
            val rel = try {
                Updater.check(this@HomeActivity, force)
            } catch (e: Exception) {
                if (force) toast("Vérification impossible, réessaie plus tard")
                return@launch
            }
            release = rel
            if (rel == null) {
                updateBar.visibility = View.GONE
                if (force) toast("Tu as la dernière version (${Updater.currentVersion(this@HomeActivity)})")
            } else {
                showBar("Nouvelle version ${rel.version} disponible", "Installer")
            }
        }
    }

    private fun showBar(text: String, action: String) {
        updateText.text = text
        updateAction.text = action
        updateBar.visibility = View.VISIBLE
    }

    private fun startUpdate() {
        val rel = release ?: return
        if (downloading) return
        if (!Updater.ensureAllowed(this)) {
            waitingPermission = true
            toast("Autorise KoalaTV à installer des applis, puis reviens")
            return
        }
        downloading = true
        showBar("Téléchargement de la version ${rel.version}…", "0 %")
        lifecycleScope.launch {
            try {
                val apk = Updater.download(this@HomeActivity, rel) { pct -> updateAction.text = "$pct %" }
                showBar("Version ${rel.version} prête", "Installer")
                Updater.install(this@HomeActivity, apk)
            } catch (e: Exception) {
                showBar("Échec du téléchargement de la version ${rel.version}", "Réessayer")
            } finally {
                downloading = false
            }
        }
    }

    private fun chooseCategories() {
        AlertDialog.Builder(this)
            .setTitle("Catégories visibles")
            .setItems(arrayOf("TV en direct", "Films", "Séries")) { _, which ->
                startActivity(Intent(this, CategoriesActivity::class.java).putExtra("kind", Kind.values()[which].name))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Format du direct : " + if (Prefs.liveFormat == "ts") "MPEG-TS (recommandé)" else "HLS",
            "Lecteur : " + if (Prefs.externalPlayer) "externe (VLC)" else "intégré (recommandé)",
            "Actualiser les listes",
            "Catégories visibles",
            "Code parental : " + if (Parental.hasCode) "activé" else "désactivé",
            "Rechercher une mise à jour",
            "Se déconnecter",
        )
        AlertDialog.Builder(this)
            .setTitle("Réglages · v${Updater.currentVersion(this)}")
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
                        toast("Actualisation des listes…")
                        Lists.refreshAll { ok -> toast(if (ok) "Listes à jour" else "Actualisation impossible, réessaie plus tard") }
                    }
                    3 -> Parental.ask(this) { chooseCategories() }
                    4 -> Parental.settings(this)
                    5 -> checkUpdate(force = true)
                    6 -> AlertDialog.Builder(this)
                        .setTitle("Se déconnecter ?")
                        .setMessage("Tes favoris et reprises de lecture sont conservés.")
                        .setPositiveButton("Se déconnecter") { _, _ ->
                            Prefs.pass = ""
                            Lists.clear()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
