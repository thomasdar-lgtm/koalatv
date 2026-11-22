package com.koala.tv

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Lecteur Android : état du chargement affiché, OK recharge après 8 s, bouton « Recharger » dans les commandes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ReloadTest {

    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun find(v: View, desc: String): View? {
        if (v.contentDescription?.toString() == desc) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), desc)?.let { return it }
        return null
    }

    @Test
    fun loadPanelAndReload() {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val reqs = java.util.concurrent.atomic.AtomicInteger()
        // serveur qui accepte la connexion puis n'envoie jamais rien
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    reqs.incrementAndGet()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            start()
        }
        Playback.items = listOf(PlayItem("Film bloqué", srv.url("/movie/u/p/7.mp4").toString(), false, "m7", "7"))
        Playback.index = 0
        Playback.startAt = 0
        val sc = ActivityScenario.launch(PlayerActivity::class.java)
        repeat(12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            Thread.sleep(150)
        }
        sc.onActivity { a ->
            val panel = a.findViewById<View>(R.id.load_panel)
            val msg = a.findViewById<TextView>(R.id.load_msg).text
            val det = a.findViewById<TextView>(R.id.load_det).text
            println("panneau : visible=${panel.visibility == View.VISIBLE} · $msg · $det · requêtes=${reqs.get()}")
            shot(a.window.decorView, "reload-panel")
            assertTrue("panneau de chargement affiché", panel.visibility == View.VISIBLE)
            assertTrue("OK pour recharger proposé", det.contains("OK pour recharger"))
        }
        val before = reqs.get()
        sc.onActivity { a ->
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        }
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            Thread.sleep(150)
        }
        println("requêtes avant OK : $before · après : ${reqs.get()}")
        assertTrue("OK relance la lecture (nouvelle requête)", reqs.get() > before)
        sc.onActivity { a ->
            val pv = a.findViewById<PlayerView>(R.id.player_view)
            pv.showController()
            shadowOf(Looper.getMainLooper()).idle()
            val b = find(pv, "Recharger")
            println("bouton Recharger : " + (b != null) + " visible=" + (b?.visibility == View.VISIBLE) + " taille=" + b?.width + "x" + b?.height)
            shot(a.window.decorView, "reload-button")
            assertNotNull("bouton Recharger dans les commandes", b)
            val n = reqs.get()
            b!!.performClick()
            repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); Thread.sleep(150) }
            println("clic sur Recharger : requêtes $n -> ${reqs.get()}")
        }
        sc.close()
        srv.shutdown()
    }
}
