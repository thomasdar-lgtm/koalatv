package com.koala.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Téléchargements : faux serveur avec reprise (Range), coupure en cours de route, lecture pendant le téléchargement. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadTest {

    private val data = ByteArray(6_000_000) { (it * 31 + it / 7).toByte() }

    /** Serveur : redirection comme Xtream, plages d'octets, coupe une fois au milieu, débit réglable. */
    private class FakeServer(val data: ByteArray, var cutOnce: Boolean, val throttleKbps: Long = 0) {
        val ranges = ArrayList<String>()
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    if (r.path!!.startsWith("/movie/")) return MockResponse().setResponseCode(302).setHeader("Location", "/real/file.mkv")
                    val m = Regex("bytes=(\\d+)-(\\d*)").find(r.getHeader("Range") ?: "")!!
                    val a = m.groupValues[1].toInt()
                    val b = (m.groupValues[2].toIntOrNull() ?: (data.size - 1)).coerceAtMost(data.size - 1)
                    synchronized(ranges) { ranges.add("$a-$b") }
                    val body = Buffer().write(data, a, b - a + 1)
                    val resp = MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $a-$b/${data.size}").setBody(body)
                    if (throttleKbps > 0) resp.throttleBody(throttleKbps * 1024 / 10, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    // une coupure au milieu du premier gros transfert
                    if (cutOnce && b - a > 1_000_000) { cutOnce = false; resp.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
                    return resp
                }
            }
            start()
        }
    }

    private fun setup(): App {
        val app = ApplicationProvider.getApplicationContext<App>()
        Downloads.init(app)
        for (d in Downloads.all()) Downloads.remove(d.key)
        return app
    }

    @Test
    fun downloadsWithResumeAfterCut() {
        setup()
        val s = FakeServer(data, cutOnce = true)
        val item = PlayItem("Film test", s.srv.url("/movie/u/p/42.mkv").toString(), false, "m42", "42")
        Downloads.Worker.run(Downloads.Dl(item.resumeKey!!, item.title, item.url, "", "", "", File(Downloads.dir(), "t.mkv").path).also {
            synchronizedAdd(it)
        }) {}
        val d = Downloads.get("m42")!!
        println("plages demandées : " + s.ranges + " · état " + d.state + " " + d.error)
        assertEquals(Downloads.State.DONE, d.state)
        assertArrayEquals(data, d.file.readBytes())
        // la fin du fichier est demandée avant le début (index des MKV)
        val firstBig = s.ranges.first { !it.startsWith("0-0") }
        assertTrue("fin d'abord : $firstBig", firstBig.endsWith("-${data.size - 1}") && !firstBig.startsWith("0-"))
        s.srv.shutdown()
    }

    @Test
    fun readWhileDownloading() {
        setup()
        val s = FakeServer(data, cutOnce = false, throttleKbps = 4000)
        val item = PlayItem("Épisode test", s.srv.url("/movie/u/p/43.mkv").toString(), false, "e43", "43")
        val d = Downloads.Dl(item.resumeKey!!, item.title, item.url, "", "", "", File(Downloads.dir(), "t2.mkv").path)
        synchronizedAdd(d)
        val t = Thread { Downloads.Worker.run(d) {} }
        t.start()
        // le lecteur lit tout le fichier pendant qu'il arrive
        val src = GrowingFileDataSource()
        val len = src.open(DataSpec(Uri.parse("ktvdl://e43")))
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var firstAt = -1L
        val t0 = System.currentTimeMillis()
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n < 0) break
            if (firstAt < 0) firstAt = System.currentTimeMillis() - t0
            out.write(buf, 0, n)
        }
        src.close()
        t.join(60_000)
        println("lecture pendant le téléchargement : ${out.size()} octets sur $len, premiers octets après $firstAt ms, fin après ${System.currentTimeMillis() - t0} ms")
        assertArrayEquals(data, out.toByteArray())
        assertEquals(Downloads.State.DONE, d.state)
        s.srv.shutdown()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun downloadsScreen() {
        val app = setup()
        val a = Downloads.Dl("e1", "Game of Thrones · S6 É1 · La Femme rouge", "http://x/1.mkv", "", "9", "Game of Thrones", File(Downloads.dir(), "a").path, total = 4_200_000_000, done = 4_200_000_000, tailFrom = 4_100_000_000, tailDone = true, state = Downloads.State.DONE)
        val b = Downloads.Dl("e2", "Game of Thrones · S6 É2 · Chez moi", "http://x/2.mkv", "", "9", "Game of Thrones", File(Downloads.dir(), "b").path, total = 4_000_000_000, done = 1_300_000_000, tailFrom = 3_900_000_000, tailDone = true, state = Downloads.State.RUNNING)
        val c = Downloads.Dl("m3", "Dune : deuxième partie", "http://x/3.mkv", "", "", "", File(Downloads.dir(), "c").path)
        listOf(a, b, c).forEach { synchronizedAdd(it) }
        val sc = ActivityScenario.launch(DownloadsActivity::class.java)
        shadowOf(Looper.getMainLooper()).idle()
        sc.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File("build/shots").mkdirs()
            File("build/shots/downloads_tv.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("états : " + listOf(a, b, c).map { DownloadsActivity.stateText(it) })
        }
        sc.close()
    }

    /** Ajout direct d'une entrée préparée (sans lancer le service). */
    private fun synchronizedAdd(d: Downloads.Dl) {
        val f = Downloads::class.java.getDeclaredField("list").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val l = f.get(Downloads) as ArrayList<Downloads.Dl>
        synchronized(l) { l.add(d) }
    }
}
