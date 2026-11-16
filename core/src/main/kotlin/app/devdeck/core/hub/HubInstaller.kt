package app.devdeck.core.hub

import app.devdeck.core.claudecode.NODE_INSTALL
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.SshManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Discord bot settings written into the hub's config.json (the token stays on the server). */
data class DiscordConfig(
    val token: String,
    val allowedUsers: List<String>,
    val channelId: String = "",
    val services: List<String> = emptyList(),
    val claudeWorkDir: String = "",
    val allowShell: Boolean = false,
)

data class HubInstallResult(val url: String, val fingerprint: String)

/**
 * Installs (or updates) the Humantize Hub on a Linux server over SSH: uploads the hub files, writes the
 * configuration, creates a TLS certificate and a systemd service (fallback: @reboot crontab).
 */
class HubInstaller(private val ssh: SshManager) {

    fun configJson(port: Int, registration: String, discord: DiscordConfig?): String = Json { prettyPrint = true }.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("port", port); put("host", "0.0.0.0"); put("dataDir", "data"); put("registration", registration)
            put("cert", "cert.pem"); put("key", "key.pem")
            if (discord != null && discord.token.isNotBlank()) put("discord", buildJsonObject {
                put("token", discord.token.trim())
                put("allowedUsers", buildJsonArray { discord.allowedUsers.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it.trim())) } })
                put("channelId", discord.channelId.trim())
                put("services", buildJsonArray { discord.services.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it.trim())) } })
                if (discord.claudeWorkDir.isNotBlank()) put("claudeWorkDir", discord.claudeWorkDir.trim())
                put("allowShell", discord.allowShell)
            })
        },
    )

    fun script(port: Int, withDiscord: Boolean): String = """
set -e
DIR="${'$'}HOME/humantize-hub"
cd "${'$'}DIR"
export PATH="${'$'}HOME/.local/bin:${'$'}HOME/.local/node/bin:${'$'}PATH"
$NODE_INSTALL
NODE=${'$'}(command -v node)
${if (withDiscord) "echo \"== discord.js installieren ==\"\nnpm install --omit=dev --no-audit --no-fund --loglevel=error" else "true"}
if [ ! -f cert.pem ]; then
  echo "== Zertifikat erzeugen =="
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=humantize-hub" -keyout key.pem -out cert.pem 2>/dev/null
fi
chmod 600 key.pem config.json
SUDO=""
if [ "${'$'}(id -u)" != 0 ]; then SUDO="sudo -n"; fi
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ] && { [ -z "${'$'}SUDO" ] || sudo -n true 2>/dev/null; }; then
  echo "== Dienst humantize-hub einrichten =="
  cat <<UNIT | ${'$'}SUDO tee /etc/systemd/system/humantize-hub.service >/dev/null
[Unit]
Description=Humantize Hub
After=network-online.target
[Service]
User=${'$'}(id -un)
WorkingDirectory=${'$'}DIR
Environment=HUB_CONFIG=${'$'}DIR/config.json
Environment=PATH=${'$'}HOME/.local/bin:${'$'}HOME/.local/node/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=${'$'}NODE ${'$'}DIR/server.js
Restart=always
[Install]
WantedBy=multi-user.target
UNIT
  ${'$'}SUDO systemctl daemon-reload
  ${'$'}SUDO systemctl enable humantize-hub >/dev/null 2>&1 || true
  ${'$'}SUDO systemctl restart humantize-hub
  if command -v ufw >/dev/null 2>&1 && ${'$'}SUDO ufw status 2>/dev/null | grep -q "Status: active"; then
    ${'$'}SUDO ufw allow $port/tcp >/dev/null && echo "Firewall: Port $port geöffnet"
  fi
else
  echo "== Hub ohne systemd starten (Autostart per crontab) =="
  pkill -f "${'$'}DIR/server.js" 2>/dev/null || true
  HUB_CONFIG="${'$'}DIR/config.json" nohup "${'$'}NODE" "${'$'}DIR/server.js" > "${'$'}DIR/hub.log" 2>&1 &
  [ -n "${'$'}HUB_SKIP_CRON" ] || ( crontab -l 2>/dev/null | grep -v humantize-hub; echo "@reboot cd ${'$'}DIR && HUB_CONFIG=${'$'}DIR/config.json ${'$'}NODE ${'$'}DIR/server.js >> ${'$'}DIR/hub.log 2>&1 # humantize-hub" ) | crontab - 2>/dev/null || true
fi
i=0
until curl -sk "https://127.0.0.1:$port/api/health" >/dev/null 2>&1; do
  i=${'$'}((i+1))
  if [ ${'$'}i -gt 30 ]; then
    echo "Der Hub antwortet nicht."
    tail -n 20 "${'$'}DIR/hub.log" 2>/dev/null || true
    journalctl -u humantize-hub -n 20 --no-pager 2>/dev/null || true
    exit 1
  fi
  sleep 1
done
echo "DEVDECK_HUB_FP=${'$'}(openssl x509 -noout -fingerprint -sha256 -in cert.pem | cut -d= -f2)"
echo DEVDECK_HUB_OK
""".trimIndent()

    /**
     * Uploads [files] (name -> content, e.g. server.js, bot.js, package.json from the app's assets), writes the
     * config and runs the install script. Output lines go to [log].
     */
    suspend fun install(
        conn: SshConnection,
        files: Map<String, ByteArray>,
        port: Int = 8443,
        registration: String = "first-user-only",
        discord: DiscordConfig? = null,
        log: (String) -> Unit,
    ): HubInstallResult {
        val fs = ssh.fs(conn)
        val dir = fs.home().trimEnd('/') + "/humantize-hub"
        fs.mkdirs(dir)
        log("Lade Hub-Dateien nach $dir …")
        files.forEach { (name, bytes) -> fs.write("$dir/$name", bytes) }
        fs.write("$dir/config.json", configJson(port, registration, discord).toByteArray())
        var fp: String? = null
        var ok = false
        val exit = ssh.execStreaming(conn, "sh -s", stdin = script(port, discord != null && discord.token.isNotBlank()),
            onStdout = { line ->
                when {
                    line.startsWith("DEVDECK_HUB_FP=") -> fp = line.substringAfter("=").trim()
                    line.trim() == "DEVDECK_HUB_OK" -> ok = true
                    else -> log(line)
                }
            }, onStderr = log)
        val fingerprint = fp
        if (!ok || fingerprint == null) throw java.io.IOException("Hub-Installation fehlgeschlagen (Exit-Code ${exit ?: "?"}).")
        return HubInstallResult("https://${conn.host}:$port", fingerprint)
    }
}
