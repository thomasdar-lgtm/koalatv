package com.koala.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Diffusion DLNA contre un faux téléviseur UPnP, et écran de télécommande. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastTest {

    /** Faux téléviseur : description, puis commandes AVTransport (état simulé). */
    private class FakeTv {
        val calls = ArrayList<String>()
        var uri = ""
        var meta = ""
        var state = "NO_MEDIA_PRESENT"
        var pos = "0:00:00"
        val srv = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    if (r.path == "/desc.xml") return MockResponse().setBody(
                        """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                        <friendlyName>[LG] webOS TV OLED65</friendlyName><serviceList>
                        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
                        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
                        </serviceList></device></root>""",
                    )
                    val action = r.getHeader("SOAPACTION")!!.substringAfter('#').trim('"')
                    val body = r.body.readUtf8()
                    calls.add(action)
                    when (action) {
                        "SetAVTransportURI" -> { uri = Dlna.tag(body, "CurrentURI"); meta = Dlna.tag(body, "CurrentURIMetaData"); state = "STOPPED" }
                        "Play" -> state = "PLAYING"
                        "Pause" -> state = "PAUSED_PLAYBACK"
                        "Stop" -> state = "STOPPED"
                        "Seek" -> pos = Dlna.tag(body, "Target")
                    }
                    val out = when (action) {
                        "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                        "GetPositionInfo" -> "<TrackDuration>0:52:10</TrackDuration><RelTime>$pos</RelTime>"
                        else -> ""
                    }
                    return MockResponse().setBody("<s:Envelope><s:Body><u:${action}Response>$out</u:${action}Response></s:Body></s:Envelope>")
                }
            }
            start()
        }
    }

    @Test
    fun dlnaFlow() = runBlocking {
        val tv = FakeTv()
        val r = Dlna.describe(tv.srv.url("/desc.xml").toString())
        assertNotNull(r)
        assertEquals("[LG] webOS TV OLED65", r!!.name)
        assertEquals(tv.srv.url("/upnp/control/AVTransport1").toString(), r.control)

        val remote = DlnaRemote(r)
        val item = PlayItem("Game of Thrones · S6 É1", "http://srv.example/series/u/p/1234.mkv", false, "e1234", "1234", seriesId = "9", seriesName = "Game of Thrones")
        val err = remote.load(item, 754_000)
        assertNull(err)
        println("commandes : " + tv.calls)
        assertEquals("http://srv.example/series/u/p/1234.mkv", tv.uri)
        assertTrue("type MKV annoncé", Dlna.tag(tv.meta.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""), "dc:title").isNotEmpty() || tv.meta.contains("video/x-matroska"))
        assertTrue(tv.meta.contains("video/x-matroska"))
        assertEquals("0:12:34", tv.pos)

        val st = remote.status()!!
        assertEquals(754_000L, st.posMs)
        assertEquals(3_130_000L, st.durMs)
        assertTrue(st.playing)
        remote.pause()
        assertTrue(!remote.status()!!.playing)
        remote.stop()
        // arrêt après lecture = fin de vidéo
        assertTrue(remote.status()!!.ended)
        tv.srv.shutdown()
    }

    @Test
    fun liveUrls() {
        val live = PlayItem("TF1", "http://srv/live/u/p/12.ts", true, null, "12")
        assertEquals("http://srv/live/u/p/12.m3u8", castUrl(live, hls = true))
        assertEquals("http://srv/live/u/p/12.ts", castUrl(live, hls = false))
        assertEquals("video/mp2t", castMime(castUrl(live, false)))
        assertEquals("application/x-mpegURL", castMime(castUrl(live, true)))
    }

    /** Écran de télécommande, avec un téléviseur factice. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun remoteScreen() {
        val app = ApplicationProvider.getApplicationContext<App>()
        Casting.remote = object : Remote {
            override val name = "[LG] webOS TV OLED65"
            override suspend fun load(item: PlayItem, startMs: Long): String? = null
            override suspend fun play() {}
            override suspend fun pause() {}
            override suspend fun seek(ms: Long) {}
            override suspend fun stop() {}
            override suspend fun status() = RemoteStatus(754_000, 3_130_000, true, false, false)
        }
        Casting.items = (1..3).map { PlayItem("Game of Thrones · S6 É$it", "http://x/$it.mkv", false, "e$it", "$it", seriesId = "9", seriesName = "Game of Thrones") }
        Casting.index = 0
        Casting.startAt = 0
        val sc = ActivityScenario.launch<CastActivity>(android.content.Intent(app, CastActivity::class.java).putExtra("send", true))
        repeat(15) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            Thread.sleep(50)
        }
        sc.onActivity { a ->
            val pos = a.findViewById<android.widget.TextView>(R.id.cast_pos).text.toString()
            println("télécommande : position $pos")
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "cast_phone.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals("12:34", pos)
        }
        sc.close()
    }
}
