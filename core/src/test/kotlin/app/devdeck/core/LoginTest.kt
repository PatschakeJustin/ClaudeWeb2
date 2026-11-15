package app.devdeck.core

import app.devdeck.core.claudecode.findAuthUrls
import app.devdeck.core.claudecode.findDeviceCode
import app.devdeck.core.claudecode.loginSteps
import app.devdeck.core.claudecode.stripTerminalCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginTest {
    @Test fun stepsForInstalledTools() {
        val steps = loginSteps("HAS claude\nAUTH claude\nHAS gemini\nHAS codex\nAUTH codex\n")
        assertEquals(listOf("claude", "gemini", "codex"), steps.map { it.id })
        assertEquals("/login\r", steps[0].followUp)          // already logged in -> re-login
        assertNull(steps[1].followUp)                         // fresh -> onboarding asks by itself
        assertEquals("codex login --device-auth\r", steps[2].command)
        assertEquals(emptyList(), loginSteps(""))
    }

    @Test fun detectsLoginUrlsInColouredOutput() {
        val claude = "\u001b[2K\u001b[1GBrowser didn't open? Use the url below to sign in:\r\n\r\n\u001b[38;5;153m" +
            "https://claude.ai/oauth/authorize?code=true&client_id=abc&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&state=xyz" +
            "\u001b[39m\r\n\r\nPaste code here if prompted >"
        assertEquals(
            listOf("https://claude.ai/oauth/authorize?code=true&client_id=abc&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&state=xyz"),
            findAuthUrls(stripTerminalCodes(claude)),
        )
        val codex = "Follow these steps to sign in with ChatGPT:\n1. Open https://auth.openai.com/codex/device\n2. Enter this one-time code: \u001b[1mK7QX-29FD\u001b[0m\n"
        val plain = stripTerminalCodes(codex)
        assertEquals(listOf("https://auth.openai.com/codex/device"), findAuthUrls(plain))
        assertEquals("K7QX-29FD", findDeviceCode(plain))
        // Ordinary links are not treated as login pages.
        assertEquals(emptyList(), findAuthUrls("see https://github.com/foo/bar for docs"))
    }
}

class LoginEdgeCaseTest {
    private val url = "https://claude.ai/oauth/authorize?code=true&client_id=9d1c&response_type=code&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&scope=org%3Acreate_api_key+user%3Ainference&code_challenge=Ab-_c&code_challenge_method=S256&state=Zz"

    @Test fun incompleteUrlAtEndIsIgnored() {
        val first = "Use the url below to sign in:\n\n" + url.take(80)
        assertEquals(emptyList(), findAuthUrls(stripTerminalCodes(first)))
        assertEquals(listOf(url), findAuthUrls(stripTerminalCodes(first + url.drop(80) + "\n")))
    }

    @Test fun escapeSplitAcrossChunksDoesNotLeakIntoUrl() {
        // Accumulate raw chunks first, strip afterwards (what the app does now).
        val raw = "\u001b[38;5;153m" + url + "\u001b[39m\u001b[1C\u001b[2mPaste code here\u001b[22m"
        val chunks = listOf(raw.take(15), raw.drop(15).take(200), raw.drop(215))
        assertEquals(listOf(url), findAuthUrls(stripTerminalCodes(chunks.joinToString(""))))
    }

    @Test fun cursorMovesBecomeSeparators() {
        assertEquals(listOf(url), findAuthUrls(stripTerminalCodes(url + "\u001b[3CPaste")))
        assertEquals(listOf(url), findAuthUrls(stripTerminalCodes("\u001b[5;1H" + url + "\u001b[6;1H>")))
        // OSC 8 hyperlinks: only the visible text remains.
        assertEquals(listOf(url), findAuthUrls(stripTerminalCodes("\u001b]8;;$url\u0007$url\u001b]8;;\u0007\n")))
    }
}
