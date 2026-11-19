package uk.jj.backchannel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uk.jj.backchannel.core.Backup
import uk.jj.backchannel.core.Kdf
import uk.jj.backchannel.core.Updates
import uk.jj.backchannel.core.WrongSecretException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import kotlin.concurrent.thread

class BackupTest {
    private val fast = Kdf.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val pass = "four unrelated words here"

    private fun seal(data: ByteArray, passphrase: String = pass): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.Writer(out, passphrase.toCharArray(), fast).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, passphrase: String = pass): ByteArray =
        Backup.Reader(ByteArrayInputStream(file), passphrase.toCharArray()).use { it.readBytes() }

    @Test fun whatGoesInComesOutForEverySizeAroundTheBlockBoundary() {
        val rnd = Random(3)
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 131_072, 300_000)) {
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            assertArrayEquals("size $size", data, open(seal(data)))
        }
    }

    @Test fun nothingReadableIsInTheFile() {
        val secret = "MEET AT GATE THREE ".repeat(500).toByteArray()
        val file = seal(secret)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("GATE THREE"))
        assertFalse(String(file, Charsets.ISO_8859_1).contains(pass))
    }

    @Test fun theWrongPassphraseIsRefused() {
        val file = seal(ByteArray(1000) { 5 })
        for (wrong in listOf("four unrelated words herf", "", "Four unrelated words here")) {
            try {
                open(file, wrong)
                fail("'$wrong' opened the backup")
            } catch (_: WrongSecretException) {
            }
        }
    }

    @Test fun aChangedCutShortOrRearrangedFileIsRefused() {
        val data = ByteArray(200_000).also { Random(9).nextBytes(it) }
        val file = seal(data)

        val flipped = file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertRefused("one changed byte") { open(flipped) }

        assertRefused("cut off mid-block") { open(file.copyOf(file.size - 100)) }

        // Cut cleanly after the first whole block: every block present still checks out, but the ending is missing.
        val header = 10 + 12 + 16 + 8
        val firstBlock = 4 + 65_536 + 16
        assertRefused("last blocks removed") { open(file.copyOf(header + firstBlock)) }

        // Swap the first two blocks.
        val a = file.copyOfRange(header, header + firstBlock)
        val b = file.copyOfRange(header + firstBlock, header + 2 * firstBlock)
        val swapped = file.copyOfRange(0, header) + b + a + file.copyOfRange(header + 2 * firstBlock, file.size)
        assertRefused("blocks swapped") { open(swapped) }

        assertRefused("not a backup at all") { open("hello, this is a text file".toByteArray()) }
        assertArrayEquals("the untouched file still opens", data, open(file))
    }

    private fun assertRefused(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: the file was accepted")
        } catch (_: IOException) {
        } catch (_: WrongSecretException) {
        }
    }

    // ------------------------------------------------------------------ update check

    @Test fun versionNumbersCompareAsNumbers() {
        assertTrue(Updates.isNewer("0.10", "0.9"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertTrue(Updates.isNewer("1.2.1", "1.2"))
        assertFalse(Updates.isNewer("0.3", "0.3"))
        assertFalse(Updates.isNewer("0.2", "0.3"))
        assertFalse(Updates.isNewer("1.2", "1.2.0"))
        assertTrue(Updates.isNewer("v1.1", "1.0"))
    }

    @Test fun theVersionIsReadFromGitHubsReply() {
        assertEquals("1.4", Updates.parse("""{"url":"x","tag_name":"v1.4","name":"Backchannel 1.4"}""")!!.version)
        assertEquals("0.3", Updates.parse("""{ "tag_name" : "0.3" }""")!!.version)
        assertNull(Updates.parse("""{"message":"Not Found"}"""))
        assertNull(Updates.parse("""{"tag_name":"latest"}"""))
    }

    /** A stand-in web server, to check the request the app sends and that it copes with real-world replies. */
    private fun serve(reply: String, seen: StringBuilder): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { s ->
                val inp = s.getInputStream()
                val req = StringBuilder()
                while (!req.endsWith("\r\n\r\n")) {
                    val c = inp.read()
                    if (c < 0) break
                    req.append(c.toChar())
                }
                synchronized(seen) { seen.append(req) }
                s.getOutputStream().apply { write(reply.toByteArray()); flush() }
            }
        }
        return server
    }

    @Test fun aReplySentInPiecesIsPutBackTogether() {
        val seen = StringBuilder()
        val body1 = """{"tag_na"""
        val body2 = """me":"v0.7"}"""
        val reply = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${Integer.toHexString(body1.length)}\r\n$body1\r\n${Integer.toHexString(body2.length)}\r\n$body2\r\n0\r\n\r\n"
        val server = serve(reply, seen)
        val (status, body) = Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            Updates.get(it, "api.github.com", "/repos/${Updates.REPO}/releases/latest")
        }
        assertEquals(200, status)
        assertEquals("0.7", Updates.parse(body)!!.version)
        val request = synchronized(seen) { seen.toString() }
        assertTrue(request.startsWith("GET /repos/CustomGPTer/backchannel/releases/latest HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: api.github.com\r\n"))
        assertTrue(request.contains("User-Agent: Backchannel\r\n"))
        server.close()
    }

    @Test fun nothingPublishedYetIsReportedAsSuch() {
        val server = serve("HTTP/1.1 404 Not Found\r\nContent-Length: 23\r\n\r\n{\"message\":\"Not Found\"}", StringBuilder())
        val (status, _) = Socket(InetAddress.getLoopbackAddress(), server.localPort).use {
            Updates.get(it, "api.github.com", "/x")
        }
        assertEquals(404, status)
        server.close()
    }
}
