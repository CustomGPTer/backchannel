package uk.jj.backchannel.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * How two phones talk once Tor has connected them.
 *
 * A connection opens with four fixed bytes, then carries frames: a length, a type and a body.
 * Tor already encrypts the connection end to end. On top of that every message body is
 * encrypted again with the Signal Protocol, which is what actually protects it.
 */
internal object Wire {
    val MAGIC = byteArrayOf('B'.code.toByte(), 'C'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
    /** Room for one photo or voice note plus its encryption overhead. */
    const val MAX_FRAME = 2 * 1024 * 1024

    /** "Here is my invite token, please send your keys." Body: token. */
    const val T_BUNDLE_REQ = 1

    /** The keys asked for. Body: a serialised key bundle. */
    const val T_BUNDLE = 2

    /** An encrypted message. Body: sender address (56 bytes), kind (1 byte), ciphertext. */
    const val T_MSG = 3

    /** Answer to a request or message. Body: one status byte. */
    const val T_ACK = 4

    /**
     * "We already know each other, but I have lost our session (restored from a backup, say).
     * Please send fresh keys." Body: sender address (56 bytes), time (8 bytes), signature.
     * Answered with [T_BUNDLE], and only if the signature matches that contact's identity key.
     */
    const val T_REKEY_REQ = 5

    const val ACK_OK = 0
    const val ACK_REFUSED = 1
    const val ACK_UNREADABLE = 2

    class Frame(val type: Int, val body: ByteArray)

    @Throws(IOException::class)
    fun write(out: DataOutputStream, type: Int, body: ByteArray) {
        if (body.size + 1 > MAX_FRAME) throw IOException("frame too large")
        out.writeInt(body.size + 1)
        out.writeByte(type)
        out.write(body)
        out.flush()
    }

    @Throws(IOException::class)
    fun read(inp: DataInputStream): Frame {
        val len = inp.readInt()
        if (len < 1 || len > MAX_FRAME) throw IOException("bad frame length")
        val type = inp.readUnsignedByte()
        val body = ByteArray(len - 1).also { inp.readFully(it) }
        return Frame(type, body)
    }
}
