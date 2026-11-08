package com.koala.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Télécommande de la diffusion : la vidéo est lue par le téléviseur, le téléphone affiche
 * la progression, enregistre la reprise et enchaîne les épisodes.
 */
class CastActivity : BaseActivity() {

    private val items get() = Casting.items
    private val current get() = items[Casting.index]

    private lateinit var tvState: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageView
    private var poll: Job? = null
    private var playing = false
    private var dragging = false
    private var lastPos = 0L
    private var lastDur = 0L
    private var lastSave = 0L
    private var lost = 0
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Casting.remote == null || items.isEmpty()) {
            finish(); return
        }
        setContentView(R.layout.activity_cast)
        tvState = findViewById(R.id.cast_state)
        tvPos = findViewById(R.id.cast_pos)
        tvDur = findViewById(R.id.cast_dur)
        seek = findViewById(R.id.cast_seek)
        btnPlay = findViewById(R.id.cast_play)
        findViewById<TextView>(R.id.cast_device).text = "Sur ${Casting.remote?.name}"

        setupBack { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        btnPlay.setOnClickListener { cmd { if (playing) it.pause() else it.play() }; playing = !playing; paintPlay() }
        findViewById<View>(R.id.cast_rew).setOnClickListener { jump(-10_000) }
        findViewById<View>(R.id.cast_fwd).setOnClickListener { jump(30_000) }
        findViewById<View>(R.id.cast_prev).setOnClickListener { episode(-1) }
        findViewById<View>(R.id.cast_next).setOnClickListener { episode(1) }
        findViewById<View>(R.id.cast_stop).setOnClickListener { stopCast(openHere = false) }
        findViewById<View>(R.id.cast_here).setOnClickListener { stopCast(openHere = true) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user) tvPos.text = fmtTime(p.toLong() * 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                val ms = s.progress.toLong() * 1000
                lastPos = ms
                cmd { it.seek(ms) }
            }
        })

        // envoi seulement depuis le lecteur ; ouverte depuis la notification, la diffusion continue telle quelle
        if (savedInstanceState == null && intent.getBooleanExtra("send", false)) send(Casting.startAt) else showItem()
    }

    override fun onStart() {
        super.onStart()
        poll?.cancel()
        poll = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                refresh()
            }
        }
    }

    override fun onStop() {
        poll?.cancel()
        save()
        super.onStop()
    }

    private fun cmd(f: suspend (Remote) -> Unit) {
        val r = Casting.remote ?: return
        lifecycleScope.launch { f(r) }
    }

    private fun showItem() {
        val it = current
        findViewById<TextView>(R.id.cast_title).text = it.title
        val poster = findViewById<ImageView>(R.id.cast_poster)
        val bg = findViewById<ImageView>(R.id.cast_bg)
        if (it.icon.startsWith("http", true)) {
            poster.load(it.icon) { crossfade(150) }
            bg.load(it.icon)
        }
        val ep = !it.live && items.size > 1
        findViewById<View>(R.id.cast_prev).visibility = if (ep) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cast_next).visibility = if (ep) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cast_prev).alpha = if (Casting.index > 0) 1f else 0.35f
        findViewById<View>(R.id.cast_next).alpha = if (Casting.index < items.size - 1) 1f else 0.35f
        val vod = !it.live
        seek.visibility = if (vod) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.cast_rew).visibility = if (vod) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cast_fwd).visibility = if (vod) View.VISIBLE else View.GONE
        if (!vod) { tvPos.text = "En direct"; tvDur.text = "" }
    }

    /** Envoie l'élément courant au téléviseur. */
    private fun send(startMs: Long) {
        val r = Casting.remote ?: return
        showItem()
        loading = true
        lastPos = startMs
        lastDur = 0
        tvState.text = "Envoi au téléviseur…"
        Presence.start(current.title)
        lifecycleScope.launch {
            val err = r.load(current, startMs)
            loading = false
            if (err != null) {
                tvState.text = err
                android.util.Log.w("KoalaTV", "diffusion : échec ${r.name} · $err")
            } else {
                playing = true
                paintPlay()
                tvState.text = ""
            }
        }
    }

    private suspend fun refresh() {
        val r = Casting.remote ?: return
        if (loading) return
        val st = r.status()
        if (st == null) {
            if (++lost >= 4) tvState.text = "Le téléviseur ne répond plus"
            return
        }
        lost = 0
        if (st.ended) { finished(); return }
        playing = st.playing
        paintPlay()
        if (current.live) return
        if (st.durMs > 0) lastDur = st.durMs
        if (st.posMs > 0 && !dragging) lastPos = st.posMs
        if (lastDur > 0) {
            seek.max = (lastDur / 1000).toInt()
            if (!dragging) seek.progress = (lastPos / 1000).toInt()
            tvDur.text = fmtTime(lastDur)
        }
        if (!dragging) tvPos.text = fmtTime(lastPos)
        if (tvState.text == "Le téléviseur ne répond plus") tvState.text = ""
        if (System.currentTimeMillis() - lastSave > 10_000) save()
    }

    private fun paintPlay() {
        btnPlay.setImageResource(if (playing) androidx.media3.ui.R.drawable.exo_styled_controls_pause else androidx.media3.ui.R.drawable.exo_styled_controls_play)
    }

    private fun jump(d: Long) {
        val ms = (lastPos + d).coerceIn(0, if (lastDur > 0) lastDur - 1000 else Long.MAX_VALUE)
        lastPos = ms
        tvPos.text = fmtTime(ms)
        seek.progress = (ms / 1000).toInt()
        cmd { it.seek(ms) }
    }

    /** Enregistre la reprise, comme le lecteur du téléphone. */
    private fun save() {
        lastSave = System.currentTimeMillis()
        if (items.isEmpty()) return
        val item = current
        val key = item.resumeKey ?: return
        if (item.live || lastPos <= 0) return
        when {
            lastDur > 0 && lastPos >= lastDur - endMargin(lastDur, item.seriesId.isNotEmpty()) -> {
                Prefs.setPos(key, 0)
                History.finish(item)
                Seen.mark(key)
            }
            lastPos > 30_000 -> {
                Prefs.setPos(key, lastPos)
                History.update(item, lastPos, lastDur)
            }
        }
    }

    /** Fin de la vidéo sur le téléviseur : épisode suivant, sinon fin de la diffusion. */
    private fun finished() {
        if (lastDur > 0) lastPos = lastDur
        save()
        if (!current.live && Casting.index < items.size - 1) {
            Casting.index++
            send(Prefs.getPos(current.resumeKey ?: ""))
        } else {
            tvState.text = "Lecture terminée"
            playing = false
            paintPlay()
            Presence.stop()
        }
    }

    private fun episode(d: Int) {
        val i = Casting.index + d
        if (current.live || i !in items.indices) return
        save()
        Casting.index = i
        send(Prefs.getPos(current.resumeKey ?: ""))
    }

    /** Fin de la diffusion ; openHere : la lecture continue sur le téléphone au même endroit. */
    private fun stopCast(openHere: Boolean) {
        save()
        val r = Casting.remote
        Casting.remote = null
        Presence.stop()
        lifecycleScope.launch {
            r?.stop()
            if (openHere) {
                Playback.items = items
                Playback.index = Casting.index
                Playback.startAt = if (current.live) 0 else lastPos
                startActivity(Intent(this@CastActivity, PlayerActivity::class.java))
            }
            finish()
        }
    }

    /** Retour : arrêter, ou laisser le téléviseur jouer. */
    private fun leave() {
        AlertDialog.Builder(this)
            .setTitle("Arrêter la diffusion ?")
            .setMessage("Le téléviseur peut continuer la lecture sans le téléphone.")
            .setPositiveButton("Arrêter") { _, _ -> stopCast(openHere = false) }
            .setNegativeButton("Laisser jouer") { _, _ -> save(); finish() }
            .show()
    }
}
