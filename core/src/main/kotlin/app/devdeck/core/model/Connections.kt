package app.devdeck.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Common view of every connection type the user can configure. */
sealed interface Connection {
    val id: String
    val name: String
    val kind: Kind

    enum class Kind(val label: String) { SSH("SSH"), RDP("RDP"), NEXTCLOUD("Nextcloud"), AI("KI-Server") }
}

@Serializable
data class SshConnection(
    override val id: String = newId(),
    override val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Either a password or a private key (OpenSSH/PEM format) is used for authentication. */
    val password: String? = null,
    val privateKey: String? = null,
    val keyPassphrase: String? = null,
    /** Directory the agent and the file browser start in. */
    val defaultDirectory: String? = null,
    /** Windows server (OpenSSH + PowerShell) instead of Linux. */
    val windows: Boolean = false,
) : Connection {
    override val kind get() = Connection.Kind.SSH
}

@Serializable
data class RdpConnection(
    override val id: String = newId(),
    override val name: String,
    val host: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
) : Connection {
    override val kind get() = Connection.Kind.RDP
}

@Serializable
data class NextcloudConnection(
    override val id: String = newId(),
    override val name: String,
    /** e.g. https://cloud.example.com (without /remote.php/...) */
    val baseUrl: String,
    val username: String,
    /** An app password created in Nextcloud under Settings > Security. */
    val appPassword: String,
) : Connection {
    override val kind get() = Connection.Kind.NEXTCLOUD
}

/** A self-hosted, OpenAI-compatible model server (Ollama, LM Studio, vLLM, LocalAI, llama.cpp …). */
@Serializable
data class AiServerConnection(
    override val id: String = newId(),
    override val name: String,
    /** e.g. http://127.0.0.1:11434 – with a tunnel this address is resolved on the SSH server. */
    val baseUrl: String = "http://127.0.0.1:11434",
    val apiKey: String = "",
    val model: String = "",
    /** If set, requests go through an SSH tunnel over this SSH connection. */
    val tunnelSshId: String? = null,
) : Connection {
    override val kind get() = Connection.Kind.AI
}

@Serializable
data class ConnectionStore(
    val ssh: List<SshConnection> = emptyList(),
    val rdp: List<RdpConnection> = emptyList(),
    val nextcloud: List<NextcloudConnection> = emptyList(),
    val ai: List<AiServerConnection> = emptyList(),
) {
    val all: List<Connection> get() = ssh + rdp + nextcloud + ai

    fun find(idOrName: String): Connection? {
        val key = idOrName.trim()
        return all.firstOrNull { it.id == key } ?: all.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }

    fun upsert(c: Connection): ConnectionStore = when (c) {
        is SshConnection -> copy(ssh = ssh.replaceOrAdd(c))
        is RdpConnection -> copy(rdp = rdp.replaceOrAdd(c))
        is NextcloudConnection -> copy(nextcloud = nextcloud.replaceOrAdd(c))
        is AiServerConnection -> copy(ai = ai.replaceOrAdd(c))
    }

    fun remove(id: String): ConnectionStore = copy(
        ssh = ssh.filterNot { it.id == id },
        rdp = rdp.filterNot { it.id == id },
        nextcloud = nextcloud.filterNot { it.id == id },
        ai = ai.filterNot { it.id == id },
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): ConnectionStore = json.decodeFromString(serializer(), text)
    }
}

private fun <T : Connection> List<T>.replaceOrAdd(c: T): List<T> =
    if (any { it.id == c.id }) map { if (it.id == c.id) c else it } else this + c

fun newId(): String = UUID.randomUUID().toString()
