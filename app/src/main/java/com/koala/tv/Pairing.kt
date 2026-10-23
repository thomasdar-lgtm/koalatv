package com.koala.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Connexion rapide : l'appareil à connecter affiche un code ; un appareil déjà connecté y envoie les identifiants.
 * Ils transitent chiffrés (clé tirée du code) par la base Firebase, à une adresse tirée du code,
 * et sont effacés dès réception. Même calcul que l'appli LG.
 */
object Pairing {
    private const val ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val rnd = SecureRandom()

    data class Creds(val server: String, val user: String, val pass: String)

    fun newCode(): String = buildString { repeat(8) { append(ABC[rnd.nextInt(ABC.length)]) } }

    fun display(code: String) = code.take(4) + " " + code.drop(4)

    /** Code saisi par l'utilisateur, ou null s'il n'est pas valide. */
    fun clean(raw: String): String? {
        val c = raw.uppercase().filter { !it.isWhitespace() && it != '-' }
        return if (c.length == 8 && c.all { it in ABC }) c else null
    }

    private fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun url(code: String) = "${Sync.DB.trimEnd('/')}/h/p${hex(sha("koalatv-pair|$code")).take(40)}/x.json"

    private fun xor(code: String, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = ByteArray(0)
        for (i in data.indices) {
            if (i % 32 == 0) block = sha("koalatv-key|$code|${i / 32}")
            out[i] = (data[i].toInt() xor block[i % 32].toInt()).toByte()
        }
        return out
    }

    /** Envoie les identifiants de cet appareil vers le code affiché sur l'autre. */
    suspend fun send(code: String): Boolean = withContext(Dispatchers.IO) {
        val plain = JSONObject().put("s", Prefs.server).put("u", Prefs.user).put("p", Prefs.pass).toString().toByteArray()
        val body = JSONObject().put("d", hex(xor(code, plain))).put("at", System.currentTimeMillis()).toString()
        try {
            Xtream.http.newCall(Request.Builder().url(url(code)).put(body.toRequestBody(JSON)).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** true quand l'autre appareil a récupéré les identifiants (l'enregistrement a été effacé). */
    suspend fun consumed(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Xtream.http.newCall(Request.Builder().url(url(code)).build()).execute().use { r ->
                r.isSuccessful && r.body?.string()?.trim() == "null"
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Efface un envoi resté sans réponse (code mal saisi, appareil éteint). */
    suspend fun cancel(code: String) = withContext(Dispatchers.IO) {
        try {
            Xtream.http.newCall(Request.Builder().url(url(code)).delete().build()).execute().close()
        } catch (e: Exception) {
            // hors ligne
        }
    }

    /** Identifiants reçus pour ce code (effacés aussitôt), ou null. */
    suspend fun receive(code: String): Creds? = withContext(Dispatchers.IO) {
        try {
            val txt = Xtream.http.newCall(Request.Builder().url(url(code)).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string() ?: return@withContext null
            }
            if (txt.trim() == "null") return@withContext null
            val rec = JSONObject(txt)
            val d = rec.optString("d")
            Xtream.http.newCall(Request.Builder().url(url(code)).delete().build()).execute().close()
            if (d.isEmpty() || d.length % 2 != 0) return@withContext null
            val bytes = ByteArray(d.length / 2) { d.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val o = JSONObject(String(xor(code, bytes)))
            val c = Creds(o.optString("s"), o.optString("u"), o.optString("p"))
            if (c.server.isEmpty() || c.user.isEmpty() || c.pass.isEmpty()) null else c
        } catch (e: Exception) {
            null
        }
    }
}
