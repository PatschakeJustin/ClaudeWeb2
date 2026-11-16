package app.devdeck.core.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class HubException(val status: Int, message: String, val serverVersion: Int? = null) : IOException(message)

/** Keys derived on the device from the password. Only [authKeyHex] is ever sent to the hub. */
class HubKeys(val authKeyHex: String, val encKey: ByteArray)

object HubCrypto {
    const val ITERATIONS = 600_000

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun newSaltHex(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toByteString().hex()

    /** Two independent keys from one password: one to log in, one to encrypt (never leaves the device). */
    fun deriveKeys(password: String, saltHex: String, iterations: Int = ITERATIONS): HubKeys {
        val salt = hexToBytes(saltHex)
        val master = pbkdf2(password, salt, iterations)
        fun sub(label: String) = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(master, "HmacSHA256")); doFinal(label.toByteArray())
        }
        return HubKeys(sub("humantize-auth").toByteString().hex(), sub("humantize-enc"))
    }

    /** AES-256-GCM; output = base64(iv || ciphertext+tag). */
    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return (iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))).toByteString().base64()
    }

    fun decrypt(key: ByteArray, data: String): String {
        val raw = okio.ByteString.Companion.run { data.decodeBase64() }?.toByteArray()
            ?: throw IOException("Ungültige Tresor-Daten")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        return c.doFinal(raw, 12, raw.size - 12).toString(Charsets.UTF_8)
    }

    fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

@Serializable
data class StoreWidget(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val type: String = "custom",
    val definition: JsonElement,
    val installs: Int = 0,
    val createdAt: String = "",
)

data class VaultState(val version: Int, val plaintext: String?)

/**
 * Client for the Humantize Hub. The hub uses a self-signed certificate; its SHA-256 fingerprint is pinned
 * (trust on first use, like SSH host keys).
 */
class HubClient(
    baseUrl: String,
    private val pinnedFingerprint: String?,
    base: OkHttpClient = OkHttpClient(),
) {
    val baseUrl = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient = run {
        val b = base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        if (this.baseUrl.startsWith("https://")) {
            val tm = PinningTrustManager(pinnedFingerprint)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true } // identity = pinned fingerprint
        }
        b.build()
    }

    private suspend fun call(method: String, path: String, body: JsonObject? = null, token: String? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
                ?: if (method == "POST" || method == "PUT") "{}".toRequestBody("application/json".toMediaType()) else null
            val req = Request.Builder().url(baseUrl + path).method(method, rb)
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val el = runCatching { json.parseToJsonElement(text) }.getOrNull()
                if (!resp.isSuccessful) {
                    val obj = el as? JsonObject
                    val msg = (obj?.get("error") as? JsonPrimitive)?.contentOrNull ?: "HTTP ${resp.code}"
                    throw HubException(resp.code, msg, (obj?.get("version") as? JsonPrimitive)?.intOrNull)
                }
                el ?: throw HubException(resp.code, "Ungültige Antwort vom Hub")
            }
        }

    suspend fun info(): JsonObject = call("GET", "/api/info").jsonObject

    /** Creates an account; returns token and keys (the encryption key stays on the device). */
    suspend fun register(email: String, password: String, displayName: String? = null): Pair<String, HubKeys> {
        val salt = HubCrypto.newSaltHex()
        val keys = withContext(Dispatchers.Default) { HubCrypto.deriveKeys(password, salt) }
        val r = call("POST", "/api/register", buildJsonObject {
            put("email", email); put("authKey", keys.authKeyHex); put("salt", salt)
            displayName?.let { put("displayName", it) }
        }).jsonObject
        return r["token"]!!.jsonPrimitive.content to keys
    }

    suspend fun login(email: String, password: String): Pair<String, HubKeys> {
        val salt = call("POST", "/api/prelogin", buildJsonObject { put("email", email) }).jsonObject["salt"]!!.jsonPrimitive.content
        val keys = withContext(Dispatchers.Default) { HubCrypto.deriveKeys(password, salt) }
        val r = call("POST", "/api/login", buildJsonObject { put("email", email); put("authKey", keys.authKeyHex) }).jsonObject
        return r["token"]!!.jsonPrimitive.content to keys
    }

    suspend fun pullVault(token: String, keys: HubKeys): VaultState {
        val r = call("GET", "/api/vault", token = token).jsonObject
        val data = (r["data"] as? JsonPrimitive)?.contentOrNull
        return VaultState(r["version"]!!.jsonPrimitive.int, data?.let { HubCrypto.decrypt(keys.encKey, it) })
    }

    /** Uploads the encrypted vault; fails with status 409 if another device saved in between. */
    suspend fun pushVault(token: String, keys: HubKeys, baseVersion: Int, plaintext: String): Int {
        val r = call("PUT", "/api/vault", buildJsonObject {
            put("baseVersion", baseVersion); put("data", HubCrypto.encrypt(keys.encKey, plaintext))
        }, token).jsonObject
        return r["version"]!!.jsonPrimitive.int
    }

    suspend fun listWidgets(query: String = ""): List<StoreWidget> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val arr = call("GET", "/api/widgets?q=$q")
        return json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(StoreWidget.serializer()), arr)
    }

    suspend fun publishWidget(token: String, name: String, description: String, type: String, definition: JsonElement): StoreWidget {
        val r = call("POST", "/api/widgets", buildJsonObject {
            put("name", name); put("description", description); put("type", type); put("definition", definition)
        }, token)
        return json.decodeFromJsonElement(StoreWidget.serializer(), r)
    }

    suspend fun markInstalled(id: String) { call("POST", "/api/widgets/$id/install") }

    suspend fun deleteWidget(token: String, id: String) { call("DELETE", "/api/widgets/$id", token = token) }

    suspend fun notify(token: String, text: String, level: String = "info"): Boolean =
        (call("POST", "/api/notify", buildJsonObject { put("text", text); put("level", level) }, token)
            .jsonObject["sent"] as? JsonPrimitive)?.contentOrNull == "true"

    companion object {
        /** Connects once without trusting anything and returns the server certificate's SHA-256 fingerprint. */
        suspend fun fetchFingerprint(baseUrl: String): String = withContext(Dispatchers.IO) {
            val uri = URI(baseUrl.trim())
            val port = if (uri.port > 0) uri.port else 443
            val captured = arrayOfNulls<X509Certificate>(1)
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { captured[0] = chain[0] }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
                s.connect(java.net.InetSocketAddress(uri.host, port), 15_000)
                s.soTimeout = 15_000
                s.startHandshake()
            }
            fingerprint(captured[0] ?: throw IOException("Kein Zertifikat erhalten"))
        }

        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }
}

/** Accepts exactly the pinned certificate (by SHA-256 fingerprint). */
private class PinningTrustManager(private val pinned: String?) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val fp = HubClient.fingerprint(chain[0])
        if (pinned == null || !fp.equals(pinned.trim(), ignoreCase = true)) {
            throw java.security.cert.CertificateException(
                "Zertifikat des Hubs passt nicht zum gespeicherten Fingerabdruck – Verbindung abgelehnt."
            )
        }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
