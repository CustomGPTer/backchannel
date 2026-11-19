package uk.jj.backchannel.core

class Contact(
    val id: Long,
    val onion: String,
    val name: String,
    /** True once the user has typed a name themselves, so it is never overwritten. */
    val customName: Boolean,
    val state: Int,
    val identityKey: ByteArray?,
    val pendingToken: ByteArray?,
    val createdAt: Long,
    val lastSeenAt: Long,
    val unread: Int,
    /** Disappearing-message timer for this chat, in seconds. 0 = messages are kept. */
    val timerS: Int = 0,
    /** A small picture copied from the address book when the contact was linked, or null. */
    val photo: ByteArray? = null,
) {
    companion object {
        /** Invite scanned, the other phone has not been reached yet. */
        const val PENDING = 0
        const val ACTIVE = 1

        /** The other phone turned the invite down (used already, or expired). */
        const val REFUSED = 2
    }
}

class Message(
    val id: Long,
    val contactId: Long,
    val msgId: ByteArray,
    val outgoing: Boolean,
    val kind: Int,
    val body: String,
    val sentAt: Long,
    val status: Int,
    /** How long this message lives once its countdown starts, in seconds. 0 = kept. */
    val expireS: Int = 0,
    /** When it will be deleted. 0 = the countdown has not started. */
    val expiresAt: Long = 0,
) {
    companion object {
        const val KIND_TEXT = 1
        const val KIND_HELLO = 2
        const val KIND_PROFILE = 3
        const val KIND_PHOTO = 4
        const val KIND_VOICE = 5

        /** A line in the chat that records a change, such as the timer being set. Never sent. */
        const val KIND_NOTE = 6

        /** Tells the other phone the timer changed. Sent, never shown. */
        const val KIND_TIMER = 7

        /** What appears in a chat. */
        val SHOWN = setOf(KIND_TEXT, KIND_PHOTO, KIND_VOICE, KIND_NOTE)

        /** What a person actually wrote or sent (used for the chat-list preview and for failures). */
        val WRITTEN = setOf(KIND_TEXT, KIND_PHOTO, KIND_VOICE)

        /** Waiting on this phone for the other phone to come online. */
        const val QUEUED = 0
        const val DELIVERED = 1
        const val RECEIVED = 2
        const val FAILED = 3
    }
}

class Attachment(val messageId: Long, val width: Int, val height: Int, val durationMs: Int, val size: Int)

class InviteRow(val token: ByteArray, val createdAt: Long, val expiresAt: Long, val used: Boolean, val requests: Int)

/**
 * Everything the app remembers. On the phone this is the encrypted database; in tests it is
 * a plain in-memory copy, so the same messaging code can be exercised off the phone.
 */
interface Storage {
    fun kvGet(key: String): ByteArray?
    fun kvPut(key: String, value: ByteArray)
    fun kvDelete(key: String)
    fun kvKeys(prefix: String): List<String>

    fun invitePut(token: ByteArray, createdAt: Long, expiresAt: Long)
    fun inviteGet(token: ByteArray): InviteRow?
    fun inviteMarkUsed(token: ByteArray)
    fun inviteCountRequest(token: ByteArray)
    fun inviteLatestOpen(now: Long): InviteRow?

    fun contactInsert(
        onion: String, name: String, customName: Boolean, state: Int,
        identityKey: ByteArray?, pendingToken: ByteArray?, now: Long,
    ): Long
    fun contactById(id: Long): Contact?
    fun contactByOnion(onion: String): Contact?
    fun contacts(): List<Contact>
    fun contactSetName(id: Long, name: String, custom: Boolean)
    fun contactSetState(id: Long, state: Int)
    fun contactSetIdentity(id: Long, identityKey: ByteArray)
    fun contactSeen(id: Long, now: Long)
    fun contactAddUnread(id: Long, delta: Int)
    fun contactClearUnread(id: Long)
    fun contactSetTimer(id: Long, seconds: Int)
    fun contactSetPhoto(id: Long, photo: ByteArray?)
    fun contactDelete(id: Long)

    /** Returns the new row id, or null if this message was already stored. */
    fun messageInsert(
        contactId: Long, msgId: ByteArray, outgoing: Boolean, kind: Int,
        body: String, sentAt: Long, status: Int, expireS: Int = 0,
    ): Long?

    /** Everything shown in a chat, oldest first. */
    fun messagesFor(contactId: Long, limit: Int): List<Message>

    /** The newest thing either person wrote or sent, for the chat-list preview. */
    fun lastWrittenFor(contactId: Long): Message?
    fun queuedFor(contactId: Long): List<Message>
    fun contactsWithQueued(): List<Long>
    fun messageSetStatus(id: Long, status: Int)
    fun messageDelete(id: Long)

    /** Starts one message's countdown, if it has a timer and the countdown has not started. */
    fun messageStartExpiry(id: Long, now: Long)

    /** Starts the countdown on every received message in a chat that has a timer and has now been seen. */
    fun messagesStartExpiryOnRead(contactId: Long, now: Long): Int

    /** Deletes every message whose countdown has run out. Returns how many went. */
    fun deleteExpired(now: Long): Int

    /** The photo or voice note that belongs to a message. Deleted together with it. */
    fun attachmentPut(messageId: Long, width: Int, height: Int, durationMs: Int, data: ByteArray)
    fun attachmentInfo(messageId: Long): Attachment?
    fun attachmentData(messageId: Long): ByteArray?

    fun <T> transaction(block: () -> T): T
}
