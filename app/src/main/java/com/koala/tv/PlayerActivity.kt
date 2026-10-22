package com.koala.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

class PlayerActivity : AppCompatActivity() {

    private lateinit var pv: PlayerView
    private lateinit var overlay: View
    private lateinit var ovTitle: TextView
    private lateinit var ovEpg: TextView
    private lateinit var tvStatus: TextView

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var retries = 0
    private var lastPos = 0L
    private var epgJob: Job? = null

    private val items: List<PlayItem> get() = Playback.items
    private val current: PlayItem get() = items[index]

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
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
        ovEpg = findViewById(R.id.ov_epg)
        tvStatus = findViewById(R.id.tv_status)
        pv.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pv.isControllerFullyVisible) pv.hideController() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (Playback.items.isNotEmpty() && player == null) initPlayer()
    }

    override fun onStop() {
        saveProgress()
        releasePlayer()
        super.onStop()
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
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
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
        playCurrent(askResume = true)
        handler.removeCallbacks(saveLoop)
        handler.postDelayed(saveLoop, 10_000)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(saveLoop)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(hideOverlay)
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
                .setItems(arrayOf("▶ Reprendre à ${fmtTime(saved)}", "⟲ Depuis le début")) { _, which ->
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

    private fun showInfo() {
        val item = current
        ovTitle.text = if (item.live && items.size > 1) "${index + 1}/${items.size}  ${item.title}" else item.title
        ovEpg.text = ""
        ovEpg.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5_000)
        epgJob?.cancel()
        val sid = item.streamId
        if (item.live && sid != null) {
            epgJob = lifecycleScope.launch {
                val t = Xtream.nowPlaying(sid)
                if (t.isNotEmpty()) {
                    ovEpg.text = t
                    ovEpg.visibility = View.VISIBLE
                }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    retries = 0
                    tvStatus.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    if (current.live) scheduleRetry("Flux interrompu, reconnexion…") else next()
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
            if (current.live || retries < 3) {
                scheduleRetry("Problème de flux, nouvelle tentative…")
            } else {
                showFatal(error)
            }
        }
    }

    private fun scheduleRetry(msg: String) {
        retries++
        val delay = (1_000L * retries).coerceAtMost(5_000L)
        tvStatus.text = if (retries > 1) "$msg ($retries)" else msg
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
        current.resumeKey?.let { Prefs.setPos(it, 0) }
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

    private fun zap(delta: Int) {
        if (items.size <= 1) return
        index = (index + delta + items.size) % items.size
        Playback.index = index
        retries = 0
        lastPos = 0
        handler.removeCallbacks(retryRunnable)
        playCurrent(askResume = false)
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
            dur > 0 && pos >= dur - 120_000 -> Prefs.setPos(key, 0)
            pos > 30_000 -> Prefs.setPos(key, pos)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::pv.isInitialized && items.isNotEmpty()) {
            val controls = pv.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (current.live) { zap(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (current.live) { zap(1); return true }
            }
            if (!controls) {
                if (current.live) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                            showInfo(); pv.showController(); return true
                        }
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
        return super.dispatchKeyEvent(event)
    }
}
