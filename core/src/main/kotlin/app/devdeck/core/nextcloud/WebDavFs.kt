package app.devdeck.core.nextcloud

import app.devdeck.core.fs.FileTooLargeException
import app.devdeck.core.fs.RemoteEntry
import app.devdeck.core.fs.RemoteFs
import app.devdeck.core.model.NextcloudConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavException(val code: Int, message: String) : IOException(message)

/** Nextcloud file access over WebDAV (`/remote.php/dav/files/<user>/`). Paths are relative to the user's root. */
class WebDavFs(
    private val conn: NextcloudConnection,
    baseClient: OkHttpClient = OkHttpClient(),
) : RemoteFs {
    private val client = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val auth = Credentials.basic(conn.username, conn.appPassword)

    /** e.g. https://host/nextcloud/remote.php/dav/files/alice */
    private val root: HttpUrl = conn.baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("remote.php/dav/files")
        .addPathSegment(conn.username)
        .build()

    internal fun urlFor(path: String): HttpUrl {
        val b = root.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth)

    private fun Response.check(path: String): Response {
        if (!isSuccessful) {
            close()
            val hint = when (code) {
                401 -> "Anmeldung fehlgeschlagen – Benutzername/App-Passwort prüfen."
                404 -> "Nicht gefunden: $path"
                else -> "HTTP $code $message"
            }
            throw WebDavException(code, "Nextcloud: $hint")
        }
        return this
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = request(urlFor(path))
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val xml = client.newCall(req).execute().check(path).use { it.body!!.string() }
        parsePropfind(xml, path)
    }

    internal fun parsePropfind(xml: String, requestedPath: String): List<RemoteEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val rootPath = root.encodedPath.trimEnd('/')
        val selfPath = "/" + requestedPath.trim('/')
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.firstText("href") ?: continue
            val hrefPath = runCatching { href.toHttpUrl().encodedPath }.getOrDefault(href)
            if (!hrefPath.startsWith(rootPath)) continue
            val rel = URLDecoder.decode(hrefPath.removePrefix(rootPath).replace("+", "%2B"), "UTF-8").trimEnd('/')
            val relPath = rel.ifEmpty { "/" }
            if (relPath.trimEnd('/') == selfPath.trimEnd('/') || (relPath == "/" && selfPath == "/")) continue
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = r.firstText("getcontentlength")?.toLongOrNull()
            val modified = r.firstText("getlastmodified")?.let { runCatching { dateFormat.parse(it).time / 1000 }.getOrNull() }
            result += RemoteEntry(
                name = relPath.substringAfterLast('/'),
                path = relPath,
                isDirectory = isDir,
                size = if (isDir) null else size,
                modifiedEpochSeconds = modified,
            )
        }
        return result.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun Element.firstText(local: String): String? {
        val nodes = getElementsByTagNameNS("DAV:", local)
        return if (nodes.length == 0) null else nodes.item(0).textContent?.trim()
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request(urlFor(path)).get().build()).execute().check(path).use { resp ->
            val body = resp.body!!
            val len = body.contentLength()
            if (len > maxBytes) throw FileTooLargeException(path, len, maxBytes)
            val bytes = body.source().let { src ->
                src.request(maxBytes + 1)
                src.buffer.readByteArray(minOf(src.buffer.size, maxBytes + 1))
            }
            if (bytes.size > maxBytes) throw FileTooLargeException(path, bytes.size.toLong(), maxBytes)
            bytes
        }
    }

    override suspend fun write(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val req = request(urlFor(path)).put(data.toRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(req).execute().check(path).close()
    }

    override suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        var current = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current += "/$segment"
            val req = request(urlFor(current)).method("MKCOL", null).build()
            client.newCall(req).execute().use { resp ->
                // 405 = already exists
                if (!resp.isSuccessful && resp.code != 405) throw WebDavException(resp.code, "Nextcloud: MKCOL $current fehlgeschlagen (HTTP ${resp.code})")
            }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        client.newCall(request(urlFor(path)).delete().build()).execute().check(path).close()
    }

    override suspend fun home(): String = "/"
}
