package app.devdeck.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app runs on Android 7 (API 24), whose Java library is roughly Java 8. Android lint does not look into the
 * :core jar, so this test guards against Java 9+ calls that compile here but crash on old phones.
 */
class AndroidCompatTest {
    private val forbidden = listOf(
        Regex("""(out|err|buffer|baos|stream)\.toString\(\s*(Charsets|StandardCharsets|Charset)""") to "ByteArrayOutputStream.toString(Charset) (Java 10)",
        Regex("""\.readAllBytes\(\)""") to "InputStream.readAllBytes (Java 9)",
        Regex("""\.readNBytes\(""") to "InputStream.readNBytes (Java 9)",
        Regex("""\.transferTo\(""") to "InputStream.transferTo (Java 9)",
        Regex("""\b(List|Set|Map)\.(of|copyOf)\(""") to "List/Set/Map.of (Java 9)",
        Regex("""\.to(Hours|Minutes|Seconds|Millis)Part\(\)""") to "Duration.to…Part (Java 9)",
        Regex("""\.(strip|stripLeading|stripTrailing)\(\)""") to "String.strip (Java 11)",
        Regex("""\.orElseThrow\(\)""") to "Optional.orElseThrow() (Java 10)",
        Regex("""Files\.(readString|writeString)""") to "Files.readString (Java 11)",
    )

    @Test fun noJava9PlusApisInSharedCode() {
        val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))
        val hits = roots.filter { it.exists() }.flatMap { root ->
            root.walk().filter { it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    forbidden.firstOrNull { it.first.containsMatchIn(line) }?.let { "${f.path}:${i + 1}: ${it.second}" }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Nicht Android-7-kompatibel:\n" + hits.joinToString("\n"))
    }
}
