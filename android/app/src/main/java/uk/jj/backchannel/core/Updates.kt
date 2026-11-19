package uk.jj.backchannel.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Asks GitHub which version of Backchannel is the newest published one. The request goes
 * out through Tor like everything else, so GitHub sees a Tor address, not the phone's.
 *
 * This only reports that a newer version exists. Installing it is left to Android, which
 * refuses any update that is not signed with the same key as the app already installed.
 */
object Updates {
    const val REPO = "CustomGPTer/backchannel"
    const val DOWNLOAD_PAGE = "https://github.com/$REPO/releases/latest"
    private const val HOST = "api.github.com"

    class Release(val version: String)

    /**
     * @param connect opens a connection to a named host and port (through Tor, in the app).
     * @return the newest published release, or null if nothing has been published.
     */
    @Throws(IOException::class)
    fun latest(connect: (host: String, port: Int) -> Socket): Release? {
        connect(HOST, 443).use { raw ->
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
            // Check that the certificate really is GitHub's, not just any valid one.
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            val (status, body) = get(tls, HOST, "/repos/$REPO/releases/latest")
            return when (status) {
                200 -> parse(body) ?: throw IOException("unexpected reply from GitHub")
                404 -> null
                else -> throw IOException("GitHub answered $status")
            }
        }
    }

    /** One plain HTTP request and reply over a connection that is already open. */
    @Throws(IOException::class)
    fun get(socket: Socket, host: String, path: String): Pair<Int, String> {
        val request = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Backchannel\r\n" +
            "Accept: application/vnd.github+json\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val all = readAll(socket.getInputStream(), 512 * 1024)
        val split = indexOf(all, "\r\n\r\n".toByteArray())
        if (split < 0) throw IOException("bad reply")
        val head = String(all, 0, split, Charsets.ISO_8859_1)
        val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(head)?.groupValues?.get(1)?.toInt() ?: throw IOException("bad reply")
        var body = all.copyOfRange(split + 4, all.size)
        if (Regex("(?im)^transfer-encoding:\\s*chunked").containsMatchIn(head)) body = unchunk(body)
        return status to String(body, Charsets.UTF_8)
    }

    fun parse(json: String): Release? {
        val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]{1,40})\"").find(json)?.groupValues?.get(1) ?: return null
        val version = tag.trim().removePrefix("v").removePrefix("V")
        return if (numbers(version).isEmpty()) null else Release(version)
    }

    /** True if [latest] is a higher version number than [current]: 0.10 is newer than 0.9. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.removePrefix("v").split('.', '-', ' ').mapNotNull { it.toIntOrNull() }

    private fun unchunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val lineEnd = indexOf(data, "\r\n".toByteArray(), at)
            if (lineEnd < 0) break
            val size = String(data, at, lineEnd - at, Charsets.US_ASCII).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad reply")
            if (size == 0) break
            val start = lineEnd + 2
            if (start + size > data.size) throw IOException("reply cut short")
            out.write(data, start, size)
            at = start + size + 2
        }
        return out.toByteArray()
    }

    private fun readAll(inp: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("reply too large")
        }
        return out.toByteArray()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
