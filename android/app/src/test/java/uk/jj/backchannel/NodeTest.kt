package uk.jj.backchannel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Invite
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.Node
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Two or more whole messaging engines talking to each other on this machine. Ordinary local
 * connections stand in for Tor; everything else (keys, encryption, queueing, the checks on
 * who may connect) is the real code.
 */
class NodeTest {

    /** Stands in for the Tor network: looks up an address and connects, or fails if that phone is "off". */
    private class FakeTor : Node.Transport {
        val ports = ConcurrentHashMap<String, Int>()
        val offline: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val sniffed = ByteArrayOutputStream()

        /** While above zero, the next reply coming back is thrown away and the line is cut. */
        val repliesToLose = java.util.concurrent.atomic.AtomicInteger(0)

        override fun connect(onion: String): Socket {
            val port = ports[onion]
            if (port == null || onion in offline) throw IOException("unreachable")
            val upstream = Socket(InetAddress.getLoopbackAddress(), port)
            // Sit in the middle and record everything, like someone tapping the line.
            val tap = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) {
                try {
                    tap.accept().use { down ->
                        tap.close()
                        val a = thread(isDaemon = true) { pump(down.getInputStream(), upstream.getOutputStream(), lossy = false) }
                        pump(upstream.getInputStream(), down.getOutputStream(), lossy = true)
                        a.join(1000)
                    }
                } catch (_: IOException) {
                } finally {
                    try { upstream.close() } catch (_: IOException) {}
                }
            }
            return Socket(InetAddress.getLoopbackAddress(), tap.localPort)
        }

