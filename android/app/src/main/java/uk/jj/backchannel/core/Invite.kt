package uk.jj.backchannel.core

import java.io.ByteArrayOutputStream

/**
 * What one person hands to another (as a QR code or a line of text) so they can be added.
 *
 * It holds where to find the inviter on Tor, the inviter's public identity key (so the
 * scanner knows it is talking to the right phone), and a single-use token that lets the
 * scanner ask the inviter's phone for the rest of its keys.
 */
class Invite(val onion: String, val identityKey: ByteArray, val token: ByteArray) {

    init {
        require(isOnion(onion)) { "bad address" }
        require(identityKey.size == IDENTITY_LEN) { "bad identity key" }
        require(token.size == TOKEN_LEN) { "bad token" }
    }

    fun encode(): String {
        val out = ByteArrayOutputStream(1 + 56 + IDENTITY_LEN + TOKEN_LEN)
        out.write(VERSION)
        out.write(onion.toByteArray(Charsets.US_ASCII))
        out.write(identityKey)
        out.write(token)
        return PREFIX + Bytes.b64(out.toByteArray())
    }

    companion object {
        const val PREFIX = "BC1-"
        const val TOKEN_LEN = 16
        const val IDENTITY_LEN = 33
        private const val VERSION = 1
        private val FINDER = Regex("BC1-[A-Za-z0-9_-]{142}")

        /** Reads an invite out of anything pasted or scanned. Returns null if there isn't one. */
        fun parse(text: String): Invite? {
            val m = FINDER.find(text) ?: return null
            return try {
                val raw = Bytes.unb64(m.value.removePrefix(PREFIX))
                if (raw.size != 1 + 56 + IDENTITY_LEN + TOKEN_LEN || raw[0].toInt() != VERSION) return null
                val onion = String(raw, 1, 56, Charsets.US_ASCII)
                val id = raw.copyOfRange(57, 57 + IDENTITY_LEN)
                val token = raw.copyOfRange(57 + IDENTITY_LEN, raw.size)
                Invite(onion, id, token)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
