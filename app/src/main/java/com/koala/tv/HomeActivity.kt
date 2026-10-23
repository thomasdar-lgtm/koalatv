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
    /** Retour depuis l'écran des catégories : on rouvre le menu d'où l'on venait. */
    private var reopenCategories = false
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
        // historique et catégories masquées partagés avec les autres appareils
        lifecycleScope.launch { if (Sync.pull()) refreshResume() }
        lifecycleScope.launch { Sync.pullCats() }
        if (reopenCategories) {
            reopenCategories = false
            chooseCategories()
        }
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
                reopenCategories = true
                startActivity(Intent(this, CategoriesActivity::class.java).putExtra("kind", Kind.values()[which].name))
            }
            .setNegativeButton("Retour") { _, _ -> showSettings() }
            .setOnCancelListener { showSettings() }
            .show()
    }

    /** Envoie les identifiants vers le code affiché sur l'écran de connexion d'un autre appareil. */
    private fun pairDevice() {
        val et = android.widget.EditText(this).apply {
            hint = "ABCD EFGH"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(9))
            gravity = android.view.Gravity.CENTER
            textSize = 26f
            letterSpacing = 0.15f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@HomeActivity, R.color.text))
            background = androidx.core.content.ContextCompat.getDrawable(this@HomeActivity, R.drawable.field_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(et)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("Connecter un appareil")
            .setMessage("Saisis le code affiché sur l'écran de connexion de l'autre appareil.")
            .setView(box)
            .setPositiveButton("Envoyer", null)
            .setNegativeButton("Retour") { _, _ -> showSettings() }
            .setOnCancelListener { showSettings() }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = Pair.clean(et.text.toString())
                if (code == null) {
                    et.error = "Code incorrect"
                    return@setOnClickListener
                }
                d.dismiss()
                toast("Envoi des identifiants…")
                lifecycleScope.launch {
                    if (!Pair.send(code)) {
                        toast("Envoi impossible, vérifie la connexion"); return@launch
                    }
                    // l'autre appareil récupère les identifiants en quelques secondes
                    repeat(20) {
                        kotlinx.coroutines.delay(3000)
                        if (Pair.consumed(code)) {
                            toast("Appareil connecté"); return@launch
                        }
                    }
                    Pair.cancel(code)
                    toast("L'appareil n'a pas répondu, vérifie le code")
                }
            }
        }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
        et.requestFocus()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Format du direct : " + if (Prefs.liveFormat == "ts") "MPEG-TS (recommandé)" else "HLS",
            "Lecteur : " + if (Prefs.externalPlayer) "externe (VLC)" else "intégré (recommandé)",
            "Actualiser les listes",
            "Catégories visibles",
            "Connecter un appareil",
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
                    3 -> chooseCategories()
                    4 -> pairDevice()
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
                        .setNegativeButton("Annuler") { _, _ -> showSettings() }
                        .setOnCancelListener { showSettings() }
                        .show()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }
}
