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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** PC de la maison : adresse et bibliothèque lues dans la base, PC joignable, demandes envoyées, écran. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RelayTest {

    private class Fake {
        val writes = ArrayList<String>()
        val pc = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest) =
                    if (r.path == "/ping?t=tok123") MockResponse().setBody("{\"ok\":1}") else MockResponse().setResponseCode(403)
            }
            start()
        }
        val fb = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val p = r.path!!
                    if (r.method == "PUT") { synchronized(writes) { writes.add(p.substringAfterLast("/h/").substringAfter('/') + " " + r.body.readUtf8()) }; return MockResponse().setBody("{}") }
                    return when {
                        p.endsWith("/r.json") -> MockResponse().setBody(
                            JSONObject().put("n", "PC LAPTOP-THOMAS").put("port", pc.port).put("tok", "tok123")
                                .put("ip", org.json.JSONArray().put("10.255.255.1").put("127.0.0.1")).put("at", System.currentTimeMillis()).toString(),
                        )
                        p.endsWith("/rl.json") -> MockResponse().setBody(
                            """{"e1":{"t":"Game of Thrones · S6 É1","st":"fini","pct":100,"tot":4200000000,"ext":"mkv","sid":"9","sn":"Game of Thrones","at":1},
                               "e2":{"t":"Game of Thrones · S6 É2","st":"cours","pct":35,"tot":4000000000,"sp":12582912,"ext":"mkv","sid":"9","sn":"Game of Thrones","at":2},
                               "m3":{"t":"La ligne rouge","st":"attente","pct":0,"ext":"mkv","at":3}}""",
                        )
                        else -> MockResponse().setBody("null")
                    }
                }
            }
            start()
        }
    }

    private fun login(): Fake {
        ApplicationProvider.getApplicationContext<App>()
        val f = Fake()
        Sync.DB = f.fb.url("/").toString().trimEnd('/')
        Prefs.server = "http://srv.example:8080"; Prefs.user = "thomas"; Prefs.pass = "secret"
        return f
    }

    @Test
    fun readsPcAndSendsRequests() = runBlocking {
        val f = login()
        Relay.refresh(force = true)
        assertTrue(Relay.known)
        assertEquals("http://127.0.0.1:${f.pc.port}", Relay.base)
        assertEquals("http://127.0.0.1:${f.pc.port}/v/e1.mkv?t=tok123", Relay.playUrl("e1"))
        assertTrue(Relay.playUrl("e2")!!.contains("/v/e2.mkv"))
        assertNull("pas encore commencé", Relay.playUrl("m3"))
        println("états : " + Relay.all().map { Relay.stateText(it) })
        val n = Relay.send(listOf(PlayItem("Game of Thrones · S6 É3", "http://srv.example:8080/series/thomas/secret/1003.mkv", false, "e1003", "1003", "", "9", "Game of Thrones")))
        assertEquals(1, n)
        Thread.sleep(500)
        val w = synchronized(f.writes) { f.writes.toList() }
        println("écritures : $w")
        assertTrue(w.any { it.startsWith("q/e1003.json") && it.contains("\"kind\":\"series\"") && it.contains("\"id\":\"1003\"") && it.contains("\"ext\":\"mkv\"") })
        assertTrue("pas d'identifiants dans la demande", w.none { it.contains("secret") })
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun downloadsScreenWithPc() {
        login()
        runBlocking { Relay.refresh(force = true) }
        val sc = ActivityScenario.launch(DownloadsActivity::class.java)
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); Thread.sleep(50) }
        sc.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File("build/shots").mkdirs()
            File("build/shots/downloads_pc_phone.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        sc.close()
    }
}
