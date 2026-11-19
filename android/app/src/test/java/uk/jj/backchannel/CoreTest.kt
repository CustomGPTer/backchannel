package uk.jj.backchannel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Invite
import uk.jj.backchannel.core.Kdf
import uk.jj.backchannel.core.Socks5
import uk.jj.backchannel.core.Strength
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class CoreTest {
    private val onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"

    @Test fun inviteSurvivesBeingPastedInsideAMessage() {
        val invite = Invite(onion, ByteArray(33) { (it + 5).toByte() }, ByteArray(16) { (it * 3).toByte() })
        val code = invite.encode()
        assertTrue(code.startsWith("BC1-"))
        val back = Invite.parse("Add me on Backchannel.\n\n$code\n")
        assertNotNull(back)
        assertEquals(onion, back!!.onion)
        assertArrayEquals(invite.identityKey, back.identityKey)
        assertArrayEquals(invite.token, back.token)
    }

    @Test fun rubbishIsNotAnInvite() {
        assertNull(Invite.parse(""))
        assertNull(Invite.parse("hello there"))
        assertNull(Invite.parse("BC1-" + "A".repeat(141)))
        assertNull(Invite.parse("BC1-" + "!".repeat(142)))
        // Right length, but the address inside is not a valid onion address.
        assertNull(Invite.parse("BC1-" + "A".repeat(142)))
    }

    @Test fun contentRoundTripsAndIsPaddedToAFixedBlock() {
        val id = ByteArray(16) { it.toByte() }
        val short = Content.encode(Content.Text(id, 1234L, "ok"))
        val longer = Content.encode(Content.Text(id, 1234L, "see you at the gate at seven"))
        assertEquals(0, short.size % 160)
        assertEquals("short messages are the same size on the wire", short.size, longer.size)

        val t = Content.decode(longer) as Content.Text
        assertEquals("see you at the gate at seven", t.text)
        assertEquals(1234L, t.sentAt)
        assertArrayEquals(id, t.msgId)

        val emoji = "café 👍 你好"
        assertEquals(emoji, (Content.decode(Content.encode(Content.Text(id, 1, emoji))) as Content.Text).text)

        val h = Content.decode(Content.encode(Content.Hello(ByteArray(16) { 9 }, onion, "Jon"))) as Content.Hello
        assertEquals(onion, h.onion)
        assertEquals("Jon", h.name)
        assertEquals("Sam", (Content.decode(Content.encode(Content.Profile("Sam"))) as Content.Profile).name)
    }

    @Test fun photosVoiceNotesAndTimersRoundTrip() {
        val id = ByteArray(16) { (it + 1).toByte() }
        val data = ByteArray(123_457) { (it * 13).toByte() }
        val m = Content.decode(Content.encode(Content.Media(id, 77L, Content.MEDIA_PHOTO, 1600, 900, 0, "caption ✓", data, 3600))) as Content.Media
        assertArrayEquals(id, m.msgId)
        assertEquals(Content.MEDIA_PHOTO, m.type)
        assertEquals(1600, m.width)
        assertEquals(900, m.height)
        assertEquals("caption ✓", m.caption)
        assertEquals(3600, m.expireS)
        assertArrayEquals(data, m.data)

        val v = Content.decode(Content.encode(Content.Media(id, 77L, Content.MEDIA_VOICE, 0, 0, 12_000, "", byteArrayOf(1, 0, 0), 0))) as Content.Media
        assertEquals(12_000, v.durationMs)
        assertArrayEquals("data ending in zeros must survive the padding", byteArrayOf(1, 0, 0), v.data)

        assertEquals(604_800, (Content.decode(Content.encode(Content.Timer(604_800))) as Content.Timer).seconds)
        assertEquals(0, (Content.decode(Content.encode(Content.Timer(0))) as Content.Timer).seconds)
        assertEquals(300, (Content.decode(Content.encode(Content.Text(id, 1, "x", 300))) as Content.Text).expireS)
    }

    @Test fun aTextMessageFromVersion01StillReads() {
        // Version 0.1 wrote: version, kind, id, time, text. No timer on the end.
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).apply {
            writeByte(1); writeByte(1)
            write(ByteArray(16) { 4 })
            writeLong(99L)
            val t = "old format".toByteArray()
            writeInt(t.size); write(t)
            writeByte(0x80)
            while (bytes.size() % 160 != 0) writeByte(0)
        }
        val t = Content.decode(bytes.toByteArray()) as Content.Text
        assertEquals("old format", t.text)
        assertEquals(0, t.expireS)
    }

    @Test fun nonsenseTimersAndOversizedMediaAreRejected() {
        val id = ByteArray(16)
        for (bad in listOf(
            Content.Timer(-1), Content.Timer(Content.MAX_TIMER_S + 1),
            Content.Text(id, 1, "x", -5),
            Content.Media(id, 1, 9, 1, 1, 0, "", byteArrayOf(1)),
            Content.Media(id, 1, Content.MEDIA_PHOTO, 1, 1, 0, "", ByteArray(Content.MAX_MEDIA + 1)),
            Content.Media(id, 1, Content.MEDIA_PHOTO, 1, 1, 0, "", ByteArray(0)),
        )) {
            try {
                Content.decode(Content.encode(bad))
                fail("should have been rejected: $bad")
            } catch (_: IOException) {
            }
        }
    }

    @Test fun damagedContentIsRejected() {
        try {
            Content.decode(ByteArray(160))
            fail("all zeros should not decode")
        } catch (_: IOException) {
        }
        try {
            Content.decode(byteArrayOf(1, 99, 0x80.toByte()))
            fail("unknown kind should not decode")
        } catch (_: IOException) {
        }
    }

    @Test fun pinStrengthRatings() {
        assertEquals(Strength.Level.TOO_SHORT, Strength.ratePin("12345").level)
        assertEquals(Strength.Level.WEAK, Strength.ratePin("123456").level)
        assertEquals(Strength.Level.WEAK, Strength.ratePin("111111").level)
        assertEquals(Strength.Level.WEAK, Strength.ratePin("987654").level)
        assertEquals("birthdays are flagged", Strength.Level.WEAK, Strength.ratePin("150387").level)
        assertEquals(Strength.Level.FAIR, Strength.ratePin("839471").level)
        assertEquals(Strength.Level.GOOD, Strength.ratePin("83947162").level)
        assertEquals(Strength.Level.STRONG, Strength.ratePin("839471625").level)
        assertEquals(Strength.Level.VERY_STRONG, Strength.ratePin("839471625038").level)
    }

    @Test fun passphraseStrengthRatings() {
        assertEquals(Strength.Level.TOO_SHORT, Strength.ratePassphrase("short").level)
        assertEquals(Strength.Level.WEAK, Strength.ratePassphrase("password").level)
        assertEquals(Strength.Level.WEAK, Strength.ratePassphrase("aaaaaaaaaa").level)
        assertTrue(Strength.ratePassphrase("correct horse battery staple").level >= Strength.Level.STRONG)
        assertTrue(Strength.ratePassphrase("gate7blue").level < Strength.Level.STRONG)
    }

    @Test fun keyDerivationIsRepeatableAndSaltedAndSecretDependent() {
        val p = Kdf.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)
        val salt1 = ByteArray(16) { 1 }
        val salt2 = ByteArray(16) { 2 }
        val a = Kdf.derive("839471".toCharArray(), salt1, p)
        assertEquals(32, a.size)
        assertArrayEquals(a, Kdf.derive("839471".toCharArray(), salt1, p))
        assertFalse(a.contentEquals(Kdf.derive("839471".toCharArray(), salt2, p)))
        assertFalse(a.contentEquals(Kdf.derive("839472".toCharArray(), salt1, p)))
    }

    @Test fun theRealKeyDerivationSettingsAreSlowOnPurposeButUsable() {
        val start = System.nanoTime()
        val key = Kdf.derive("839471".toCharArray(), ByteArray(16) { 3 }, Kdf.DEFAULT)
        val ms = (System.nanoTime() - start) / 1_000_000
        System.err.println("Argon2id with the app's settings (${Kdf.DEFAULT.memoryKiB / 1024} MiB, ${Kdf.DEFAULT.iterations} passes) took $ms ms here")
        assertEquals(32, key.size)
        assertTrue("each guess should cost real work", ms >= 20)
        assertTrue("but unlocking must not take forever", ms < 20_000)
    }

    /** Checks the bytes the app sends to Tor's proxy: the host goes as a name, never an IP lookup. */
    @Test fun socksAsksTheProxyForTheHostByName() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var askedFor = ""
        var askedPort = 0
        val t = thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                assertEquals(5, inp.readUnsignedByte())
                val methods = inp.readUnsignedByte()
                inp.skipBytes(methods)
                out.write(byteArrayOf(5, 0)); out.flush()
                assertEquals(5, inp.readUnsignedByte())
                assertEquals(1, inp.readUnsignedByte())
                inp.readUnsignedByte()
                assertEquals("address type must be 'domain name'", 3, inp.readUnsignedByte())
                val host = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
                askedFor = String(host)
                askedPort = inp.readUnsignedShort()
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                out.write("hi".toByteArray()); out.flush()
            }
        }
        Socks5.connect(server.localPort, "$onion.onion", 5223, 5000).use { s ->
            val got = ByteArray(2)
            DataInputStream(s.getInputStream()).readFully(got)
            assertEquals("hi", String(got))
        }
        t.join(5000)
        assertEquals("$onion.onion", askedFor)
        assertEquals(5223, askedPort)
        server.close()
    }

    @Test fun socksReportsAnUnreachablePhone() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                inp.skipBytes(3)
                out.write(byteArrayOf(5, 0)); out.flush()
                inp.skipBytes(4)
                inp.skipBytes(inp.readUnsignedByte() + 2)
                // 4 = host unreachable
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            }
        }
        try {
            Socks5.connect(server.localPort, "$onion.onion", 5223, 5000).close()
            fail("should have thrown")
        } catch (_: IOException) {
        }
        server.close()
    }
}
