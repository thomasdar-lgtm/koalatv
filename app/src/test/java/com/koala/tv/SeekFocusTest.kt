package com.koala.tv

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Avance à la télécommande : après le premier appui, le curseur doit être sur la barre (pas sur Pause). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SeekFocusTest {

    /** Lecteur factice : une vidéo d'une heure en lecture. */
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var pos = 600_000L
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(MediaItemData.Builder("v").setMediaItem(MediaItem.fromUri("http://x/v.mp4")).setDurationUs(3_600_000_000L).setIsSeekable(true).build()))
            .setContentPositionMs { pos }
            .build()
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            pos = positionMs
            return Futures.immediateVoidFuture()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Test
    fun seekThenStaysOnBar() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pv = PlayerView(act)
        act.setContentView(pv)
        val fake = FakePlayer()
        pv.player = fake
        pv.controllerShowTimeoutMs = 5000
        pv.hideController()
        shadowOf(Looper.getMainLooper()).idle()
        val bar = pv.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        bar.setKeyTimeIncrement(10_000)
        // premier appui (commandes cachées) : comme dans PlayerActivity.showOnBar()
        fake.seekTo(fake.pos + 10_000)
        pv.showController()
        bar.post { bar.requestFocus() }
        shadowOf(Looper.getMainLooper()).idle()
        println("focus après 1er appui : " + act.currentFocus?.javaClass?.simpleName + " id=" + act.currentFocus?.id)
        assertTrue("la barre doit avoir le focus", bar.isFocused)
        // second appui : la barre avance encore de 10 s, le focus ne bouge pas
        act.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        act.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        println("position après 2e appui : " + fake.pos + " · focus barre : " + bar.isFocused)
        assertTrue("le focus reste sur la barre", bar.isFocused)
        assertEquals(620_000L, fake.pos)
    }
}
