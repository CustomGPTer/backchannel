package uk.jj.backchannel.core

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens a connection through Tor's local SOCKS port. The host name is passed to Tor as
 * text, so nothing is ever looked up on the ordinary internet.
 */
object Socks5 {
    @Throws(IOException::class)
    fun connect(socksPort: Int, host: String, port: Int, timeoutMs: Int): Socket {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        if (hostBytes.size > 255) throw IOException("host too long")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), 10_000)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inp = DataInputStream(socket.getInputStream())

            // Greeting: version 5, one method offered, "no authentication".
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            if (inp.readUnsignedByte() != 5 || inp.readUnsignedByte() != 0) throw IOException("proxy refused")

            // Request: connect to a named host.
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = (port shr 8).toByte()
            req[6 + hostBytes.size] = port.toByte()
            out.write(req)
            out.flush()

            if (inp.readUnsignedByte() != 5) throw IOException("bad proxy reply")
            val status = inp.readUnsignedByte()
            inp.readUnsignedByte()
            when (inp.readUnsignedByte()) {
                1 -> inp.skipBytes(4)
                3 -> inp.skipBytes(inp.readUnsignedByte())
                4 -> inp.skipBytes(16)
                else -> throw IOException("bad proxy reply")
            }
            inp.skipBytes(2)
            if (status != 0) throw IOException("could not reach the other phone (code $status)")
            return socket
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
    }
}
