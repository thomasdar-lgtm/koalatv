package com.koala.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var pv: PlayerView
    private lateinit var overlay: View
    private lateinit var ovTitle: TextView
    private lateinit var ovPos: TextView
    private lateinit var ovEpg: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNumber: TextView
    private lateinit var gestures: GestureDetector

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var retries = 0
    private var lastPos = 0L
    private var epgJob: Job? = null
    private var swiped = false
    private var prevList: List<PlayItem>? = null
    private var prevIndex = -1
    private var digits = ""
    private var okDown = false
    private var okLong = false
    private val digitsRunnable = Runnable { commitDigits() }

    private val items: List<PlayItem> get() = Playback.items
    private val current: PlayItem get() = items[index]

    private val hideOverlay = Runnable { if (!pv.isControllerFullyVisible) overlay.visibility = View.GONE }
    private val saveLoop = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }
    private val retryRunnable = Runnable {
        val pos = lastPos
        playCurrent(askResume = false)
        if (!current.live && pos > 0) player?.seekTo(pos)
    }
    private val zapRunnable = Runnable { playCurrent(askResume = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Playback.items.isEmpty()) {
            finish(); return
        }
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        index = Playback.index.coerceIn(0, items.size - 1)
        pv = findViewById(R.id.player_view)
        overlay = findViewById(R.id.overlay)
        ovTitle = findViewById(R.id.ov_title)
        ovPos = findViewById(R.id.ov_pos)
        ovEpg = findViewById(R.id.ov_epg)
        tvStatus = findViewById(R.id.tv_status)
        tvNumber = findViewById(R.id.tv_number)
        pv.requestFocus()

        // Infos de la chaîne visibles tant que les commandes sont affichées
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) showInfo(keep = true)
            else {
                handler.removeCallbacks(hideOverlay)
                handler.postDelayed(hideOverlay, 1500)
            }
        })

        // Mobile / tablette : glisser vers le haut = chaîne suivante, vers le bas = précédente
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (items.isEmpty() || !current.live) return false
                previousChannel()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (items.isEmpty() || !current.live || items.size < 2) return false
                if (abs(vy) < 900 || abs(vy) < abs(vx) * 1.5f) return false
                swiped = true
                zap(if (vy < 0) 1 else -1)
                return true
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pv.isControllerFullyVisible) pv.hideController() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (Playback.items.isNotEmpty() && player == null && ::pv.isInitialized) initPlayer()
    }

    override fun onStop() {
        saveProgress()
        releasePlayer()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::gestures.isInitialized) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) swiped = false
        gestures.onTouchEvent(ev)
        if (swiped && ev.actionMasked == MotionEvent.ACTION_UP) {
            // le glissement ne doit pas aussi afficher / masquer les commandes
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun initPlayer() {
        val http = OkHttpDataSource.Factory(Xtream.http).setUserAgent(Prefs.userAgent)
        val dataSource = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource, extractors)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
            .build()
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(trackSelector.buildUponParameters().setPreferredAudioLanguage("fr").build())

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        p.addListener(listener)
        pv.player = p
        player = p
        val start = Playback.startAt
        Playback.startAt = -1
        if (start >= 0) {
            playCurrent(askResume = false)
            if (start > 0) p.seekTo(start)
        } else {
            playCurrent(askResume = true)
        }
        handler.removeCallbacks(saveLoop)
        handler.postDelayed(saveLoop, 10_000)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(saveLoop)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(zapRunnable)
        epgJob?.cancel()
        player?.removeListener(listener)
        player?.release()
        player = null
        if (::pv.isInitialized) pv.player = null
    }

    private fun playCurrent(askResume: Boolean) {
        val p = player ?: return
        val item = current
        tvStatus.visibility = View.GONE
        val b = MediaItem.Builder()
            .setUri(item.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        when {
            item.url.endsWith(".m3u8", true) -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            item.url.endsWith(".ts", true) -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        p.setMediaItem(b.build())
        p.prepare()

        val saved = if (!item.live && item.resumeKey != null) Prefs.getPos(item.resumeKey) else 0L
        if (askResume && saved > 0) {
            p.seekTo(saved)
            p.playWhenReady = false
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf("Reprendre à ${fmtTime(saved)}", "Reprendre au début")) { _, which ->
                    if (which == 1) player?.seekTo(0)
                    player?.playWhenReady = true
                }
                .setOnCancelListener { player?.playWhenReady = true }
                .show()
        } else {
            p.playWhenReady = true
        }
        showInfo()
    }

    private fun showInfo(keep: Boolean = false) {
        val item = current
        ovTitle.text = item.title
        ovPos.text = when {
            !item.live -> ""
            item.num > 0 -> "Chaîne ${item.num}"
            items.size > 1 -> "${index + 1} / ${items.size}"
            else -> ""
        }
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        if (!keep) handler.postDelayed(hideOverlay, 5_000)
        val sid = item.streamId
        if (item.live && sid != null) {
            if (epgJob?.isActive != true || ovEpg.tag != sid) {
                ovEpg.visibility = View.GONE
                ovEpg.tag = sid
                epgJob?.cancel()
                epgJob = lifecycleScope.launch {
                    val txt = Xtream.shortEpg(sid, 2).joinToString("\n") { p -> Epg.hm(p.start) + "  " + p.title }
                    if (current.streamId == sid && txt.isNotEmpty()) {
                        ovEpg.text = formatEpg(txt)
                        ovEpg.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            ovEpg.visibility = View.GONE
        }
    }

    /** Met l'heure de chaque programme en vert. */
    private fun formatEpg(txt: String): CharSequence {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val sb = SpannableStringBuilder()
        txt.lines().forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line)
            val sp = line.indexOf("  ")
            if (sp in 1..5) sb.setSpan(ForegroundColorSpan(accent), start, start + sp, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    retries = 0
                    tvStatus.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    if (current.live) scheduleRetry() else next()
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (current.live || retries < 3) scheduleRetry() else showFatal(error)
        }
    }

    private fun scheduleRetry() {
        retries++
        val delay = (1_000L * retries).coerceAtMost(5_000L)
        tvStatus.text = if (retries > 2) "Reconnexion en cours ($retries)" else "Reconnexion…"
        tvStatus.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun showFatal(error: PlaybackException) {
        tvStatus.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("Lecture impossible")
            .setMessage("Ce contenu ne peut pas être lu par le lecteur intégré.\n(${error.errorCodeName})")
            .setPositiveButton("Lecteur externe") { _, _ ->
                if (Nav.openExternal(this, current)) finish()
            }
            .setNeutralButton("Réessayer") { _, _ ->
                retries = 0
                retryRunnable.run()
            }
            .setNegativeButton("Fermer") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun next() {
        current.resumeKey?.let {
            Prefs.setPos(it, 0)
            History.remove(it)
        }
        if (index < items.size - 1) {
            index++
            Playback.index = index
            retries = 0
            lastPos = 0
            playCurrent(askResume = true)
        } else {
            finish()
        }
    }

    /** Changement de chaîne : le titre s'affiche tout de suite, le flux démarre quand on arrête de zapper. */
    private fun switchTo(list: List<PlayItem>, i: Int) {
        if (list.isEmpty() || (list === items && i == index)) return
        prevList = items
        prevIndex = index
        Playback.items = list
        index = i.coerceIn(0, list.size - 1)
        Playback.index = index
        retries = 0
        lastPos = 0
        handler.removeCallbacks(retryRunnable)
        tvStatus.visibility = View.GONE
        player?.stop()
        showInfo()
        handler.removeCallbacks(zapRunnable)
        handler.postDelayed(zapRunnable, 350)
    }

    private fun zap(delta: Int) {
        if (items.size <= 1) return
        switchTo(items, (index + delta + items.size) % items.size)
    }

    /** Retour à la chaîne regardée juste avant. */
    private fun previousChannel() {
        val l = prevList
        if (l == null || prevIndex !in l.indices) {
            toast("Pas encore de chaîne précédente")
            return
        }
        switchTo(l, prevIndex)
    }

    /** Saisie d'un numéro de chaîne à la télécommande. */
    private fun typeDigit(d: Int) {
        if (digits.length >= 4) digits = ""
        digits += d
        tvNumber.text = digits
        tvNumber.visibility = View.VISIBLE
        handler.removeCallbacks(digitsRunnable)
        handler.postDelayed(digitsRunnable, 2000)
    }

    private fun commitDigits() {
        handler.removeCallbacks(digitsRunnable)
        val n = digits.toIntOrNull()
        digits = ""
        tvNumber.visibility = View.GONE
        if (n == null || n <= 0) return
        val here = items.indexOfFirst { it.num == n }
        if (here >= 0) {
            switchTo(items, here)
            return
        }
        // pas dans la catégorie en cours : on cherche dans toutes les chaînes
        val all = Lists.peek(Kind.LIVE)?.items?.filter { it.num > 0 }?.sortedBy { it.num }
        val at = all?.indexOfFirst { it.num == n } ?: -1
        if (all == null || at < 0) {
            toast("Chaîne $n introuvable")
            return
        }
        switchTo(all.map { liveItem(it) }, at)
    }

    private fun saveProgress() {
        val p = player ?: return
        if (items.isEmpty()) return
        val item = current
        if (item.live) return
        val pos = p.currentPosition
        if (pos > 0) lastPos = pos
        val key = item.resumeKey ?: return
        val dur = p.duration
        when {
            dur > 0 && pos >= dur - 120_000 -> {
                Prefs.setPos(key, 0)
                History.remove(key)
            }
            pos > 30_000 -> {
                Prefs.setPos(key, pos)
                History.update(item, pos, dur)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::pv.isInitialized && items.isNotEmpty()) {
            val controls = pv.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (current.live) { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (current.live) { zap(-1); return true }
            }
            if (current.live) {
                val d = digitOf(event.keyCode)
                if (d >= 0) { typeDigit(d); return true }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_PROG_BLUE -> { previousChannel(); return true }
                }
            }
            if (!controls) {
                if (current.live) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            // appui court : commandes (ou valide le numéro) · appui long : chaîne précédente
                            if (event.repeatCount == 0) {
                                okDown = true
                                okLong = false
                            } else if (okDown && !okLong) {
                                okLong = true
                                previousChannel()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_INFO -> { pv.showController(); return true }
                    }
                } else {
                    val p = player
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (p != null) {
                            p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); pv.showController(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (p != null) {
                            p.seekTo(p.currentPosition + 30_000); pv.showController(); return true
                        }
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP && okDown &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            okDown = false
            if (!okLong) {
                if (digits.isNotEmpty()) commitDigits() else pv.showController()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun digitOf(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
