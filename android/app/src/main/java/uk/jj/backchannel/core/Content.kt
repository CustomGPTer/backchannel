package uk.jj.backchannel.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The readable part of a message, before it is encrypted. */
sealed class Content {
    /** An ordinary chat message. [expireS] is the disappearing timer in force when it was written; 0 = keep. */
    class Text(val msgId: ByteArray, val sentAt: Long, val text: String, val expireS: Int = 0) : Content()

    /** The first message from someone who scanned an invite: "this is me, add me". */
    class Hello(val token: ByteArray, val onion: String, val name: String) : Content()

    /** "This is the name I go by." */
    class Profile(val name: String) : Content()

    /** A photo or a voice note, carried inside the encrypted message itself. */
    class Media(
        val msgId: ByteArray,
        val sentAt: Long,
        val type: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        val caption: String,
        val data: ByteArray,
        val expireS: Int = 0,
    ) : Content()

    /** "I have changed this chat's disappearing-message timer." 0 = off. */
    class Timer(val seconds: Int) : Content()

    companion object {
        private const val VERSION = 1
        private const val K_TEXT = 1
        private const val K_HELLO = 2
        private const val K_PROFILE = 3
        private const val K_MEDIA = 4
        private const val K_TIMER = 5

        const val MEDIA_PHOTO = 1
        const val MEDIA_VOICE = 2

        /** Messages are padded to a multiple of this, so their length gives little away. */
        private const val BLOCK = 160
        const val MAX_TEXT = 8000
        const val MAX_NAME = 60

        /** The largest photo or voice note that will be sent or accepted. */
        const val MAX_MEDIA = 1_000_000

        /** Four weeks: the longest disappearing timer. */
        const val MAX_TIMER_S = 28 * 24 * 60 * 60

        fun encode(c: Content): ByteArray {
            val bytes = ByteArrayOutputStream()
            val out = DataOutputStream(bytes)
            out.writeByte(VERSION)
            when (c) {
                is Text -> {
                    out.writeByte(K_TEXT)
                    out.write(c.msgId)
                    out.writeLong(c.sentAt)
                    writeString(out, c.text)
                    out.writeInt(c.expireS)
                }
                is Hello -> {
                    out.writeByte(K_HELLO)
                    out.write(c.token)
                    writeString(out, c.onion)
                    writeString(out, c.name)
                }
                is Profile -> {
                    out.writeByte(K_PROFILE)
                    writeString(out, c.name)
                }
                is Media -> {
                    out.writeByte(K_MEDIA)
                    out.write(c.msgId)
                    out.writeLong(c.sentAt)
                    out.writeByte(c.type)
                    out.writeInt(c.width)
                    out.writeInt(c.height)
                    out.writeInt(c.durationMs)
                    out.writeInt(c.expireS)
                    writeString(out, c.caption)
                    out.writeInt(c.data.size)
                    out.write(c.data)
                }
                is Timer -> {
                    out.writeByte(K_TIMER)
                    out.writeInt(c.seconds)
                }
            }
            out.writeByte(0x80)
            while (bytes.size() % BLOCK != 0) out.writeByte(0)
            return bytes.toByteArray()
        }

        @Throws(IOException::class)
        fun decode(padded: ByteArray): Content {
            var end = padded.size - 1
            while (end >= 0 && padded[end].toInt() == 0) end--
            if (end < 0 || padded[end] != 0x80.toByte()) throw IOException("bad padding")
            val inp = DataInputStream(ByteArrayInputStream(padded, 0, end))
            if (inp.readUnsignedByte() != VERSION) throw IOException("unknown version")
            return when (inp.readUnsignedByte()) {
                K_TEXT -> {
                    val id = ByteArray(16).also { inp.readFully(it) }
                    val at = inp.readLong()
                    val text = readString(inp, MAX_TEXT * 4)
                    // Version 0.1 did not send a timer; its messages simply end here.
                    val expire = if (inp.available() >= 4) timer(inp.readInt()) else 0
                    Text(id, at, text, expire)
                }
                K_HELLO -> {
                    val token = ByteArray(Invite.TOKEN_LEN).also { inp.readFully(it) }
                    val onion = readString(inp, 56)
                    Hello(token, onion, readString(inp, MAX_NAME * 4).take(MAX_NAME))
                }
                K_PROFILE -> Profile(readString(inp, MAX_NAME * 4).take(MAX_NAME))
                K_MEDIA -> {
                    val id = ByteArray(16).also { inp.readFully(it) }
                    val at = inp.readLong()
                    val type = inp.readUnsignedByte()
                    if (type != MEDIA_PHOTO && type != MEDIA_VOICE) throw IOException("unknown media type")
                    val w = inp.readInt()
                    val h = inp.readInt()
                    val dur = inp.readInt()
                    if (w !in 0..20_000 || h !in 0..20_000 || dur !in 0..3_600_000) throw IOException("bad media size")
                    val expire = timer(inp.readInt())
                    val caption = readString(inp, MAX_TEXT * 4)
                    val n = inp.readInt()
                    if (n < 1 || n > MAX_MEDIA) throw IOException("bad media length")
                    val data = ByteArray(n).also { inp.readFully(it) }
                    Media(id, at, type, w, h, dur, caption, data, expire)
                }
                K_TIMER -> Timer(timer(inp.readInt()))
                else -> throw IOException("unknown kind")
            }
        }

        private fun timer(seconds: Int): Int {
            if (seconds < 0 || seconds > MAX_TIMER_S) throw IOException("bad timer")
            return seconds
        }

        private fun writeString(out: DataOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.writeInt(b.size)
            out.write(b)
        }

        private fun readString(inp: DataInputStream, maxBytes: Int): String {
            val n = inp.readInt()
            if (n < 0 || n > maxBytes) throw IOException("bad length")
            val b = ByteArray(n).also { inp.readFully(it) }
            return String(b, Charsets.UTF_8)
        }
    }
}
