package app.devdeck.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.devdeck.AppContainer
import app.devdeck.ui.appContainer
import app.devdeck.ui.theme.MonoFamily

/** Creates a ViewModel that gets the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val c = appContainer()
    return viewModel(initializer = { create(c) })
}

/** Minimal Markdown: fenced code blocks, `inline code` and **bold**. Enough for chat replies. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parts = splitFences(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { (isCode, body) ->
            if (isCode) {
                Text(
                    body.trimEnd('\n'),
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            } else if (body.isNotBlank()) {
                Text(inlineMarkdown(body.trim('\n')), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun splitFences(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    var inCode = false
    val buf = StringBuilder()
    for (line in text.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            out += inCode to buf.toString(); buf.clear(); inCode = !inCode
        } else buf.append(line).append('\n')
    }
    out += inCode to buf.toString()
    return out
}

@Composable
private fun inlineMarkdown(s: String) = buildAnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = MonoFamily, background = codeBg)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}
