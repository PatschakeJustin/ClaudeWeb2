package app.devdeck.core

import app.devdeck.core.model.NextcloudConnection
import app.devdeck.core.nextcloud.WebDavException
import app.devdeck.core.nextcloud.WebDavFs
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavFsTest {
    private val server = MockWebServer()
    private lateinit var fs: WebDavFs

    @BeforeTest fun setUp() {
        server.start()
        fs = WebDavFs(NextcloudConnection(name = "nc", baseUrl = server.url("/cloud/").toString(), username = "anna", appPassword = "pw"))
    }

    @AfterTest fun tearDown() = server.shutdown()

    @Test fun listParsesPropfind() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(
            """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:">
 <d:response><d:href>/cloud/remote.php/dav/files/anna/Meine%20Projekte/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
 <d:response><d:href>/cloud/remote.php/dav/files/anna/Meine%20Projekte/app/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Sun, 15 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
 <d:response><d:href>/cloud/remote.php/dav/files/anna/Meine%20Projekte/notiz%20%2B%20plan.md</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>
</d:multistatus>"""))
        val entries = fs.list("/Meine Projekte")
        assertEquals(listOf("app", "notiz + plan.md"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(42L, entries[1].size)
        assertEquals("/Meine Projekte/notiz + plan.md", entries[1].path)

        val req = server.takeRequest()
        assertEquals("PROPFIND", req.method)
        assertEquals("/cloud/remote.php/dav/files/anna/Meine%20Projekte", req.path)
        assertEquals("1", req.getHeader("Depth"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun readWriteAndErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("inhalt"))
        assertEquals("inhalt", fs.read("/a.txt").toString(Charsets.UTF_8))
        server.enqueue(MockResponse().setResponseCode(201))
        fs.write("/b.txt", "neu".toByteArray())
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("neu", put.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertFailsWith<WebDavException> { runBlocking { fs.read("/x") } }
        assertEquals(401, e.code)
    }
}
