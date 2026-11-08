package com.koala.tv

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.images.WebImage
import android.net.Uri
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Diffusion vers un téléviseur qui n'a pas l'appli :
 *  - Chromecast et téléviseurs Google TV / Android TV (lecteur de base de Google, rien à installer) ;
 *  - téléviseurs compatibles DLNA (LG, Samsung, Sony, Philips…), pilotés en UPnP.
 * Le téléviseur charge lui-même la vidéo : le téléphone ne fait que la télécommande.
 */

/** État de la lecture sur le téléviseur. */
data class RemoteStatus(val posMs: Long, val durMs: Long, val playing: Boolean, val ended: Boolean, val idle: Boolean)

/** Un téléviseur piloté à distance. */
interface Remote {
    val name: String
    /** Envoie la vidéo ; onDone(null) si c'est parti, onDone(message) sinon. */
    suspend fun load(item: PlayItem, startMs: Long): String?
    suspend fun play()
    suspend fun pause()
    suspend fun seek(ms: Long)
    suspend fun stop()
    suspend fun status(): RemoteStatus?
}

/** Un téléviseur trouvé sur le Wi-Fi. */
class Device(val name: String, val kind: String, val connect: suspend () -> Remote?)

/** Adresse et type de la vidéo pour un téléviseur : le direct en HLS pour Chromecast, en MPEG-TS pour le DLNA. */
fun castUrl(item: PlayItem, hls: Boolean): String {
    if (!item.live || item.streamId == null) return item.url
    return item.url.replace(Regex("\\.(ts|m3u8)(\\?.*)?$"), if (hls) ".m3u8" else ".ts")
}

fun castMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
    "mkv" -> "video/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "ts" -> "video/mp2t"
    "m3u8" -> "application/x-mpegURL"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    else -> "video/mp4"
}

/** Diffusion en cours (partagée entre le lecteur et l'écran de télécommande). */
object Casting {
    var remote: Remote? = null
    var items: List<PlayItem> = emptyList()
    var index = 0
    var startAt = 0L
}

// ---------------------------------------------------------------------------------------------
// Chromecast
// ---------------------------------------------------------------------------------------------

/** Réglages du Chromecast : lecteur de base de Google, notification qui ramène à la télécommande. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder().setTargetActivityClassName(CastActivity::class.java.name).build()
        val media = CastMediaOptions.Builder().setNotificationOptions(notif).build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Chromecast {
    /** null si les services Google sont absents (Fire TV, téléphones sans Google). */
    fun context(c: Context): CastContext? = try {
        CastContext.getSharedInstance(c.applicationContext)
    } catch (e: Exception) {
        null
    } catch (e: Error) {
        null
    }
}

/** Télécommande d'un Chromecast (session ouverte par la sélection de l'appareil). */
class CastRemote(override val name: String, private val session: CastSession) : Remote {
    private val client get() = session.remoteMediaClient

