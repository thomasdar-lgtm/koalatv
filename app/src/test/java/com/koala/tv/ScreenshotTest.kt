package com.koala.tv

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures d'écran de contrôle (lancées par la compilation sur GitHub) : l'accueil tel qu'il s'affiche
 * sur un boîtier Android TV 1080p et sur un téléphone, avec le vrai code de l'appli.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private fun login(tv: Boolean, resume: Boolean) {
        val app = ApplicationProvider.getApplicationContext<App>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, tv)
        Prefs.server = "http://127.0.0.1:9"
        Prefs.user = "demo"
        Prefs.pass = "demo"
        if (resume) {
            History.update(PlayItem("Film exemple", "http://127.0.0.1:9/movie/1.mp4", false, "m1", "1"), 600_000, 6_000_000)
            History.update(PlayItem("Série test – Épisode 3", "http://127.0.0.1:9/series/3.mp4", false, "e3", "3", "", "9", "Série test"), 300_000, 2_400_000)
        }
    }

    private fun shoot(name: String, remote: Boolean = false) {
        // télécommande : pas de mode tactile, la tuile choisie est mise en avant
        if (remote) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val sc = ActivityScenario.launch(HomeActivity::class.java)
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(150)
        }
        if (remote) {
            // une vraie touche de télécommande : sortie du mode tactile, comme sur le boîtier
            sc.onActivity { a ->
                for (k in intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                    a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, k))
                    a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, k))
                }
            }
            repeat(6) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(150)
            }
        }
        sc.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        sc.close()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun homeTvResume() {
        login(tv = true, resume = true); shoot("home_tv_resume", remote = true)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun homeTv() {
        History.clear()
        login(tv = true, resume = false); shoot("home_tv", remote = true)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun homePhone() {
        login(tv = false, resume = true); shoot("home_phone")
    }
}
