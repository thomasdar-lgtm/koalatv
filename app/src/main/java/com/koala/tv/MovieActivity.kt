package com.koala.tv

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/** Fiche d'un film : affiche, résumé, durée, note, puis lecture. */
class MovieActivity : AppCompatActivity() {

    private var id = ""
    private var name = ""
    private var icon = ""
    private var ext = "mp4"
    private val favKey get() = "${Kind.MOVIE.name}:$id"
    private val resumeKey get() = "m$id"

    private lateinit var btnPlay: TextView
    private lateinit var btnRestart: TextView
    private lateinit var btnFav: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie)
        setupBack()
        id = intent.getStringExtra("id") ?: ""
        name = intent.getStringExtra("name") ?: ""
        icon = intent.getStringExtra("icon") ?: ""
        ext = (intent.getStringExtra("ext") ?: "").ifEmpty { "mp4" }
        val rating = intent.getStringExtra("rating") ?: ""

        btnPlay = findViewById(R.id.btn_play)
        btnRestart = findViewById(R.id.btn_restart)
        btnFav = findViewById(R.id.btn_fav)
        btnPlay.setOnClickListener { play(fromStart = false) }
        btnRestart.setOnClickListener { play(fromStart = true) }
        btnFav.setOnClickListener {
            val added = Prefs.toggleFav(favKey)
            toast(if (added) "Ajouté aux favoris" else "Retiré des favoris")
            paintFav()
        }
        listOf(btnPlay, btnRestart, btnFav).forEach { it.focusZoom(1.05f) }

        // affichage immédiat avec ce qu'on connaît déjà, puis la fiche complète
        show(MovieInfo(name, icon, "", "", "", 0, "", rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.FRANCE, "%.1f", it) } ?: "", "", "", ""))
        btnPlay.requestFocus()
        lifecycleScope.launch {
            try {
                val info = Xtream.vodInfo(id)
                if (info.ext.isNotEmpty()) ext = info.ext
                show(info.copy(title = info.title.ifEmpty { name }, poster = info.poster.ifEmpty { icon }))
            } catch (e: Exception) {
                // la fiche reste minimale, la lecture fonctionne quand même
            } finally {
                findViewById<View>(R.id.progress).visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        paintButtons()
        paintFav()
    }

    private fun show(m: MovieInfo) {
        findViewById<TextView>(R.id.tv_title).text = m.title
        val meta = listOfNotNull(
            m.rating.ifEmpty { null },
            m.year.ifEmpty { null },
            if (m.minutes > 0) (if (m.minutes >= 60) "${m.minutes / 60} h ${String.format("%02d", m.minutes % 60)}" else "${m.minutes} min") else null,
        ).joinToString("  ·  ")
        findViewById<TextView>(R.id.tv_meta).text = meta
        findViewById<View>(R.id.iv_star).visibility = if (m.rating.isNotEmpty()) View.VISIBLE else View.GONE
        setText(R.id.tv_genre, m.genre)
        setText(R.id.tv_plot, m.plot)
        val credits = listOfNotNull(
            m.director.ifEmpty { null }?.let { "Réalisation : $it" },
            m.cast.ifEmpty { null }?.let { "Avec : $it" },
        ).joinToString("\n")
        setText(R.id.tv_credits, credits)
        val poster = findViewById<ImageView>(R.id.iv_poster)
        if (m.poster.startsWith("http", true)) poster.load(m.poster) { crossfade(150) }
        val back = findViewById<ImageView>(R.id.iv_backdrop)
        val b = m.backdrop.ifEmpty { m.poster }
        if (b.startsWith("http", true)) back.load(b) { crossfade(250) }
    }

    private fun setText(viewId: Int, t: String) {
        val v = findViewById<TextView>(viewId)
        v.text = t
        v.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun paintButtons() {
        val pos = Prefs.getPos(resumeKey)
        btnPlay.text = if (pos > 0) "Reprendre à ${fmtTime(pos)}" else "Lecture"
        btnRestart.visibility = if (pos > 0) View.VISIBLE else View.GONE
    }

    private fun paintFav() {
        val on = Prefs.favs().contains(favKey)
        btnFav.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.accent else R.color.muted))
        btnFav.contentDescription = if (on) "Retirer des favoris" else "Ajouter aux favoris"
    }

    private fun play(fromStart: Boolean) {
        val item = PlayItem(name, Xtream.movieUrl(id, ext), false, resumeKey, id, icon)
        val start = if (fromStart) 0L else Prefs.getPos(resumeKey)
        Nav.play(this, listOf(item), 0, startAt = start)
    }
}
