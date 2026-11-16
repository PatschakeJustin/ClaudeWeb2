package app.devdeck.core

import app.devdeck.core.hub.DiscordConfig
import app.devdeck.core.hub.HubClient
import app.devdeck.core.hub.HubInstaller
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.InMemoryKnownHosts
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Installs the hub through SSH/SFTP into a temporary home directory and logs in to it over TLS. */
class HubInstallerTest {
    private val home: File = Files.createTempDirectory("hubhome").toFile()
    private val sshd: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"; port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        setPasswordAuthenticator { _, _, _ -> true }
        fileSystemFactory = VirtualFileSystemFactory(home.toPath())
        subsystemFactories = listOf(SftpSubsystemFactory())
        commandFactory = CommandFactory { channel, command ->
            ProcessShellFactory(command, "/bin/sh", "-c", "export HOME='${home.path}' HUB_SKIP_CRON=1; $command").createShell(channel)
        }
        start()
    }

    @AfterTest fun cleanup() {
        ProcessBuilder("pkill", "-f", "${home.path}/humantize-hub/server.js").start().waitFor()
        sshd.stop(true)
    }

    @Test fun configJsonKeepsDiscordSettings() {
        val json = HubInstaller(SshManager(InMemoryKnownHosts())).configJson(9443, "open",
            DiscordConfig("tok", listOf("123", " "), "456", listOf("nginx"), allowShell = false))
        assertContains(json, "\"token\": \"tok\""); assertContains(json, "\"allowedUsers\": [\n            \"123\"\n        ]")
        assertFalse(HubInstaller(SshManager(InMemoryKnownHosts())).configJson(1, "open", null).contains("discord"))
    }

    @Test fun installsAndServesOverTls() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val files = listOf("server.js", "bot.js", "package.json").associateWith { File("../hub/$it").readBytes() }
        val conn = SshConnection(name = "srv", host = "127.0.0.1", port = sshd.port, username = "u", password = "p")
        val log = mutableListOf<String>()
        val result = HubInstaller(SshManager(InMemoryKnownHosts())).install(conn, files, port = port, registration = "open") { log += it }

        assertEquals("https://127.0.0.1:$port", result.url)
        assertTrue(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(result.fingerprint), result.fingerprint)
        assertTrue(File(home, "humantize-hub/cert.pem").exists())
        assertTrue(log.any { "Zertifikat" in it }, log.joinToString("\n"))

        // The fingerprint reported through SSH is the one the hub actually presents.
        assertEquals(result.fingerprint, HubClient.fetchFingerprint(result.url))
        val client = HubClient(result.url, result.fingerprint)
        val (token, keys) = client.register("a@b.de", "pw-123456")
        assertEquals(1, client.pushVault(token, keys, 0, "{}"))
    }
}
