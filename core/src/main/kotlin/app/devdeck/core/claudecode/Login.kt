package app.devdeck.core.claudecode

/** One interactive login in the terminal. [followUp] is typed once the tool's UI has settled (e.g. `/login`). */
data class LoginStep(val id: String, val label: String, val command: String, val followUp: String? = null)

/** Prints `HAS <id>` for installed tools and `AUTH <id>` where a stored login exists. */
val LOGIN_STATUS_SCRIPT: String = CLAUDE_PATH_PREFIX + "export PATH=\"\$HOME/.local/node/bin:\$PATH\"; " +
    "command -v claude >/dev/null 2>&1 && echo 'HAS claude'; [ -f \"\$HOME/.claude/.credentials.json\" ] && echo 'AUTH claude'; " +
    "command -v gemini >/dev/null 2>&1 && echo 'HAS gemini'; [ -f \"\$HOME/.gemini/oauth_creds.json\" ] && echo 'AUTH gemini'; " +
    "command -v qwen >/dev/null 2>&1 && echo 'HAS qwen'; [ -f \"\$HOME/.qwen/oauth_creds.json\" ] && echo 'AUTH qwen'; " +
    "command -v codex >/dev/null 2>&1 && echo 'HAS codex'; [ -f \"\$HOME/.codex/auth.json\" ] && echo 'AUTH codex'; true"

/** Builds the login sequence for everything installed on the server. Already logged-in tools are logged in again. */
fun loginSteps(statusOutput: String): List<LoginStep> {
    val lines = statusOutput.lines().map { it.trim() }.toSet()
    fun has(id: String) = "HAS $id" in lines
    fun auth(id: String) = "AUTH $id" in lines
    val steps = mutableListOf<LoginStep>()
    if (has("claude")) steps += LoginStep("claude", "Claude Code", "claude\r", if (auth("claude")) "/login\r" else null)
    if (has("gemini")) steps += LoginStep("gemini", "Gemini CLI", "NO_BROWSER=true gemini\r", if (auth("gemini")) "/auth\r" else null)
    if (has("qwen")) steps += LoginStep("qwen", "Qwen Code", "qwen\r", if (auth("qwen")) "/auth\r" else null)
    if (has("codex")) steps += LoginStep("codex", "Codex CLI", "codex login --device-auth\r")
    return steps
}

private val URL = Regex("https?://[^\\s\"'<>\\u001b]+")
private val AUTH_HINT = Regex("oauth|authorize|/login|signin|sign_in|device|auth\\.|accounts\\.google|/code", RegexOption.IGNORE_CASE)

/** Login-looking URLs in terminal output (ANSI already stripped), in order of appearance. */
fun findAuthUrls(text: String): List<String> =
    URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ';', ':') }.filter { AUTH_HINT.containsMatchIn(it) }.distinct().toList()

private val DEVICE_CODE = Regex("\\b([A-Z0-9]{4,5}-[A-Z0-9]{4,5})\\b")

/** One-time device codes such as `ABCD-1234` that the user has to type on the login page. */
fun findDeviceCode(text: String): String? = DEVICE_CODE.findAll(text).lastOrNull()?.groupValues?.get(1)

private val ANSI_RE = Regex("\u001B\\[[0-9;?<>=]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]|\u001B[=>78MDEHc]|\r")

/** Removes ANSI escape sequences (colours, cursor moves, OSC titles). */
fun stripTerminalCodes(s: String): String = s.replace(ANSI_RE, "")