    override suspend fun load(item: PlayItem, startMs: Long): String? = withContext(Dispatchers.Main) {
        val c = client ?: return@withContext "Le Chromecast ne répond pas"
        val url = castUrl(item, hls = true)
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            if (item.seriesName.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, item.seriesName)
            if (item.icon.startsWith("http")) addImage(WebImage(Uri.parse(item.icon)))
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (item.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castMime(url))
            .setMetadata(meta)
            .build()
        val req = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .setCurrentTime(if (item.live) 0 else startMs).build()
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            c.load(req).setResultCallback { r ->
                if (cont.isActive) cont.resumeWith(Result.success(if (r.status.isSuccess) null else "Le Chromecast n'a pas pu lire cette vidéo"))
            }
        }
    }

    override suspend fun play() = withContext(Dispatchers.Main) { client?.play(); Unit }
    override suspend fun pause() = withContext(Dispatchers.Main) { client?.pause(); Unit }
    override suspend fun seek(ms: Long) = withContext(Dispatchers.Main) {
        client?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(ms).build()); Unit
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        try {
            client?.stop()
            Chromecast.context(App.ctx)?.sessionManager?.endCurrentSession(true)
        } catch (e: Exception) { /* déjà fermé */ }
        Unit
    }

    override suspend fun status(): RemoteStatus? = withContext(Dispatchers.Main) {
        if (!session.isConnected) return@withContext null
        val c = client ?: return@withContext null
        val st = c.playerState
        RemoteStatus(
            posMs = c.approximateStreamPosition,
            durMs = c.streamDuration.coerceAtLeast(0),
            playing = st == MediaStatus.PLAYER_STATE_PLAYING || st == MediaStatus.PLAYER_STATE_BUFFERING,
            ended = st == MediaStatus.PLAYER_STATE_IDLE && c.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            idle = st == MediaStatus.PLAYER_STATE_IDLE || st == MediaStatus.PLAYER_STATE_UNKNOWN,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// DLNA / UPnP
// ---------------------------------------------------------------------------------------------

object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    class Renderer(val name: String, val control: String, val location: String)

    /** Recherche des téléviseurs (SSDP) pendant `ms` ; found() est appelé pour chaque adresse de description. */
    fun search(c: Context, ms: Long, found: (String) -> Unit) {
        val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("koalatv-dlna")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            null
        }
        val seen = HashSet<String>()
        try {
            MulticastSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AVT)) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                    repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                }
                val end = System.currentTimeMillis() + ms
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: continue
                    if (seen.add(loc)) found(loc)
                }
            }
        } catch (e: Exception) {
            // pas de Wi-Fi, réseau qui bloque la recherche : aucun téléviseur
        } finally {
            try { lock?.release() } catch (e: Exception) { /* ignoré */ }
        }
    }

    /** Lit la description d'un appareil : son nom et l'adresse de commande de la lecture. */
    fun describe(location: String): Renderer? = try {
        val body = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: ""
        parseDescription(location, body)
    } catch (e: Exception) {
        null
    }

    fun parseDescription(location: String, xml: String): Renderer? {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        fun text(e: Element, tag: String): String = e.getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent.trim() else "" }
        val root = doc.documentElement
        val base = text(root, "URLBase").ifEmpty { location }
        val services = root.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val s = services.item(i) as Element
            if (!text(s, "serviceType").startsWith("urn:schemas-upnp-org:service:AVTransport")) continue
            val ctl = text(s, "controlURL")
            if (ctl.isEmpty()) continue
            // le nom de l'appareil qui porte ce service
            var dev = s.parentNode
            while (dev != null && dev.nodeName != "device") dev = dev.parentNode
            val name = (dev as? Element)?.let { d ->
                (0 until d.childNodes.length).map { d.childNodes.item(it) }.firstOrNull { it.nodeName == "friendlyName" }?.textContent?.trim()
            } ?: text(root, "friendlyName")
            return Renderer(name.ifEmpty { "Téléviseur" }, URL(URL(base), ctl).toString(), location)
        }
        return null
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Description de la vidéo demandée par la plupart des téléviseurs (Samsung en particulier). */
    fun didl(title: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem.movie</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"

    fun envelope(action: String, args: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$AVT\">" +
            "<InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"

    /** Envoie une commande ; renvoie la réponse, ou null en cas d'échec. */
    fun soap(control: String, action: String, args: String = ""): String? = try {
        val req = Request.Builder().url(control)
            .header("SOAPACTION", "\"$AVT#$action\"")
            .post(envelope(action, args).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) (r.body?.string() ?: "") else null }
    } catch (e: Exception) {
        null
    }

    fun hms(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun parseHms(t: String): Long {
        val p = t.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
        return if (p.size == 3) (p[0] * 3600 + p[1] * 60 + p[2]) * 1000 else 0
    }

    fun tag(xml: String, name: String): String = Regex("<(?:\\w+:)?$name>([^<]*)</").find(xml)?.groupValues?.get(1)?.trim() ?: ""
}

/** Télécommande d'un téléviseur DLNA. */
class DlnaRemote(private val r: Dlna.Renderer) : Remote {
    override val name: String get() = r.name

    override suspend fun load(item: PlayItem, startMs: Long): String? = withContext(Dispatchers.IO) {
        val url = castUrl(item, hls = false)
        val mime = castMime(url)
        Dlna.soap(r.control, "Stop")
        val ok = Dlna.soap(
            r.control, "SetAVTransportURI",
            "<CurrentURI>${Dlna.esc(url)}</CurrentURI><CurrentURIMetaData>${Dlna.esc(Dlna.didl(item.title, url, mime))}</CurrentURIMetaData>",
        ) ?: return@withContext "Le téléviseur a refusé la vidéo"
        Dlna.soap(r.control, "Play", "<Speed>1</Speed>") ?: return@withContext "Le téléviseur n'a pas lancé la lecture"
        // reprise : on attend que la lecture démarre pour se placer au bon endroit
        if (startMs > 5000 && !item.live) {
            for (i in 0 until 40) {
                kotlinx.coroutines.delay(500)
                val st = Dlna.tag(Dlna.soap(r.control, "GetTransportInfo") ?: "", "CurrentTransportState")
                if (st == "PLAYING") {
                    Dlna.soap(r.control, "Seek", "<Unit>REL_TIME</Unit><Target>${Dlna.hms(startMs)}</Target>")
                    break
                }
            }
        }
        null
    }

    override suspend fun play() = withContext(Dispatchers.IO) { Dlna.soap(r.control, "Play", "<Speed>1</Speed>"); Unit }
    override suspend fun pause() = withContext(Dispatchers.IO) { Dlna.soap(r.control, "Pause"); Unit }
    override suspend fun seek(ms: Long) = withContext(Dispatchers.IO) {
        Dlna.soap(r.control, "Seek", "<Unit>REL_TIME</Unit><Target>${Dlna.hms(ms.coerceAtLeast(0))}</Target>"); Unit
    }
    override suspend fun stop() = withContext(Dispatchers.IO) { Dlna.soap(r.control, "Stop"); Unit }

    private var wasPlaying = false

    override suspend fun status(): RemoteStatus? = withContext(Dispatchers.IO) {
        val pos = Dlna.soap(r.control, "GetPositionInfo") ?: return@withContext null
        val tr = Dlna.soap(r.control, "GetTransportInfo") ?: return@withContext null
        val st = Dlna.tag(tr, "CurrentTransportState")
        val playing = st == "PLAYING" || st == "TRANSITIONING"
        val p = Dlna.parseHms(Dlna.tag(pos, "RelTime"))
        val d = Dlna.parseHms(Dlna.tag(pos, "TrackDuration"))
        val stopped = st == "STOPPED" || st == "NO_MEDIA_PRESENT"
        // fin de vidéo : arrêt après avoir joué, près de la fin
        val ended = stopped && wasPlaying && d > 0
        if (playing) wasPlaying = true
        RemoteStatus(p, d, playing, ended, stopped)
    }
}

// ---------------------------------------------------------------------------------------------
// Recherche des appareils (Chromecast + DLNA)
// ---------------------------------------------------------------------------------------------

/** Cherche les téléviseurs ; onChange est appelé (fil principal) à chaque nouvel appareil. */
class DeviceScan(private val c: Context, private val onChange: (List<Device>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val cast = Chromecast.context(c)
    private val router: MediaRouter? = if (cast != null) MediaRouter.getInstance(c) else null
    private val selector: MediaRouteSelector? = cast?.mergedSelector
    private val dlna = LinkedHashMap<String, Device>()
    @Volatile private var running = true

    private val cb = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
    }

    fun start() {
        if (router != null && selector != null) router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        Thread {
            // deux passes : certains téléviseurs répondent tard ou mettent du temps à se réveiller
            repeat(2) {
                if (!running) return@Thread
                Dlna.search(c, 3500) { loc ->
                    Thread {
                        val r = Dlna.describe(loc) ?: return@Thread
                        synchronized(dlna) {
                            if (dlna.values.any { it.name == r.name }) return@Thread
                            dlna[loc] = Device(r.name, "Téléviseur") { DlnaRemote(r) }
                        }
                        publish()
                    }.start()
                }
            }
        }.start()
        publish()
    }

    fun stop() {
        running = false
        try { router?.removeCallback(cb) } catch (e: Exception) { /* ignoré */ }
    }

    private fun castDevices(): List<Device> {
        val r = router ?: return emptyList()
        val sel = selector ?: return emptyList()
        return r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) && !it.isBluetooth }.map { route ->
            Device(route.name, "Chromecast") { connectCast(route) }
        }
    }

    private fun publish() {
        main.post {
            if (!running) return@post
            val all = castDevices() + synchronized(dlna) { dlna.values.toList() }
            // un téléviseur Google vu deux fois (Chromecast et DLNA) : on garde le Chromecast
            val names = HashSet<String>()
            onChange(all.filter { names.add(it.name.lowercase()) })
        }
    }

    /** Sélectionne le Chromecast et attend l'ouverture de la session (15 s au plus). */
    private suspend fun connectCast(route: MediaRouter.RouteInfo): Remote? = withContext(Dispatchers.Main) {
        val ctx = cast ?: return@withContext null
        val sm = ctx.sessionManager
        kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Remote?> { cont ->
                val l = object : SessionManagerListener<CastSession> {
                    fun done(r: Remote?) {
                        sm.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resumeWith(Result.success(r))
                    }
                    override fun onSessionStarted(s: CastSession, id: String) = done(CastRemote(route.name, s))
                    override fun onSessionResumed(s: CastSession, was: Boolean) = done(CastRemote(route.name, s))
                    override fun onSessionStartFailed(s: CastSession, e: Int) = done(null)
                    override fun onSessionResumeFailed(s: CastSession, e: Int) = done(null)
                    override fun onSessionEnded(s: CastSession, e: Int) {}
                    override fun onSessionEnding(s: CastSession) {}
                    override fun onSessionResuming(s: CastSession, id: String) {}
                    override fun onSessionStarting(s: CastSession) {}
                    override fun onSessionSuspended(s: CastSession, r: Int) {}
                }
                sm.addSessionManagerListener(l, CastSession::class.java)
                cont.invokeOnCancellation { sm.removeSessionManagerListener(l, CastSession::class.java) }
                val already = sm.currentCastSession
                if (already != null && already.isConnected && already.castDevice?.friendlyName == route.name) l.done(CastRemote(route.name, already))
                else router?.selectRoute(route)
            }
        }
    }
}
