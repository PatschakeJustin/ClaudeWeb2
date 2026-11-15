package app.devdeck.core.ssh

import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** Persists SSH host key fingerprints ("host:port" -> fingerprint). */
interface KnownHostsStore {
    fun get(hostPort: String): String?
    fun put(hostPort: String, fingerprint: String)
}

class InMemoryKnownHosts : KnownHostsStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(hostPort: String) = map[hostPort]
    override fun put(hostPort: String, fingerprint: String) { map[hostPort] = fingerprint }
}

class HostKeyMismatchException(val hostPort: String, val expected: String, val actual: String) :
    Exception(
        "Der Host-Schlüssel von $hostPort hat sich geändert (erwartet $expected, erhalten $actual). " +
            "Verbindung abgebrochen – möglicher Man-in-the-Middle-Angriff. " +
            "Wenn der Server neu aufgesetzt wurde, den gespeicherten Schlüssel in den Verbindungseinstellungen zurücksetzen."
    )

/**
 * Trust-on-first-use: the first key seen for a host is stored, later connections must present the same key.
 */
class TofuHostKeyVerifier(private val store: KnownHostsStore) : HostKeyVerifier {
    @Volatile var mismatch: HostKeyMismatchException? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val hostPort = "$hostname:$port"
        val fp = SecurityUtils.getFingerprint(key)
        val known = store.get(hostPort)
        return when {
            known == null -> { store.put(hostPort, fp); true }
            known == fp -> true
            else -> { mismatch = HostKeyMismatchException(hostPort, known, fp); false }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
