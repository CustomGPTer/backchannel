package uk.jj.backchannel

import uk.jj.backchannel.core.Attachment
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.InviteRow
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.Storage
import java.util.TreeMap

/** A plain in-memory stand-in for the encrypted database, used only by the tests. */
class MemStorage : Storage {
    private val kv = TreeMap<String, ByteArray>()
    private val invites = LinkedHashMap<String, InviteRow>()
    private val contacts = LinkedHashMap<Long, Contact>()
    private val messages = ArrayList<Message>()
    private val attachments = HashMap<Long, Pair<Attachment, ByteArray>>()
    private var nextContact = 1L
    private var nextMessage = 1L

    /** An independent copy of everything, as a backup file would hold. */
    @Synchronized fun snapshot(): MemStorage {
        val c = MemStorage()
        kv.forEach { (k, v) -> c.kv[k] = v.copyOf() }
        c.invites.putAll(invites)
        c.contacts.putAll(contacts)
        c.messages.addAll(messages)
        attachments.forEach { (k, v) -> c.attachments[k] = v.first to v.second.copyOf() }
        c.nextContact = nextContact
        c.nextMessage = nextMessage
        return c
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Synchronized override fun kvGet(key: String) = kv[key]?.copyOf()
    @Synchronized override fun kvPut(key: String, value: ByteArray) { kv[key] = value.copyOf() }
    @Synchronized override fun kvDelete(key: String) { kv.remove(key) }
    @Synchronized override fun kvKeys(prefix: String) = kv.keys.filter { it.startsWith(prefix) }

    @Synchronized override fun invitePut(token: ByteArray, createdAt: Long, expiresAt: Long) {
        invites[hex(token)] = InviteRow(token.copyOf(), createdAt, expiresAt, false, 0)
    }
    @Synchronized override fun inviteGet(token: ByteArray) = invites[hex(token)]
    @Synchronized override fun inviteMarkUsed(token: ByteArray) {
        invites[hex(token)]?.let { invites[hex(token)] = InviteRow(it.token, it.createdAt, it.expiresAt, true, it.requests) }
    }
    @Synchronized override fun inviteCountRequest(token: ByteArray) {
        invites[hex(token)]?.let { invites[hex(token)] = InviteRow(it.token, it.createdAt, it.expiresAt, it.used, it.requests + 1) }
    }
    @Synchronized override fun inviteLatestOpen(now: Long) =
        invites.values.filter { !it.used && it.requests == 0 && it.expiresAt > now }.maxByOrNull { it.createdAt }

    private fun Contact.with(
        name: String = this.name, customName: Boolean = this.customName, state: Int = this.state,
        identityKey: ByteArray? = this.identityKey, lastSeenAt: Long = this.lastSeenAt, unread: Int = this.unread,
        timerS: Int = this.timerS, photo: ByteArray? = this.photo,
    ) = Contact(id, onion, name, customName, state, identityKey, pendingToken, createdAt, lastSeenAt, unread, timerS, photo)

    private fun Message.with(status: Int = this.status, expiresAt: Long = this.expiresAt) =
        Message(id, contactId, msgId, outgoing, kind, body, sentAt, status, expireS, expiresAt)

    @Synchronized override fun contactInsert(
        onion: String, name: String, customName: Boolean, state: Int,
        identityKey: ByteArray?, pendingToken: ByteArray?, now: Long,
    ): Long {
        check(contacts.values.none { it.onion == onion }) { "duplicate contact" }
        val id = nextContact++
        contacts[id] = Contact(id, onion, name, customName, state, identityKey, pendingToken, now, 0, 0)
        return id
    }
    @Synchronized override fun contactById(id: Long) = contacts[id]
    @Synchronized override fun contactByOnion(onion: String) = contacts.values.firstOrNull { it.onion == onion }
    @Synchronized override fun contacts() = contacts.values.toList()
    @Synchronized override fun contactSetName(id: Long, name: String, custom: Boolean) {
        contacts[id]?.let { contacts[id] = it.with(name = name, customName = custom) }
    }
    @Synchronized override fun contactSetState(id: Long, state: Int) {
        contacts[id]?.let { contacts[id] = it.with(state = state) }
    }
    @Synchronized override fun contactSetIdentity(id: Long, identityKey: ByteArray) {
        contacts[id]?.let { contacts[id] = it.with(identityKey = identityKey) }
    }
    @Synchronized override fun contactSeen(id: Long, now: Long) {
        contacts[id]?.let { contacts[id] = it.with(lastSeenAt = now) }
    }
    @Synchronized override fun contactAddUnread(id: Long, delta: Int) {
        contacts[id]?.let { contacts[id] = it.with(unread = it.unread + delta) }
    }
    @Synchronized override fun contactClearUnread(id: Long) {
        contacts[id]?.let { contacts[id] = it.with(unread = 0) }
    }
    @Synchronized override fun contactSetTimer(id: Long, seconds: Int) {
        contacts[id]?.let { contacts[id] = it.with(timerS = seconds) }
    }
    @Synchronized override fun contactSetPhoto(id: Long, photo: ByteArray?) {
        contacts[id]?.let { contacts[id] = Contact(it.id, it.onion, it.name, it.customName, it.state, it.identityKey, it.pendingToken, it.createdAt, it.lastSeenAt, it.unread, it.timerS, photo) }
    }
    @Synchronized override fun contactDelete(id: Long) {
        contacts.remove(id)
        messages.filter { it.contactId == id }.forEach { attachments.remove(it.id) }
        messages.removeAll { it.contactId == id }
    }

    @Synchronized override fun messageInsert(
        contactId: Long, msgId: ByteArray, outgoing: Boolean, kind: Int, body: String, sentAt: Long, status: Int, expireS: Int,
    ): Long? {
        if (messages.any { it.contactId == contactId && it.outgoing == outgoing && it.msgId.contentEquals(msgId) }) return null
        val id = nextMessage++
        messages += Message(id, contactId, msgId.copyOf(), outgoing, kind, body, sentAt, status, expireS, 0)
        return id
    }
    @Synchronized override fun messagesFor(contactId: Long, limit: Int) =
        messages.filter { it.contactId == contactId && it.kind in Message.SHOWN }.takeLast(limit)
    @Synchronized override fun lastWrittenFor(contactId: Long) =
        messages.lastOrNull { it.contactId == contactId && it.kind in Message.WRITTEN }
    @Synchronized override fun queuedFor(contactId: Long) =
        messages.filter { it.contactId == contactId && it.outgoing && it.status == Message.QUEUED }
    @Synchronized override fun contactsWithQueued() =
        messages.filter { it.outgoing && it.status == Message.QUEUED }.map { it.contactId }.distinct()
    @Synchronized override fun messageSetStatus(id: Long, status: Int) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].with(status = status)
    }
    @Synchronized override fun messageDelete(id: Long) {
        messages.removeAll { it.id == id }
        attachments.remove(id)
    }

    @Synchronized override fun messageStartExpiry(id: Long, now: Long) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0 && messages[i].expireS > 0 && messages[i].expiresAt == 0L) {
            messages[i] = messages[i].with(expiresAt = now + messages[i].expireS * 1000L)
        }
    }
    @Synchronized override fun messagesStartExpiryOnRead(contactId: Long, now: Long): Int {
        var n = 0
        for (i in messages.indices) {
            val m = messages[i]
            if (m.contactId == contactId && !m.outgoing && m.expireS > 0 && m.expiresAt == 0L) {
                messages[i] = m.with(expiresAt = now + m.expireS * 1000L)
                n++
            }
        }
        return n
    }
    @Synchronized override fun deleteExpired(now: Long): Int {
        val gone = messages.filter { it.expiresAt in 1..now }
        gone.forEach { attachments.remove(it.id) }
        messages.removeAll(gone)
        return gone.size
    }

    @Synchronized override fun attachmentPut(messageId: Long, width: Int, height: Int, durationMs: Int, data: ByteArray) {
        attachments[messageId] = Attachment(messageId, width, height, durationMs, data.size) to data.copyOf()
    }
    @Synchronized override fun attachmentInfo(messageId: Long) = attachments[messageId]?.first
    @Synchronized override fun attachmentData(messageId: Long) = attachments[messageId]?.second?.copyOf()

    @Synchronized override fun <T> transaction(block: () -> T): T = block()
}
