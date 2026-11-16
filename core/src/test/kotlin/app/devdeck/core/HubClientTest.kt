package app.devdeck.core

import app.devdeck.core.hub.HubClient
import app.devdeck.core.hub.HubCrypto
import app.devdeck.core.hub.HubException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the real Node.js hub (hub/server.js) with TLS and talks to it through [HubClient]. */
class HubClientTest {
    private lateinit var proc: Process
    private lateinit var url: String
    private lateinit var fingerprint: String
    private lateinit var dataDir: File

    @BeforeTest fun startHub() {
        val dir = Files.createTempDirectory("hub").toFile()
        dataDir = File(dir, "data")
        fun sh(vararg cmd: String) = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start().let {
            val out = it.inputStream.readAllBytes().toString(Charsets.UTF_8); it.waitFor(); out
        }
        sh("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=humantize-hub",
            "-keyout", "key.pem", "-out", "cert.pem")
        fingerprint = sh("openssl", "x509", "-noout", "-fingerprint", "-sha256", "-in", "cert.pem")
            .substringAfter("=").trim()
        File(dir, "config.json").writeText("""{"port":0,"host":"127.0.0.1","dataDir":"data","registration":"first-user-only"}""")
        val server = File("../hub/server.js").canonicalPath
        proc = ProcessBuilder("node", server).directory(dir).redirectErrorStream(true)
            .also { it.environment()["HUB_CONFIG"] = File(dir, "config.json").path }.start()
        val line = proc.inputStream.bufferedReader().readLine() ?: error("hub did not start")
        val port = Regex("Port (\\d+)").find(line)?.groupValues?.get(1) ?: error("unexpected: $line")
        url = "https://127.0.0.1:$port"
    }

    @AfterTest fun stopHub() { proc.destroy(); proc.waitFor(5, TimeUnit.SECONDS) }

    @Test fun keyDerivationIsDeterministicAndSeparated() {
        val salt = "00112233445566778899aabbccddeeff"
        val a = HubCrypto.deriveKeys("geheim", salt, iterations = 1000)
        val b = HubCrypto.deriveKeys("geheim", salt, iterations = 1000)
        assertEquals(a.authKeyHex, b.authKeyHex)
        assertEquals(64, a.authKeyHex.length)
        assertNotEquals(a.authKeyHex, a.encKey.joinToString("") { "%02x".format(it) })
        val box = HubCrypto.encrypt(a.encKey, "Hallo Tresor ✓")
        assertEquals("Hallo Tresor ✓", HubCrypto.decrypt(b.encKey, box))
        assertNotEquals(box, HubCrypto.encrypt(a.encKey, "Hallo Tresor ✓")) // random IV
    }

    @Test fun pinningRegistrationVaultAndStore() = runBlocking {
        assertEquals(fingerprint.uppercase(), HubClient.fetchFingerprint(url))

        // Wrong or missing pin: refused.
        assertFailsWith<Exception> { HubClient(url, "AA:BB").info() }
        assertFailsWith<Exception> { HubClient(url, null).info() }

        val phone = HubClient(url, fingerprint)
        assertEquals("open", phone.info()["registration"].toString().trim('"'))
        val (token, keys) = phone.register("justin@example.com", "sehr-geheim-123", "Justin")

        val v0 = phone.pullVault(token, keys)
        assertEquals(0, v0.version); assertEquals(null, v0.plaintext)
        val secretJson = """{"ssh":[{"name":"web","password":"root-pw"}]}"""
        assertEquals(1, phone.pushVault(token, keys, 0, secretJson))

        // The hub stores only ciphertext.
        val stored = dataDir.walkTopDown().filter { it.isFile && it.path.contains("vaults") }.single().readText()
        assertTrue("root-pw" !in stored && "web" !in stored, stored)

        // Second device logs in and decrypts.
        val tablet = HubClient(url, fingerprint)
        val (token2, keys2) = tablet.login("JUSTIN@example.com", "sehr-geheim-123")
        assertEquals(secretJson, tablet.pullVault(token2, keys2).plaintext)

        // Concurrent save from an outdated version is rejected with the current version.
        val e = assertFailsWith<HubException> { tablet.pushVault(token2, keys2, 0, "{}") }
        assertEquals(409, e.status); assertEquals(1, e.serverVersion)

        val wrong = assertFailsWith<HubException> { tablet.login("justin@example.com", "falsch") }
        assertEquals(401, wrong.status)
        assertEquals(403, assertFailsWith<HubException> { tablet.register("b@x.de", "pw") }.status)

        // Widget store.
        val w = phone.publishWidget(token, "Server-Last", "Zeigt uptime", "command",
            buildJsonObject { put("kind", "command"); put("command", "uptime") })
        tablet.markInstalled(w.id)
        val found = tablet.listWidgets("last")
        assertEquals(listOf("Server-Last"), found.map { it.name })
        assertEquals(1, found.single().installs)
        assertContains(found.single().definition.toString(), "uptime")
        phone.deleteWidget(token, w.id)
        assertEquals(0, tablet.listWidgets().size)
    }
}