        private fun pump(from: InputStream, to: OutputStream, lossy: Boolean) {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    if (lossy && repliesToLose.get() > 0 && repliesToLose.decrementAndGet() >= 0) break
                    synchronized(sniffed) { sniffed.write(buf, 0, n) }
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: IOException) {
            }
            try { to.close() } catch (_: IOException) {}
        }
    }

    private class Phone(val onion: String, name: String, tor: FakeTor, val clock: AtomicLong, val store: MemStorage = MemStorage()) {
        val incoming = ArrayList<Long>()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val node = Node(
            storage = store,
            transport = tor,
            events = object : Node.Events {
                override fun onIncomingText(contactId: Long) { synchronized(incoming) { incoming += contactId } }
            },
            now = { clock.get() },
            timing = Node.Timing(retryMs = longArrayOf(100, 200), idleMs = 400, ioTimeoutMs = 5000, rekeyGapMs = 0),
            log = { what, e -> if (e !is IOException) System.err.println("[${onion.take(1)}] $what: $e") },
        )

        init {
            node.selfOnion = onion
            node.profileName = name
            tor.ports[onion] = server.localPort
            node.serve(server)
        }

        fun contact(other: Phone): Contact? = store.contactByOnion(other.onion)
        fun texts(other: Phone): List<Message> = contact(other)?.let { store.messagesFor(it.id, 100) }.orEmpty()
        fun close() = node.close()
    }

    private val tor = FakeTor()
    private val clock = AtomicLong(1_800_000_000_000L)
    private val phones = ArrayList<Phone>()

    private fun phone(letter: Char, name: String) = Phone(letter.toString().repeat(56), name, tor, clock).also { phones += it }

    @After fun tearDown() = phones.forEach { it.close() }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** B scans A's code and both end up with each other as a working contact. */
    private fun pair(a: Phone, b: Phone, nameTypedByB: String = "") {
        val code = a.node.invite().encode()
        b.node.addFromInvite(Invite.parse(code)!!, nameTypedByB)
        await("${b.onion.take(1)} to finish adding ${a.onion.take(1)}") {
            b.contact(a)?.state == Contact.ACTIVE && a.contact(b)?.state == Contact.ACTIVE &&
                a.store.contactsWithQueued().isEmpty() && b.store.contactsWithQueued().isEmpty()
        }
    }

    @Test fun scanningACodeAddsEachPersonToTheOther() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        assertEquals("Alice's phone learns Bob's name", "Bob", alice.contact(bob)!!.name)
        await("Bob's phone to learn Alice's name") { bob.contact(alice)?.name == "Alice" }
    }

    @Test fun aNameYouTypeYourselfIsKept() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob, nameTypedByB = "Al from site")
        Thread.sleep(300)
        assertEquals("Al from site", bob.contact(alice)!!.name)
    }

    @Test fun messagesGoBothWaysAndAreMarkedDelivered() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)

        bob.node.sendText(bob.contact(alice)!!.id, "Morning. Gate 3 at seven?")
        await("Alice to get Bob's message") { alice.texts(bob).any { !it.outgoing && it.body == "Morning. Gate 3 at seven?" } }
        await("Bob's copy to show delivered") { bob.texts(alice).single().status == Message.DELIVERED }
        assertEquals(1, alice.contact(bob)!!.unread)
        assertEquals(listOf(alice.contact(bob)!!.id), alice.incoming)

        alice.node.sendText(alice.contact(bob)!!.id, "Yes, see you there.")
        await("Bob to get the reply") { bob.texts(alice).any { !it.outgoing && it.body == "Yes, see you there." } }
        await("Alice's copy to show delivered") { alice.texts(bob).last().status == Message.DELIVERED }

        // A longer back-and-forth keeps working as the keys move on with every message.
        for (i in 1..12) {
            val (from, to) = if (i % 3 == 0) alice to bob else bob to alice
            from.node.sendText(from.contact(to)!!.id, "message $i")
            await("message $i to arrive") { to.texts(from).any { !it.outgoing && it.body == "message $i" } }
        }
        alice.node.markRead(alice.contact(bob)!!.id)
        assertEquals(0, alice.contact(bob)!!.unread)
    }

    @Test fun nothingReadableCrossesTheWire() {
        val alice = phone('a', "Alice Example")
        val bob = phone('b', "Bob Example")
        pair(alice, bob)
        val secret = "the consignment lands thursday 0400"
        bob.node.sendText(bob.contact(alice)!!.id, secret)
        await("delivery") { alice.texts(bob).any { it.body == secret } }

        val wire = synchronized(tor.sniffed) { tor.sniffed.toByteArray() }
        assertTrue("the tap should have captured traffic", wire.size > 500)
        val seen = String(wire, Charsets.ISO_8859_1)
        assertFalse("message text was visible on the wire", seen.contains(secret))
        assertFalse("a name was visible on the wire", seen.contains("Alice Example") || seen.contains("Bob Example"))
    }

    @Test fun messagesWaitWhileTheOtherPhoneIsOffThenArriveInOrder() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)

        tor.offline += alice.onion
        Thread.sleep(700) // let the line that was open between them close, as it would if her phone went off
        val toAlice = bob.contact(alice)!!.id
        for (i in 1..5) bob.node.sendText(toAlice, "queued $i")
        Thread.sleep(700)
        assertTrue("messages must stay waiting while she is offline", bob.texts(alice).all { it.status == Message.QUEUED })
        assertTrue(alice.texts(bob).isEmpty())

        tor.offline -= alice.onion
        await("queued messages to arrive once she is back") { alice.texts(bob).size == 5 }
        assertEquals((1..5).map { "queued $it" }, alice.texts(bob).map { it.body })
        await("all five marked delivered") { bob.texts(alice).all { it.status == Message.DELIVERED } }
    }

    @Test fun aMessageIsNeverShownTwiceEvenIfTheConfirmationIsLost() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        Thread.sleep(700)
        // Alice's phone receives the message, but its "got it" reply never reaches Bob's phone,
        // so Bob's phone sends the message again.
        tor.repliesToLose.set(1)
        bob.node.sendText(bob.contact(alice)!!.id, "only once please")
        await("Bob's copy to show delivered after the retry") { bob.texts(alice).single().status == Message.DELIVERED }
        assertEquals(0, tor.repliesToLose.get())
        Thread.sleep(300)
        assertEquals("Alice must see it exactly once", listOf("only once please"), alice.texts(bob).map { it.body })
        assertEquals(1, alice.contact(bob)!!.unread)
    }

    @Test fun aCodeAddedWhileTheOtherPhoneIsOffCompletesLater() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        val code = alice.node.invite().encode()
        tor.offline += alice.onion
        val id = bob.node.addFromInvite(Invite.parse(code)!!, "")
        bob.node.sendText(id, "sent before we were connected")
        Thread.sleep(500)
        assertEquals(Contact.PENDING, bob.contact(alice)!!.state)
        assertNull(alice.contact(bob))

        tor.offline -= alice.onion
        await("pairing to complete") { alice.contact(bob)?.state == Contact.ACTIVE && bob.contact(alice)?.state == Contact.ACTIVE }
        await("the early message to arrive") { alice.texts(bob).any { it.body == "sent before we were connected" } }
    }

    @Test fun aCodeWorksOnlyOnce() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        val carol = phone('c', "Carol")
        val code = alice.node.invite().encode()
        bob.node.addFromInvite(Invite.parse(code)!!, "")
        await("Bob added") { alice.contact(bob)?.state == Contact.ACTIVE && bob.contact(alice)?.state == Contact.ACTIVE }

        carol.node.addFromInvite(Invite.parse(code)!!, "")
        await("Carol to be turned away") { carol.contact(alice)?.state == Contact.REFUSED }
        assertNull("Alice must not have Carol as a contact", alice.contact(carol))

        // After a code has been used, a fresh one is offered next time.
        assertFalse(alice.node.invite().encode() == code)
    }

    @Test fun aCodeStopsWorkingAfterSevenDays() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        val code = alice.node.invite().encode()
        clock.addAndGet(Node.INVITE_LIFE_MS + 60_000)
        bob.node.addFromInvite(Invite.parse(code)!!, "")
        await("Bob to be turned away") { bob.contact(alice)?.state == Contact.REFUSED }
        assertNull(alice.contact(bob))
    }

    @Test fun aCodeThatPointsAtTheWrongPhoneIsRefused() {
        val alice = phone('a', "Alice")
        val mallory = phone('m', "Mallory")
        val bob = phone('b', "Bob")
        // Mallory hands Bob a code carrying Alice's identity but Mallory's own address and token,
        // hoping to sit in the middle.
        val real = Invite.parse(alice.node.invite().encode())!!
        val fake = Invite.parse(mallory.node.invite().encode())!!
        val forged = Invite(fake.onion, real.identityKey, fake.token)
        bob.node.addFromInvite(forged, "Alice")
        await("Bob's phone to refuse the forged code") { bob.contact(mallory)?.state == Contact.REFUSED }
        assertNull(mallory.contact(bob))
        assertNull(alice.contact(bob))
    }

    @Test fun strangersCannotGetAnythingIn() {
        val alice = phone('a', "Alice")
        val before = alice.store.contacts().size

        fun attempt(type: Int, body: ByteArray): Int? {
            Socket(InetAddress.getLoopbackAddress(), alice.server.localPort).use { s ->
                s.soTimeout = 3000
                val out = DataOutputStream(s.getOutputStream())
                out.write("BC01".toByteArray())
                out.writeInt(body.size + 1); out.writeByte(type); out.write(body); out.flush()
                val inp = DataInputStream(s.getInputStream())
                return try {
                    val len = inp.readInt()
                    val t = inp.readUnsignedByte()
                    val b = ByteArray(len - 1).also { inp.readFully(it) }
                    if (t == 4) b[0].toInt() else -t
                } catch (_: IOException) {
                    null
                }
            }
        }

        // Asking for keys with a made-up token.
        assertEquals("made-up token must be refused", 1, attempt(1, ByteArray(16) { 7 }))
        // A "message" from an address Alice has never added, in both message formats.
        val junk = "z".repeat(56).toByteArray() + byteArrayOf(3) + ByteArray(200) { it.toByte() }
        assertEquals(1, attempt(3, junk))
        val junk2 = "z".repeat(56).toByteArray() + byteArrayOf(2) + ByteArray(200) { it.toByte() }
        assertEquals(1, attempt(3, junk2))
        // Wrong opening bytes: the connection is just dropped.
        Socket(InetAddress.getLoopbackAddress(), alice.server.localPort).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            assertEquals(-1, s.getInputStream().read())
        }
        assertEquals("no contact may appear", before, alice.store.contacts().size)
        assertTrue(alice.incoming.isEmpty())
    }

    @Test fun someonePretendingToBeAContactIsRejected() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        // An outsider who knows both addresses sends bytes claiming to come from Bob.
        Socket(InetAddress.getLoopbackAddress(), alice.server.localPort).use { s ->
            s.soTimeout = 3000
            val out = DataOutputStream(s.getOutputStream())
            val body = bob.onion.toByteArray() + byteArrayOf(2) + ByteArray(300) { (it * 7).toByte() }
            out.write("BC01".toByteArray())
            out.writeInt(body.size + 1); out.writeByte(3); out.write(body); out.flush()
            val inp = DataInputStream(s.getInputStream())
            inp.readInt(); inp.readUnsignedByte()
            assertEquals("forged message must be marked unreadable", 2, inp.readUnsignedByte())
        }
        assertTrue(alice.texts(bob).isEmpty())
        // And the real Bob can still talk to Alice afterwards.
        bob.node.sendText(bob.contact(alice)!!.id, "still me")
        await("real message after the forgery") { alice.texts(bob).any { it.body == "still me" } }
    }

    @Test fun theConversationSurvivesBothAppsRestarting() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        bob.node.sendText(bob.contact(alice)!!.id, "before restart")
        await("first message") { alice.texts(bob).any { it.body == "before restart" } }
        await("delivered") { bob.texts(alice).all { it.status == Message.DELIVERED } }

        // Close both and start again from what was saved, as after a reboot.
        alice.close(); bob.close()
        phones.clear()
        val alice2 = Phone(alice.onion, "Alice", tor, clock, alice.store).also { phones += it }
        val bob2 = Phone(bob.onion, "Bob", tor, clock, bob.store).also { phones += it }

        alice2.node.sendText(alice2.contact(bob2)!!.id, "after restart")
        await("message after restart") { bob2.texts(alice2).any { !it.outgoing && it.body == "after restart" } }
        bob2.node.sendText(bob2.contact(alice2)!!.id, "and back")
        await("reply after restart") { alice2.texts(bob2).any { !it.outgoing && it.body == "and back" } }
    }

    @Test fun aMessageLeftWaitingIsSentAfterARestart() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        tor.offline += alice.onion
        bob.node.sendText(bob.contact(alice)!!.id, "written while she was off")
        Thread.sleep(300)
        bob.close()
        phones.remove(bob)
        tor.offline -= alice.onion

        val bob2 = Phone(bob.onion, "Bob", tor, clock, bob.store).also { phones += it }
        bob2.node.kick()
        await("the waiting message to go after restart") { alice.texts(bob2).any { it.body == "written while she was off" } }
    }

    @Test fun deletingAContactRemovesTheirMessagesAndKeys() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        bob.node.sendText(bob.contact(alice)!!.id, "hello")
        await("delivery") { alice.texts(bob).isNotEmpty() }

        val id = alice.contact(bob)!!.id
        alice.node.deleteContact(id)
        assertNull(alice.contact(bob))
        assertTrue(alice.store.messagesFor(id, 10).isEmpty())
        assertTrue("session and identity must be wiped", alice.store.kvKeys("ss:").isEmpty() && alice.store.kvKeys("id:").isEmpty())

        // Bob's next message is turned away: to Alice's phone he is now a stranger.
        bob.node.sendText(bob.contact(alice)!!.id, "are you there?")
        await("Bob's message to be marked not delivered") { bob.texts(alice).last().status == Message.FAILED }
        assertNull(alice.contact(bob))
    }

    // ------------------------------------------------------------------ photos and voice notes

    @Test fun aPhotoArrivesByteForByteWithItsCaption() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        val photo = ByteArray(600_000).also { java.util.Random(1).nextBytes(it) }
        bob.node.sendMedia(bob.contact(alice)!!.id, Content.MEDIA_PHOTO, photo, 1600, 1200, 0, "the chamber cover")
        await("the photo to arrive") { alice.texts(bob).any { it.kind == Message.KIND_PHOTO } }
        val got = alice.texts(bob).single()
        assertEquals("the chamber cover", got.body)
        assertFalse(got.outgoing)
        val info = alice.store.attachmentInfo(got.id)!!
        assertEquals(1600, info.width)
        assertEquals(1200, info.height)
        assertTrue("photo must arrive unchanged", photo.contentEquals(alice.store.attachmentData(got.id)))
        await("Bob's copy to show delivered") { bob.texts(alice).single().status == Message.DELIVERED }
        assertEquals(1, alice.contact(bob)!!.unread)
        assertEquals(1, alice.incoming.size)
        // Bob still has his own copy to look at.
        assertTrue(photo.contentEquals(bob.store.attachmentData(bob.texts(alice).single().id)))
    }

    @Test fun aVoiceNoteArrivesWithItsLength() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        val audio = ByteArray(90_000) { (it * 31).toByte() }
        alice.node.sendMedia(alice.contact(bob)!!.id, Content.MEDIA_VOICE, audio, 0, 0, 23_500)
        await("the voice note to arrive") { bob.texts(alice).any { it.kind == Message.KIND_VOICE } }
        val got = bob.texts(alice).single()
        assertEquals(23_500, bob.store.attachmentInfo(got.id)!!.durationMs)
        assertTrue(audio.contentEquals(bob.store.attachmentData(got.id)))
    }

    @Test fun aPhotoSentWhileTheOtherPhoneIsOffWaitsThenArrives() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        tor.offline += alice.onion
        Thread.sleep(700)
        val photo = ByteArray(200_000) { (it * 7).toByte() }
        bob.node.sendMedia(bob.contact(alice)!!.id, Content.MEDIA_PHOTO, photo, 800, 600, 0)
        bob.node.sendText(bob.contact(alice)!!.id, "did you get the photo?")
        Thread.sleep(500)
        assertTrue(bob.texts(alice).all { it.status == Message.QUEUED })
        tor.offline -= alice.onion
        await("both to arrive, photo first") {
            alice.texts(bob).map { it.kind } == listOf(Message.KIND_PHOTO, Message.KIND_TEXT)
        }
        assertTrue(photo.contentEquals(alice.store.attachmentData(alice.texts(bob).first().id)))
    }

    @Test fun somethingTooBigIsRefusedBeforeItIsQueued() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        try {
            bob.node.sendMedia(bob.contact(alice)!!.id, Content.MEDIA_PHOTO, ByteArray(Content.MAX_MEDIA + 1), 10, 10, 0)
            throw AssertionError("oversized photo accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(bob.texts(alice).isEmpty())
    }

    // ------------------------------------------------------------------ disappearing messages

    @Test fun theTimerIsSharedAndMessagesDisappearFromBothPhones() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        val toAlice = bob.contact(alice)!!.id
        val toBob = alice.contact(bob)!!.id

        bob.node.setTimer(toAlice, 3600)
        await("Alice's phone to pick up the timer") { alice.contact(bob)!!.timerS == 3600 }
        assertEquals(3600, bob.contact(alice)!!.timerS)
        // Each chat records the change: Bob's as his own, Alice's as coming from Bob.
        assertEquals(listOf(Message.KIND_NOTE to true), bob.texts(alice).map { it.kind to it.outgoing })
        assertEquals(listOf(Message.KIND_NOTE to false), alice.texts(bob).map { it.kind to it.outgoing })
        assertEquals("3600", alice.texts(bob).single().body)

        bob.node.sendText(toAlice, "burn after reading")
        bob.node.sendMedia(toAlice, Content.MEDIA_PHOTO, ByteArray(5000) { 3 }, 10, 10, 0)
        await("both delivered") { bob.texts(alice).count { it.status == Message.DELIVERED && it.kind != Message.KIND_NOTE } == 2 }
        await("both received") { alice.texts(bob).size == 3 }
        val t0 = clock.get()
        assertTrue("Bob's countdown starts at delivery", bob.texts(alice).filter { it.kind != Message.KIND_NOTE }.all { it.expiresAt == t0 + 3_600_000 })
        assertTrue("Alice's has not started: she hasn't seen them", alice.texts(bob).all { it.expiresAt == 0L })

        // Two hours pass. Bob's copies go. Alice's stay, because she still hasn't opened the chat.
        clock.addAndGet(2 * 3_600_000L)
        bob.node.sweep(); alice.node.sweep()
        assertEquals(listOf(Message.KIND_NOTE), bob.texts(alice).map { it.kind })
        assertEquals(3, alice.texts(bob).size)

        // She opens the chat. An hour later her copies are gone too, photo included.
        val photoId = alice.texts(bob).first { it.kind == Message.KIND_PHOTO }.id
        alice.node.markRead(toBob)
        clock.addAndGet(3_599_000L)
        alice.node.sweep()
        assertEquals(3, alice.texts(bob).size)
        clock.addAndGet(2_000L)
        alice.node.sweep()
        assertEquals(listOf(Message.KIND_NOTE), alice.texts(bob).map { it.kind })
        assertNull(alice.store.attachmentData(photoId))

        // Either person can change it. Alice turns it off; Bob's phone follows.
        alice.node.setTimer(toBob, 0)
        await("Bob's phone to see it turned off") { bob.contact(alice)!!.timerS == 0 }
        bob.node.sendText(toAlice, "this one stays")
        await("delivery") { alice.texts(bob).any { it.body == "this one stays" } }
        alice.node.markRead(toBob)
        clock.addAndGet(30L * 24 * 3_600_000)
        alice.node.sweep(); bob.node.sweep()
        assertTrue(alice.texts(bob).any { it.body == "this one stays" })
        assertTrue(bob.texts(alice).any { it.body == "this one stays" })
    }

    @Test fun aMessageNeverDisappearsWhileItIsStillWaitingToBeSent() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        tor.offline += alice.onion
        Thread.sleep(700)
        val toAlice = bob.contact(alice)!!.id
        bob.node.setTimer(toAlice, 300)
        bob.node.sendText(toAlice, "five minute message")
        clock.addAndGet(3 * 24 * 3_600_000L)
        bob.node.sweep()
        assertEquals("still waiting after three days", Message.QUEUED, bob.texts(alice).last().status)

        tor.offline -= alice.onion
        await("it to arrive, with the timer") { alice.texts(bob).any { it.body == "five minute message" && it.expireS == 300 } }
        assertEquals(300, alice.contact(bob)!!.timerS)
        await("Bob's countdown to start at delivery") { bob.texts(alice).last().expiresAt == clock.get() + 300_000 }
    }

    @Test fun ifATimerChangeWasMissedTheNextMessageBringsBothPhonesBackInStep() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        // Bob's phone has a timer that Alice's never heard about.
        bob.store.contactSetTimer(bob.contact(alice)!!.id, 86_400)
        bob.node.sendText(bob.contact(alice)!!.id, "hello")
        await("Alice's phone to follow the message's timer") { alice.contact(bob)!!.timerS == 86_400 }
        assertEquals(86_400, alice.texts(bob).first { it.kind == Message.KIND_TEXT }.expireS)
        assertTrue("and to note the change in the chat", alice.texts(bob).any { it.kind == Message.KIND_NOTE && it.body == "86400" })
    }

    @Test fun linkingToTheAddressBookSetsNameAndPictureOnThisPhoneOnly() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        Thread.sleep(700)
        val before = synchronized(tor.sniffed) { tor.sniffed.size() }
        alice.node.linkContact(alice.contact(bob)!!.id, "Bob from Maher", byteArrayOf(1, 2, 3))
        assertEquals("Bob from Maher", alice.contact(bob)!!.name)
        assertTrue(alice.contact(bob)!!.customName)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(alice.contact(bob)!!.photo))
        Thread.sleep(500)
        assertEquals("nothing may be sent when linking a contact", before, synchronized(tor.sniffed) { tor.sniffed.size() })
        assertTrue(alice.store.contactsWithQueued().isEmpty())
    }

    // ------------------------------------------------------------------ backup and restore

    @Test fun afterRestoringAnOlderBackupBothPhonesRecoverByThemselves() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        pair(alice, bob)
        bob.node.sendText(bob.contact(alice)!!.id, "before the backup")
        await("first message") { alice.texts(bob).any { it.body == "before the backup" } }
        await("settled") { bob.store.contactsWithQueued().isEmpty() && alice.store.contactsWithQueued().isEmpty() }

        // Alice makes her backup here...
        val backup = alice.store.snapshot()

        // ...and then they carry on talking, so the keys in the backup go out of date.
        for (i in 1..6) {
            val (from, to) = if (i % 2 == 0) alice to bob else bob to alice
            from.node.sendText(from.contact(to)!!.id, "after the backup $i")
            await("later message $i") { to.texts(from).any { it.body == "after the backup $i" } }
        }
        await("settled") { bob.store.contactsWithQueued().isEmpty() && alice.store.contactsWithQueued().isEmpty() }

        // Alice's phone is lost. She restores the backup on a new one.
        alice.close()
        phones.remove(alice)
        Node.prepareRestored(backup, clock.get())
        val alice2 = Phone(alice.onion, "Alice", tor, clock, backup).also { phones += it }

        // Bob writes first, still using the old keys. His phone finds out they no longer work,
        // sets up fresh ones with Alice's new phone, and sends the message again.
        bob.node.sendText(bob.contact(alice2)!!.id, "are you back?")
        await("Bob's message to reach the restored phone") { alice2.texts(bob).any { it.body == "are you back?" } }
        await("and to show delivered, not failed") { bob.texts(alice2).last().status == Message.DELIVERED }

        alice2.node.sendText(alice2.contact(bob)!!.id, "yes, new phone")
        await("the reply to arrive") { bob.texts(alice2).any { !it.outgoing && it.body == "yes, new phone" } }

        // What was in the backup is still there; what came after it is not, as expected.
        assertTrue(alice2.texts(bob).any { it.body == "before the backup" })
        assertFalse(alice2.texts(bob).any { it.body.startsWith("after the backup") })
        // And the conversation keeps working in both directions.
        for (i in 1..4) {
            val (from, to) = if (i % 2 == 0) alice2 to bob else bob to alice2
            from.node.sendText(from.contact(to)!!.id, "carrying on $i")
            await("carrying on $i") { to.texts(from).any { it.body == "carrying on $i" } }
        }
        assertTrue(bob.texts(alice2).none { it.status == Message.FAILED })
        assertTrue(alice2.texts(bob).none { it.status == Message.FAILED })
    }

    @Test fun freshKeysAreOnlyHandedToARealContact() {
        val alice = phone('a', "Alice")
        val bob = phone('b', "Bob")
        val mallory = phone('m', "Mallory")
        pair(alice, bob)

        fun ask(from: String, signWith: Phone): Int {
            val at = clock.get()
            val statement = "backchannel rekey v1|$from|${alice.onion}|$at".toByteArray()
            val identity = org.signal.libsignal.protocol.IdentityKeyPair(signWith.store.kvGet("self:identity")!!)
            val sig = identity.privateKey.calculateSignature(statement)
            val body = java.io.ByteArrayOutputStream()
            DataOutputStream(body).apply { write(from.toByteArray()); writeLong(at); write(sig) }
            Socket(InetAddress.getLoopbackAddress(), alice.server.localPort).use { s ->
                s.soTimeout = 3000
                val out = DataOutputStream(s.getOutputStream())
                out.write("BC01".toByteArray())
                out.writeInt(body.size() + 1); out.writeByte(5); out.write(body.toByteArray()); out.flush()
                val inp = DataInputStream(s.getInputStream())
                inp.readInt()
                return inp.readUnsignedByte()
            }
        }
        assertEquals("a stranger is refused", 4, ask(mallory.onion, mallory))
        assertEquals("someone claiming to be Bob without Bob's key is refused", 4, ask(bob.onion, mallory))
        assertEquals("the real Bob is given keys", 2, ask(bob.onion, bob))
    }

    @Test fun yourOwnCodeIsRejected() {
        val alice = phone('a', "Alice")
        try {
            alice.node.addFromInvite(Invite.parse(alice.node.invite().encode())!!, "")
            throw AssertionError("own code accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertNotNull(alice.node.selfOnion)
    }
}
