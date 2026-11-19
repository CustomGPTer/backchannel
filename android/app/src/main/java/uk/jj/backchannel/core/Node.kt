package uk.jj.backchannel.core

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The messaging engine. It knows nothing about Tor or Android: it is given a way to open a
 * connection to another phone's address and a socket to listen on, and does the rest.
 *
 *  - Outgoing messages are saved first, then sent when the other phone can be reached.
 *    A message only counts as delivered once the other phone has confirmed it.
 *  - Incoming connections are only acted on if they carry a valid invite token or a message
 *    that decrypts under an existing contact's keys. Everything else is dropped.
 */
class Node(
    private val storage: Storage,
    private val transport: Transport,
    private val events: Events = object : Events {},
    private val now: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
    /** Receives a short note whenever something goes wrong. Never given message contents or keys. */
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : Closeable {

    fun interface Transport {
        /** Opens a connection to the phone at this onion address, or throws if it can't be reached. */
        @Throws(IOException::class)
        fun connect(onion: String): Socket
    }

    interface Events {
        /** Something on screen may need redrawing. */
        fun onChanged() {}
        fun onIncomingText(contactId: Long) {}
        fun onContactAdded(contactId: Long) {}
    }

    class Timing(
        /** How long to wait before trying an unreachable phone again; the last value repeats. */
        val retryMs: LongArray = longArrayOf(10_000, 20_000, 40_000, 80_000, 160_000, 300_000),
        /** How long an open connection is kept with nothing to send. */
        val idleMs: Long = 120_000,
        val ioTimeoutMs: Int = 90_000,
        /** The shortest gap allowed between two requests for fresh keys from the same contact. */
        val rekeyGapMs: Long = 20_000,
    )

    private val lock = Any()
    private val store = SignalStore(storage)
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "bc-node").apply { isDaemon = true }
    }
    private val workers = HashMap<Long, Worker>()
    private val incomingSlots = Semaphore(MAX_INCOMING)

    /** Messages that have already had one automatic "start a fresh session and send again". */
    private val retried: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val lastRekey = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Every connection currently open, in or out, so they can all be cut the moment the engine stops. */
    private val openSockets: MutableSet<Socket> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile private var closed = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var onion: String? = storage.kvGet(Keys.K_ONION)?.let { String(it, Charsets.US_ASCII) }

    init {
        Keys.ensureIdentity(storage, now())
    }

    // ------------------------------------------------------------------ this phone

    /** This phone's own Tor address, once Tor has created it. */
    var selfOnion: String?
        get() = onion
        set(value) {
            requireNotNull(value)
            require(isOnion(value))
            storage.kvPut(Keys.K_ONION, value.toByteArray(Charsets.US_ASCII))
            onion = value
            kick()
        }

    var profileName: String
        get() = storage.kvGet(Keys.K_NAME)?.let { String(it, Charsets.UTF_8) } ?: ""
        set(value) = storage.kvPut(Keys.K_NAME, value.trim().take(Content.MAX_NAME).toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------------ adding people

    /** The invite to show as a QR code or share as text. One invite adds one person. */
    fun invite(fresh: Boolean = false): Invite {
        val self = onion ?: throw IllegalStateException("Tor has not started yet")
        val token = synchronized(lock) {
            val open = if (fresh) null else storage.inviteLatestOpen(now())
            open?.token ?: Bytes.random(Invite.TOKEN_LEN).also {
                storage.invitePut(it, now(), now() + INVITE_LIFE_MS)
            }
        }
        return Invite(self, store.identityKeyPair.publicKey.serialize(), token)
    }

    /**
     * Adds the person whose invite was scanned or pasted. They show as "waiting" until this
     * phone has reached theirs over Tor and swapped keys.
     */
    fun addFromInvite(invite: Invite, name: String): Long {
        require(invite.onion != onion) { "That is your own code." }
        val typed = name.trim().take(Content.MAX_NAME)
        val id = synchronized(lock) {
            storage.contactByOnion(invite.onion)?.let { return it.id }
            storage.transaction {
                // Fix their identity key now, so only the phone that made the invite can answer.
                store.pinIdentity(invite.onion, invite.identityKey)
                val id = storage.contactInsert(
                    invite.onion, typed.ifEmpty { DEFAULT_NAME }, typed.isNotEmpty(),
                    Contact.PENDING, invite.identityKey, invite.token, now(),
                )
                storage.messageInsert(id, Bytes.random(16), true, Message.KIND_HELLO, "", now(), Message.QUEUED)
                id
            }
        }
        events.onChanged()
        ensureWorker(id)
        return id
    }

    fun renameContact(id: Long, name: String) {
        val typed = name.trim().take(Content.MAX_NAME)
        if (typed.isEmpty()) return
        storage.contactSetName(id, typed, true)
        events.onChanged()
    }

    fun deleteContact(id: Long) {
        synchronized(lock) {
            val c = storage.contactById(id) ?: return
            storage.transaction {
                storage.contactDelete(id)
                store.forget(c.onion)
            }
        }
        synchronized(workers) { workers[id]?.quit() }
        events.onChanged()
    }

    // ------------------------------------------------------------------ messages

    fun sendText(contactId: Long, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val timer = storage.contactById(contactId)?.timerS ?: return
        // Long text is split so every piece fits comfortably in one encrypted message.
        for (part in body.chunked(Content.MAX_TEXT)) {
            storage.messageInsert(contactId, Bytes.random(16), true, Message.KIND_TEXT, part, now(), Message.QUEUED, timer)
        }
        events.onChanged()
        ensureWorker(contactId)
    }

    /** Queues a photo ([Content.MEDIA_PHOTO]) or voice note ([Content.MEDIA_VOICE]) that is already prepared. */
    fun sendMedia(contactId: Long, type: Int, data: ByteArray, width: Int, height: Int, durationMs: Int, caption: String = "") {
        require(type == Content.MEDIA_PHOTO || type == Content.MEDIA_VOICE)
        require(data.isNotEmpty() && data.size <= Content.MAX_MEDIA) { "That is too large to send." }
        val timer = storage.contactById(contactId)?.timerS ?: return
        val kind = if (type == Content.MEDIA_PHOTO) Message.KIND_PHOTO else Message.KIND_VOICE
        storage.transaction {
            val id = storage.messageInsert(
                contactId, Bytes.random(16), true, kind, caption.trim().take(Content.MAX_TEXT), now(), Message.QUEUED, timer,
            ) ?: return@transaction
            storage.attachmentPut(id, width, height, durationMs, data)
        }
        events.onChanged()
        ensureWorker(contactId)
    }

    /**
     * Sets this chat's disappearing-message timer and tells the other phone, so both use the
     * same one. It applies to messages written from now on; earlier ones keep what they had.
     */
    fun setTimer(contactId: Long, seconds: Int) {
        val s = seconds.coerceIn(0, Content.MAX_TIMER_S)
        val c = storage.contactById(contactId) ?: return
        if (c.timerS == s) return
        storage.transaction {
            storage.contactSetTimer(contactId, s)
            storage.messageInsert(contactId, Bytes.random(16), true, Message.KIND_NOTE, s.toString(), now(), Message.DELIVERED)
            storage.messageInsert(contactId, Bytes.random(16), true, Message.KIND_TIMER, s.toString(), now(), Message.QUEUED)
        }
        events.onChanged()
        ensureWorker(contactId)
    }

    /**
     * Called when a chat is on screen: clears the unread count and starts the countdown on any
     * received disappearing messages, which only begin to count once they have been seen.
     */
    fun markRead(contactId: Long) {
        val c = storage.contactById(contactId) ?: return
        val started = storage.messagesStartExpiryOnRead(contactId, now())
        if (c.unread != 0) storage.contactClearUnread(contactId)
        if (c.unread != 0 || started > 0) events.onChanged()
    }

    /** Deletes messages whose disappearing timer has run out. Called every few seconds while the app is open. */
    fun sweep() {
        if (closed) return
        if (storage.deleteExpired(now()) > 0) events.onChanged()
    }

    /** Copies a name and picture from the phone's address book onto a contact. Nothing is sent anywhere. */
    fun linkContact(id: Long, name: String, photo: ByteArray?) {
        val typed = name.trim().take(Content.MAX_NAME)
        storage.transaction {
            if (typed.isNotEmpty()) storage.contactSetName(id, typed, true)
            storage.contactSetPhoto(id, photo)
        }
        events.onChanged()
    }

    /** Try every waiting message again now, for example because Tor has just connected. */
    fun kick() {
        if (closed) return
        for (id in storage.contactsWithQueued()) ensureWorker(id)
    }

    // ------------------------------------------------------------------ outgoing

    private fun ensureWorker(contactId: Long) {
        if (closed) return
        synchronized(workers) {
            val existing = workers[contactId]
            if (existing != null) {
                existing.poke()
            } else {
                val w = Worker(contactId)
                workers[contactId] = w
                pool.execute(w)
            }
        }
    }

    private inner class Conn(val socket: Socket) : Closeable {
        val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        init {
            openSockets += socket
            if (closed) close()
        }

        override fun close() {
            openSockets -= socket
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private class RefusedException : Exception()
    private class BrokenException(msg: String) : Exception(msg)

    /** The other phone could not open a message: the two phones' sessions no longer match. */
    private class UnreadableException : Exception()

    /** One of these runs for each contact that has messages waiting to go. */
    private inner class Worker(val contactId: Long) : Runnable {
        private val signal = Semaphore(0)
        @Volatile private var quit = false

        fun poke() = signal.release()

        fun quit() {
            quit = true
            signal.release()
        }

        /** Waits, but wakes early if poked. Returns true if it was poked. */
        private fun pause(ms: Long): Boolean {
            val poked = try {
                signal.tryAcquire(ms, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                quit = true
                false
            }
            signal.drainPermits()
            return poked
        }

        override fun run() {
            var conn: Conn? = null
            var fails = 0
            try {
                while (!closed && !quit) {
                    val contact = storage.contactById(contactId) ?: break
                    if (contact.state == Contact.REFUSED) break
                    val queued = storage.queuedFor(contactId)
                    if (queued.isEmpty()) {
                        if (pause(timing.idleMs)) continue
                        synchronized(workers) {
                            if (storage.queuedFor(contactId).isEmpty()) {
                                workers.remove(contactId)
                                return
                            }
                        }
                        continue
                    }
                    val self = onion
                    if (self == null) {
                        pause(timing.retryMs[0])
                        continue
                    }
                    try {
                        val c = conn ?: open(contact.onion).also { conn = it }
                        if (!hasSession(contact.onion)) {
                            if (contact.state == Contact.PENDING && contact.pendingToken != null) {
                                exchangeKeys(c, contact, self)
                            } else {
                                rekey(c, contact, self)
                            }
                        }
                        for (m in queued) deliver(c, contact, self, m)
                        fails = 0
                    } catch (_: UnreadableException) {
                        // Their phone couldn't open it. Drop our side of the session; the next
                        // pass asks for fresh keys and sends the same messages again, once.
                        log("starting a fresh session with a contact", null)
                        synchronized(lock) { store.deleteSession(Keys.address(contact.onion)) }
                        for (m in queued) retried.add(m.id)
                    } catch (_: RefusedException) {
                        // The other phone turned the invite down. Stop trying.
                        storage.transaction {
                            storage.contactSetState(contactId, Contact.REFUSED)
                            failQueued(contactId)
                        }
                        events.onChanged()
                        break
                    } catch (_: BrokenException) {
                        storage.transaction { failQueued(contactId) }
                        events.onChanged()
                        break
                    } catch (e: Exception) {
                        // Not reachable right now, or the line dropped. Keep the messages and retry.
                        log("send attempt failed", e)
                        conn?.close()
                        conn = null
                        val wait = timing.retryMs[minOf(fails, timing.retryMs.size - 1)]
                        fails++
                        pause(wait)
                    }
                }
            } finally {
                conn?.close()
                synchronized(workers) {
                    if (workers[contactId] === this) workers.remove(contactId)
                }
            }
        }
    }

    private fun failQueued(contactId: Long) {
        for (m in storage.queuedFor(contactId)) {
            if (m.kind in Message.WRITTEN) storage.messageSetStatus(m.id, Message.FAILED) else storage.messageDelete(m.id)
        }
    }

    private fun open(onion: String): Conn {
        val socket = transport.connect(onion)
        try {
            socket.soTimeout = timing.ioTimeoutMs
            val conn = Conn(socket)
            conn.out.write(Wire.MAGIC)
            conn.out.flush()
            return conn
        } catch (e: IOException) {
            try { socket.close() } catch (_: IOException) {}
            throw e
        }
    }

    /**
     * Note the order: the Signal library's SessionCipher takes this phone's address first and
     * the other phone's second, the opposite way round to SessionBuilder.
     */
    private fun cipherFor(remote: String, self: String) =
        SessionCipher(store, Keys.address(self), Keys.address(remote))

    private fun hasSession(onion: String): Boolean =
        synchronized(lock) { store.containsSession(Keys.address(onion)) }

    /** First contact: hand over the invite token and get the other phone's keys back. */
    private fun exchangeKeys(conn: Conn, contact: Contact, self: String) {
        val token = contact.pendingToken ?: throw BrokenException("no keys and no invite for this contact")
        val expected = contact.identityKey ?: throw BrokenException("no identity for this contact")
        Wire.write(conn.out, Wire.T_BUNDLE_REQ, token)
        val reply = Wire.read(conn.inp)
        if (reply.type == Wire.T_ACK) throw RefusedException()
        if (reply.type != Wire.T_BUNDLE) throw IOException("unexpected reply")
        val bundle = Keys.parseBundle(reply.body)
        // The keys must belong to the identity that was in the invite. If not, someone is in the middle.
        if (!bundle.identityKey.serialize().contentEquals(expected)) throw RefusedException()
        try {
            synchronized(lock) {
                SessionBuilder(store, Keys.address(contact.onion), Keys.address(self)).process(bundle)
            }
        } catch (e: Exception) {
            log("their keys were not accepted", e)
            throw RefusedException()
        }
    }

    /** Asks an existing contact for fresh keys, proving who is asking with this phone's identity key. */
    private fun rekey(conn: Conn, contact: Contact, self: String) {
        val expected = contact.identityKey ?: throw BrokenException("no identity for this contact")
        val at = now()
        val sig = store.identityKeyPair.privateKey.calculateSignature(rekeyStatement(self, contact.onion, at))
        val body = java.io.ByteArrayOutputStream()
        DataOutputStream(body).apply {
            write(self.toByteArray(Charsets.US_ASCII))
            writeLong(at)
            write(sig)
        }
        Wire.write(conn.out, Wire.T_REKEY_REQ, body.toByteArray())
        val reply = Wire.read(conn.inp)
        if (reply.type == Wire.T_ACK) throw BrokenException("the other phone no longer has this contact")
        if (reply.type != Wire.T_BUNDLE) throw IOException("unexpected reply")
        val bundle = Keys.parseBundle(reply.body)
        if (!bundle.identityKey.serialize().contentEquals(expected)) throw BrokenException("identity changed")
        try {
            synchronized(lock) {
                SessionBuilder(store, Keys.address(contact.onion), Keys.address(self)).process(bundle)
            }
        } catch (e: Exception) {
            log("their fresh keys were not accepted", e)
            throw BrokenException("fresh keys not accepted")
        }
    }

    private fun rekeyStatement(from: String, to: String, at: Long): ByteArray =
        "backchannel rekey v1|$from|$to|$at".toByteArray(Charsets.US_ASCII)

    private fun deliver(conn: Conn, contact: Contact, self: String, m: Message) {
        val content: Content = when (m.kind) {
            Message.KIND_TEXT -> Content.Text(m.msgId, m.sentAt, m.body, m.expireS)
            Message.KIND_PHOTO, Message.KIND_VOICE -> {
                val info = storage.attachmentInfo(m.id)
                val data = storage.attachmentData(m.id)
                if (info == null || data == null) {
                    storage.messageSetStatus(m.id, Message.FAILED)
                    events.onChanged()
                    return
                }
                val type = if (m.kind == Message.KIND_PHOTO) Content.MEDIA_PHOTO else Content.MEDIA_VOICE
                Content.Media(m.msgId, m.sentAt, type, info.width, info.height, info.durationMs, m.body, data, m.expireS)
            }
            Message.KIND_TIMER -> Content.Timer(m.body.toIntOrNull() ?: 0)
            Message.KIND_HELLO -> Content.Hello(contact.pendingToken ?: ByteArray(Invite.TOKEN_LEN), self, profileName)
            Message.KIND_PROFILE -> Content.Profile(profileName)
            else -> {
                storage.messageDelete(m.id)
                return
            }
        }
        val sealed: CiphertextMessage = try {
            synchronized(lock) {
                cipherFor(contact.onion, self).encrypt(Content.encode(content))
            }
        } catch (e: Exception) {
            log("could not encrypt", e)
            throw BrokenException("cannot encrypt for this contact")
        }
        val cipherBytes = sealed.serialize()
        val body = ByteArray(57 + cipherBytes.size)
        System.arraycopy(self.toByteArray(Charsets.US_ASCII), 0, body, 0, 56)
        body[56] = sealed.type.toByte()
        System.arraycopy(cipherBytes, 0, body, 57, cipherBytes.size)
        // A photo takes a while to cross Tor, so the wait for the reply grows with the size.
        conn.socket.soTimeout = timing.ioTimeoutMs + body.size / 10
        Wire.write(conn.out, Wire.T_MSG, body)

        val reply = Wire.read(conn.inp)
        conn.socket.soTimeout = timing.ioTimeoutMs
        if (reply.type != Wire.T_ACK || reply.body.size != 1) throw IOException("unexpected reply")
        val code = reply.body[0].toInt()
        val ok = code == Wire.ACK_OK
        if (code == Wire.ACK_UNREADABLE && m.kind != Message.KIND_HELLO && retried.add(m.id)) throw UnreadableException()
        when {
            m.kind == Message.KIND_HELLO && !ok -> throw RefusedException()
            m.kind == Message.KIND_HELLO -> storage.transaction {
                storage.contactSetState(contact.id, Contact.ACTIVE)
                storage.messageDelete(m.id)
            }
            m.kind !in Message.WRITTEN -> storage.messageDelete(m.id)
            ok -> storage.transaction {
                storage.messageSetStatus(m.id, Message.DELIVERED)
                // The sender's countdown starts only now, so a message never vanishes while still waiting to go.
                storage.messageStartExpiry(m.id, now())
            }
            else -> storage.messageSetStatus(m.id, Message.FAILED)
        }
        if (ok) storage.contactSeen(contact.id, now())
        events.onChanged()
    }

    // ------------------------------------------------------------------ incoming

    /** Starts answering connections that Tor passes on from this phone's onion address. */
    fun serve(server: ServerSocket) {
        this.server = server
        pool.execute {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                if (!incomingSlots.tryAcquire()) {
                    try { socket.close() } catch (_: IOException) {}
                    continue
                }
                try {
                    pool.execute {
                        try {
                            handle(socket)
                        } finally {
                            incomingSlots.release()
                        }
                    }
                } catch (_: Exception) {
                    incomingSlots.release()
                    try { socket.close() } catch (_: IOException) {}
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            Conn(socket).use { conn ->
                socket.soTimeout = (timing.idleMs + 30_000).toInt()
                val magic = ByteArray(Wire.MAGIC.size)
                conn.inp.readFully(magic)
                if (!magic.contentEquals(Wire.MAGIC)) return
                while (!closed) {
                    val frame = Wire.read(conn.inp)
                    val keepOpen = when (frame.type) {
                        Wire.T_BUNDLE_REQ -> onKeyRequest(frame.body, conn)
                        Wire.T_REKEY_REQ -> onRekeyRequest(frame.body, conn)
                        Wire.T_MSG -> onMessage(frame.body, conn)
                        else -> false
                    }
                    if (!keepOpen) return
                }
            }
        } catch (e: Exception) {
            // A dropped or malformed connection is simply closed.
            if (e !is java.io.EOFException && e !is java.net.SocketException) log("incoming connection closed", e)
        }
    }

    private fun ack(conn: Conn, code: Int) = Wire.write(conn.out, Wire.T_ACK, byteArrayOf(code.toByte()))

    private fun onKeyRequest(token: ByteArray, conn: Conn): Boolean {
        val bundle = synchronized(lock) {
            val row = if (token.size == Invite.TOKEN_LEN) storage.inviteGet(token) else null
            if (row == null || row.used || row.expiresAt < now() || row.requests >= MAX_KEY_REQUESTS) {
                null
            } else {
                storage.transaction {
                    storage.inviteCountRequest(token)
                    Keys.makeBundle(storage, store, now())
                }
            }
        }
        if (bundle == null) {
            ack(conn, Wire.ACK_REFUSED)
            return false
        }
        Wire.write(conn.out, Wire.T_BUNDLE, bundle)
        return true
    }

    /** A contact says it has lost our session and wants fresh keys. Only honoured if it can sign as that contact. */
    private fun onRekeyRequest(body: ByteArray, conn: Conn): Boolean {
        val self = onion
        if (body.size < 56 + 8 + 32 || self == null) return false
        val from = String(body, 0, 56, Charsets.US_ASCII)
        if (!isOnion(from)) return false
        val at = DataInputStream(java.io.ByteArrayInputStream(body, 56, 8)).readLong()
        val sig = body.copyOfRange(64, body.size)
        val bundle = synchronized(lock) {
            val contact = storage.contactByOnion(from)
            val identity = store.getIdentity(Keys.address(from))
            val recent = lastRekey[from]
            val genuine = try {
                contact != null && identity != null &&
                    kotlin.math.abs(now() - at) <= REKEY_WINDOW_MS &&
                    (recent == null || now() - recent >= timing.rekeyGapMs) &&
                    identity.publicKey.verifySignature(rekeyStatement(from, self, at), sig)
            } catch (_: Exception) {
                false
            }
            if (!genuine) {
                null
            } else {
                lastRekey[from] = now()
                storage.transaction { Keys.makeBundle(storage, store, now()) }
            }
        }
        if (bundle == null) {
            ack(conn, Wire.ACK_REFUSED)
            return false
        }
        Wire.write(conn.out, Wire.T_BUNDLE, bundle)
        return true
    }

    private fun onMessage(body: ByteArray, conn: Conn): Boolean {
        if (body.size < 58) return false
        val from = String(body, 0, 56, Charsets.US_ASCII)
        val self = onion
        if (!isOnion(from) || self == null || from == self) return false
        val kind = body[56].toInt()
        val cipherBytes = body.copyOfRange(57, body.size)

        val after = ArrayList<() -> Unit>()
        val code = synchronized(lock) { receive(from, kind, cipherBytes, self, after) }
        ack(conn, code)
        for (f in after) f()
        return code != Wire.ACK_REFUSED
    }

    /** Decrypts one incoming message and files it. Runs with the lock held. */
    private fun receive(from: String, kind: Int, cipherBytes: ByteArray, self: String, after: MutableList<() -> Unit>): Int {
        val address = Keys.address(from)
        val cipher = cipherFor(from, self)
        val contact = storage.contactByOnion(from)

        if (contact == null) {
            // A stranger. The only thing accepted is a first message built from keys this
            // phone handed out against a valid invite token.
            if (kind != CiphertextMessage.PREKEY_TYPE) return Wire.ACK_REFUSED
            val hello = try {
                Content.decode(cipher.decrypt(PreKeySignalMessage(cipherBytes))) as? Content.Hello
            } catch (e: Exception) {
                log("first message from a stranger did not open", e)
                null
            }
            val row = hello?.let { storage.inviteGet(it.token) }
            val identity = store.getIdentity(address)
            if (hello == null || hello.onion != from || row == null || row.used || row.expiresAt < now() || identity == null) {
                store.forget(from)
                return Wire.ACK_REFUSED
            }
            val id = storage.transaction {
                storage.inviteMarkUsed(hello.token)
                val id = storage.contactInsert(
                    from, hello.name.trim().ifEmpty { DEFAULT_NAME }, false,
                    Contact.ACTIVE, identity.serialize(), null, now(),
                )
                // Reply straight away with our own name. This also confirms the keys to their phone.
                storage.messageInsert(id, Bytes.random(16), true, Message.KIND_PROFILE, "", now(), Message.QUEUED)
                id
            }
            after += {
                events.onContactAdded(id)
                events.onChanged()
                ensureWorker(id)
            }
            return Wire.ACK_OK
        }

        val plain = try {
            when (kind) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(cipherBytes))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(cipherBytes))
                else -> return Wire.ACK_REFUSED
            }
        } catch (_: DuplicateMessageException) {
            return Wire.ACK_OK
        } catch (e: Exception) {
            log("message from a contact did not open", e)
            return Wire.ACK_UNREADABLE
        }
        val content = try {
            Content.decode(plain)
        } catch (_: Exception) {
            return Wire.ACK_UNREADABLE
        }

        storage.contactSeen(contact.id, now())
        if (contact.state != Contact.ACTIVE) storage.contactSetState(contact.id, Contact.ACTIVE)
        when (content) {
            is Content.Text -> {
                val text = content.text.take(Content.MAX_TEXT)
                val sentAt = minOf(content.sentAt, now())
                val fresh = storage.messageInsert(
                    contact.id, content.msgId, false, Message.KIND_TEXT, text, sentAt, Message.RECEIVED, content.expireS,
                )
                if (fresh != null) {
                    adoptTimer(contact, content.expireS)
                    storage.contactAddUnread(contact.id, 1)
                    after += { events.onIncomingText(contact.id) }
                }
            }
            is Content.Media -> {
                val kind = if (content.type == Content.MEDIA_PHOTO) Message.KIND_PHOTO else Message.KIND_VOICE
                val sentAt = minOf(content.sentAt, now())
                val fresh = storage.transaction {
                    val id = storage.messageInsert(
                        contact.id, content.msgId, false, kind, content.caption.take(Content.MAX_TEXT),
                        sentAt, Message.RECEIVED, content.expireS,
                    )
                    if (id != null) storage.attachmentPut(id, content.width, content.height, content.durationMs, content.data)
                    id
                }
                if (fresh != null) {
                    adoptTimer(contact, content.expireS)
                    storage.contactAddUnread(contact.id, 1)
                    after += { events.onIncomingText(contact.id) }
                }
            }
            is Content.Timer -> {
                if (contact.timerS != content.seconds) {
                    storage.contactSetTimer(contact.id, content.seconds)
                    storage.messageInsert(
                        contact.id, Bytes.random(16), false, Message.KIND_NOTE, content.seconds.toString(), now(), Message.RECEIVED,
                    )
                }
            }
            is Content.Hello -> Unit
            is Content.Profile -> {
                val name = content.name.trim()
                if (!contact.customName && name.isNotEmpty()) storage.contactSetName(contact.id, name, false)
            }
        }
        after += {
            events.onChanged()
            // They are online right now, so anything waiting for them can go.
            if (storage.queuedFor(contact.id).isNotEmpty()) ensureWorker(contact.id)
        }
        return Wire.ACK_OK
    }

    /**
     * If a message arrives carrying a different timer from the one this phone has for the chat
     * (because a "timer changed" notice was missed), follow the message so both phones agree.
     */
    private fun adoptTimer(contact: Contact, seconds: Int) {
        if (contact.timerS == seconds) return
        if (storage.contactById(contact.id)?.timerS == seconds) return
        storage.contactSetTimer(contact.id, seconds)
        storage.messageInsert(contact.id, Bytes.random(16), false, Message.KIND_NOTE, seconds.toString(), now(), Message.RECEIVED)
    }

    override fun close() {
        closed = true
        try { server?.close() } catch (_: IOException) {}
        synchronized(workers) { workers.values.forEach { it.quit() } }
        for (s in openSockets.toList()) {
            try { s.close() } catch (_: IOException) {}
        }
        openSockets.clear()
        pool.shutdownNow()
    }

    companion object {
        const val DEFAULT_NAME = "New contact"
        private const val REKEY_WINDOW_MS = 15 * 60_000L

        /**
         * Run once on a database that has just been restored from a backup. The sessions in a
         * backup are out of date the moment a message is exchanged after it was made, so they
         * are dropped; each contact is then sent this phone's name, which makes both phones
         * set up a fresh session the next time they are online together.
         */
        fun prepareRestored(storage: Storage, now: Long) {
            storage.transaction {
                SignalStore(storage).dropAllSessions()
                for (c in storage.contacts()) {
                    if (c.state == Contact.ACTIVE) {
                        storage.messageInsert(c.id, Bytes.random(16), true, Message.KIND_PROFILE, "", now, Message.QUEUED)
                    }
                }
            }
        }
        const val INVITE_LIFE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_KEY_REQUESTS = 5
        private const val MAX_INCOMING = 24
    }
}
