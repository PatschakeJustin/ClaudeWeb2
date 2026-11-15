package app.devdeck.core.claudecode

/** One interactive login in the terminal. [followUp] is typed once the tool's UI has settled (e.g. `/login`). */
data class LoginStep(val id: String, val label: String, val command: String, val followUp: String? = null)

/** Prints `HAS <id>` for installed tools and `AUTH <id>` where a stored login exists. */
val LOGIN_STATUS_SCRIPT: String = CLAUDE_PATH_PREFIX + "export PATH=\"\$HOME/.local/node/bin:\$PATH\"; " +
    "command -v claude >/dev/null 2>&1 && echo 'HAS claude'; [ -f \"\$HOME/.claude/.credentials.json\" ] && echo 'AUTH claude'; " +
    "command -v gemini >/dev/null 2>&1 && echo 'HAS gemini'; [ -f \"\$HOME/.gemini/oauth_creds.json\" ] && echo 'AUTH gemini'; " +
    "command -v qwen >/dev/null 2>&1 && echo 'HAS qwen'; [ -f \"\$HOME/.qwen/oauth_creds.json\" ] && echo 'AUTH qwen'; " +
    "command -v codex >/dev/null 2>&1 && echo 'HAS codex'; [ -f \"\$HOME/.codex/auth.json\" ] && echo 'AUTH codex'; true"

/** Install/login state of one tool on a server. */
data class ToolStatus(val id: String, val label: String, val installed: Boolean, val loggedIn: Boolean)

private val TOOLS = listOf("claude" to "Claude Code", "gemini" to "Gemini CLI", "qwen" to "Qwen Code", "codex" to "Codex CLI")

/** Parses [LOGIN_STATUS_SCRIPT] output into one entry per known tool. */
fun toolStatus(statusOutput: String): List<ToolStatus> {
    val lines = statusOutput.lines().map { it.trim() }.toSet()
    return TOOLS.map { (id, label) -> ToolStatus(id, label, "HAS $id" in lines, "AUTH $id" in lines) }
}

/** Login step for a single tool (re-login if it is already logged in). */
fun loginStepFor(status: ToolStatus): LoginStep? = if (!status.installed) null else
    loginSteps("HAS ${status.id}\n" + if (status.loggedIn) "AUTH ${status.id}\n" else "").firstOrNull()

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

/**
 * Login-looking URLs in terminal output (ANSI already stripped), in order of appearance. A URL at the very end
 * of [text] is ignored because it may still be incomplete (output arrives in chunks).
 */
fun findAuthUrls(text: String): List<String> =
    URL.findAll(text)
        .filter { it.range.last + 1 < text.length }
        .map { it.value.trimEnd('.', ',', ')', ';', ':', '…') }
        .filter { AUTH_HINT.containsMatchIn(it) && !it.endsWith("...") }
        .distinct().toList()

/**
 * All complete http(s) URLs in [text] (a URL at the very end may still be incomplete and is skipped).
 * Used during a guided login, where any link the tool prints is the login page.
 */
fun findCompleteUrls(text: String): List<String> =
    URL.findAll(text)
        .filter { it.range.last + 1 < text.length }
        .map { it.value.trimEnd('.', ',', ')', ';', ':', '…') }
        .filter { it.length > 12 && !it.endsWith("...") }
        .distinct().toList()

/** Picks the most likely login page: the last login-looking URL, otherwise the last URL at all. */
fun pickLoginUrl(urls: List<String>): String? =
    urls.lastOrNull { AUTH_HINT.containsMatchIn(it) } ?: urls.lastOrNull()

private val DEVICE_CODE = Regex("\\b([A-Z0-9]{4,5}-[A-Z0-9]{4,5})\\b")

/** One-time device codes such as `ABCD-1234` that the user has to type on the login page. */
fun findDeviceCode(text: String): String? = DEVICE_CODE.findAll(text).lastOrNull()?.groupValues?.get(1)

private val CSI = Regex("\u001B\\[([0-9;?<>=]*)[ -/]*([@-~])")
private val OSC = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
private val OTHER_ESC = Regex("\u001B[()][A-Za-z0-9]|\u001B[=>78MDEHc]")

/**
 * Turns raw terminal output into plain text: colours and titles are removed, cursor moves become
 * spaces or line breaks so that words drawn next to each other by a TUI do not run together.
 */
fun stripTerminalCodes(s: String): String {
    var t = OSC.replace(s, "")
    t = CSI.replace(t) { m ->
        val n = m.groupValues[1].substringBefore(';').toIntOrNull() ?: 1
        when (m.groupValues[2]) {
            "C" -> " ".repeat(n.coerceIn(1, 200))
            "A", "B", "E", "F", "H", "f", "d", "J" -> "\n"
            "K", "G" -> " "
            else -> ""
        }
    }
    return OTHER_ESC.replace(t, "").replace("\r", "")
}

private val OSC8 = Regex("\u001B\\]8;[^;\u0007\u001B]*;(https?://[^\u0007\u001B]+)(\u0007|\u001B\\\\)")
private val URL_TAIL = Regex("^[A-Za-z0-9%&=?/_.:~+#@!$*,;\\-]+$")
private val URL_AT_END = Regex("https?://\\S+$")

/** Full URLs carried in OSC 8 hyperlinks (`ESC ] 8 ; ; URL BEL`) – complete even if the visible text wraps. */
fun osc8Urls(raw: String): List<String> = OSC8.findAll(raw).map { it.groupValues[1] }.distinct().toList()

/**
 * TUIs like Claude Code wrap long lines themselves (hard line breaks, often indented). If a line ends in a URL
 * and the following lines consist only of URL characters, they are glued back together.
 */
fun joinWrappedUrls(text: String): String {
    val lines = text.split("\n")
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        var line = lines[i].trimEnd()
        if (URL_AT_END.containsMatchIn(line)) {
            while (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (next.isEmpty() || next.startsWith("http") || !URL_TAIL.matches(next)) break
                line += next
                i++
            }
        }
        out += line
        i++
    }
    return out.joinToString("\n")
}

/**
 * Login links in raw terminal output. During a guided login ([anyUrl]) every complete link counts,
 * otherwise only login-looking ones.
 */
fun extractLoginUrls(raw: String, anyUrl: Boolean): List<String> {
    val text = joinWrappedUrls(stripTerminalCodes(raw))
    val visible = if (anyUrl) findCompleteUrls(text) else findAuthUrls(text)
    val hidden = osc8Urls(raw).filter { anyUrl || AUTH_HINT.containsMatchIn(it) }
    // Prefer the longer variant when the visible text is a truncated copy of a hidden link.
    return (visible + hidden).filter { u -> (visible + hidden).none { o -> o != u && o.startsWith(u) } }.distinct()
}

private val SUCCESS = Regex(
    "login successful|successfully (logged|signed) in|logged in successfully|signed in successfully|" +
        "authentication (was )?successful|authentication complete|successfully authenticated",
    RegexOption.IGNORE_CASE,
)

/** True if the (stripped) output says the login worked. */
fun loginSucceeded(text: String): Boolean = SUCCESS.containsMatchIn(text)
