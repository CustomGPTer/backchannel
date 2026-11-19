package uk.jj.backchannel

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.Storage
import uk.jj.backchannel.data.SqlStorage

/** The same checks are run against the real database code and the in-memory test copy. */
abstract class StorageContract {
    protected abstract fun create(): Storage
    private val opened = ArrayList<Storage>()

    fun make(): Storage = create().also { opened += it }

    @org.junit.After fun closeAll() {
        opened.forEach { (it as? java.io.Closeable)?.close() }
    }

    private val onion = "a".repeat(56)

    @Test fun keyValue() {
        val s = make()
        assertNull(s.kvGet("x"))
        s.kvPut("ss:one", byteArrayOf(1, 2, 3))
        s.kvPut("ss:two", byteArrayOf(4))
        s.kvPut("id:one", byteArrayOf(5))
        s.kvPut("ss:one", byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(9, 9), s.kvGet("ss:one"))
        assertEquals(listOf("ss:one", "ss:two"), s.kvKeys("ss:"))
        s.kvDelete("ss:one")
        assertNull(s.kvGet("ss:one"))
        assertEquals(listOf("ss:two"), s.kvKeys("ss:"))
    }

    @Test fun invites() {
        val s = make()
        val t1 = ByteArray(16) { 1 }
        val t2 = ByteArray(16) { 2 }
        s.invitePut(t1, 1000, 5000)
        s.invitePut(t2, 2000, 6000)
        assertArrayEquals(t2, s.inviteLatestOpen(3000)!!.token)
        s.inviteCountRequest(t2)
        assertEquals(1, s.inviteGet(t2)!!.requests)
        assertArrayEquals("a code someone has started using is not offered again", t1, s.inviteLatestOpen(3000)!!.token)
        s.inviteMarkUsed(t1)
        assertTrue(s.inviteGet(t1)!!.used)
        assertNull(s.inviteLatestOpen(3000))
        assertNull(s.inviteGet(ByteArray(16) { 3 }))
        s.invitePut(ByteArray(16) { 4 }, 100, 200)
        assertNull("expired codes are not offered", s.inviteLatestOpen(3000))
    }

    @Test fun contacts() {
        val s = make()
        val id = s.contactInsert(onion, "New contact", false, Contact.PENDING, byteArrayOf(7), ByteArray(16) { 8 }, 111)
        val c = s.contactById(id)!!
        assertEquals(onion, c.onion)
        assertEquals(Contact.PENDING, c.state)
        assertFalse(c.customName)
        assertArrayEquals(byteArrayOf(7), c.identityKey)
        assertEquals(16, c.pendingToken!!.size)
        assertEquals(111, c.createdAt)
        assertEquals(id, s.contactByOnion(onion)!!.id)
        assertNull(s.contactByOnion("b".repeat(56)))

        s.contactSetName(id, "Dave", true)
        s.contactSetState(id, Contact.ACTIVE)
        s.contactSeen(id, 999)
        s.contactAddUnread(id, 2)
        s.contactAddUnread(id, 1)
        s.contactSetIdentity(id, byteArrayOf(1, 1))
        val d = s.contactById(id)!!
        assertEquals("Dave", d.name)
        assertTrue(d.customName)
        assertEquals(Contact.ACTIVE, d.state)
        assertEquals(999, d.lastSeenAt)
        assertEquals(3, d.unread)
        assertArrayEquals(byteArrayOf(1, 1), d.identityKey)
        s.contactClearUnread(id)
        assertEquals(0, s.contactById(id)!!.unread)

        val id2 = s.contactInsert("b".repeat(56), "Second", true, Contact.ACTIVE, null, null, 222)
        assertNull(s.contactById(id2)!!.identityKey)
        assertNull(s.contactById(id2)!!.pendingToken)
        assertEquals(listOf(id, id2), s.contacts().map { it.id })
    }

    @Test fun messages() {
        val s = make()
        val a = s.contactInsert(onion, "A", false, Contact.ACTIVE, null, null, 1)
        val b = s.contactInsert("b".repeat(56), "B", false, Contact.ACTIVE, null, null, 1)
        val m1 = s.messageInsert(a, ByteArray(16) { 1 }, true, Message.KIND_TEXT, "one", 10, Message.QUEUED)!!
        val hello = s.messageInsert(a, ByteArray(16) { 2 }, true, Message.KIND_HELLO, "", 11, Message.QUEUED)!!
        val m3 = s.messageInsert(a, ByteArray(16) { 3 }, false, Message.KIND_TEXT, "three 👍", 12, Message.RECEIVED)!!
        s.messageInsert(b, ByteArray(16) { 1 }, true, Message.KIND_TEXT, "other chat", 13, Message.QUEUED)!!

        assertNull("the same incoming message is never stored twice",
            s.messageInsert(a, ByteArray(16) { 3 }, false, Message.KIND_TEXT, "three again", 99, Message.RECEIVED))
        assertNotNull("an outgoing message may share an id with an incoming one",
            s.messageInsert(a, ByteArray(16) { 3 }, true, Message.KIND_TEXT, "mine", 14, Message.DELIVERED))

        assertEquals("only chat text is shown", listOf("one", "three 👍", "mine"), s.messagesFor(a, 50).map { it.body })
        assertEquals(listOf("three 👍", "mine"), s.messagesFor(a, 2).map { it.body })
        assertEquals("mine", s.lastWrittenFor(a)!!.body)
        assertEquals(listOf(m1, hello), s.queuedFor(a).map { it.id })
        assertEquals(setOf(a, b), s.contactsWithQueued().toSet())

        s.messageSetStatus(m1, Message.DELIVERED)
        s.messageDelete(hello)
        assertTrue(s.queuedFor(a).isEmpty())
        assertEquals(listOf(b), s.contactsWithQueued())
        assertEquals(Message.DELIVERED, s.messagesFor(a, 50).first().status)
        assertFalse(s.messagesFor(a, 50).first { it.id == m3 }.outgoing)

        s.contactDelete(a)
        assertNull(s.contactById(a))
        assertTrue(s.messagesFor(a, 50).isEmpty())
        assertEquals(1, s.messagesFor(b, 50).size)
    }

    @Test fun timersAndAddressBookPicture() {
        val s = make()
        val id = s.contactInsert(onion, "A", false, Contact.ACTIVE, null, null, 1)
        assertEquals(0, s.contactById(id)!!.timerS)
        assertNull(s.contactById(id)!!.photo)
        s.contactSetTimer(id, 3600)
        s.contactSetPhoto(id, byteArrayOf(1, 2, 3))
        assertEquals(3600, s.contactById(id)!!.timerS)
        assertArrayEquals(byteArrayOf(1, 2, 3), s.contactByOnion(onion)!!.photo)
        assertEquals(3600, s.contacts().single().timerS)
        s.contactSetPhoto(id, null)
        assertNull(s.contactById(id)!!.photo)
    }

    @Test fun disappearingMessages() {
        val s = make()
        val a = s.contactInsert(onion, "A", false, Contact.ACTIVE, null, null, 1)
        val kept = s.messageInsert(a, ByteArray(16) { 1 }, false, Message.KIND_TEXT, "kept", 10, Message.RECEIVED)!!
        val inbound = s.messageInsert(a, ByteArray(16) { 2 }, false, Message.KIND_TEXT, "inbound", 10, Message.RECEIVED, 60)!!
        val waiting = s.messageInsert(a, ByteArray(16) { 3 }, true, Message.KIND_TEXT, "waiting", 10, Message.QUEUED, 60)!!
        val sent = s.messageInsert(a, ByteArray(16) { 4 }, true, Message.KIND_PHOTO, "", 10, Message.DELIVERED, 60)!!
        s.attachmentPut(sent, 4, 3, 0, byteArrayOf(9, 9, 9))
        assertEquals(60, s.messagesFor(a, 10).first { it.id == inbound }.expireS)
        assertEquals(0L, s.messagesFor(a, 10).first { it.id == inbound }.expiresAt)

        assertEquals("nothing has started counting yet", 0, s.deleteExpired(Long.MAX_VALUE / 4))

        s.messageStartExpiry(sent, 1_000)
        s.messageStartExpiry(kept, 1_000)
        assertEquals(61_000L, s.messagesFor(a, 10).first { it.id == sent }.expiresAt)
        assertEquals("a message with no timer never gets a deadline", 0L, s.messagesFor(a, 10).first { it.id == kept }.expiresAt)
        s.messageStartExpiry(sent, 500_000)
        assertEquals("a countdown is never restarted", 61_000L, s.messagesFor(a, 10).first { it.id == sent }.expiresAt)

        assertEquals("only received messages start counting on being read", 1, s.messagesStartExpiryOnRead(a, 5_000))
        assertEquals(0, s.messagesStartExpiryOnRead(a, 9_000))
        assertEquals(65_000L, s.messagesFor(a, 10).first { it.id == inbound }.expiresAt)

        assertEquals(0, s.deleteExpired(60_999))
        assertEquals(1, s.deleteExpired(61_000))
        assertNull("its photo goes with it", s.attachmentData(sent))
        assertNull(s.attachmentInfo(sent))
        assertEquals(1, s.deleteExpired(70_000))
        assertEquals(listOf(kept, waiting), s.messagesFor(a, 10).map { it.id })
    }

    @Test fun photosAndVoiceNotesComeBackExactly() {
        val s = make()
        val a = s.contactInsert(onion, "A", false, Contact.ACTIVE, null, null, 1)
        val rnd = java.util.Random(7)
        // Sizes either side of the piece boundary, and the largest allowed.
        for ((n, size) in listOf(1, 1000, 256 * 1024, 256 * 1024 + 1, 700_000, 1_000_000).withIndex()) {
            val id = s.messageInsert(a, ByteArray(16) { n.toByte() }, true, Message.KIND_PHOTO, "", 10, Message.QUEUED)!!
            val data = ByteArray(size).also { rnd.nextBytes(it) }
            s.attachmentPut(id, 1600, 1200, 0, data)
            val info = s.attachmentInfo(id)!!
            assertEquals(size, info.size)
            assertEquals(1600, info.width)
            assertEquals(1200, info.height)
            assertArrayEquals("size $size", data, s.attachmentData(id))
        }
        val voice = s.messageInsert(a, ByteArray(16) { 99 }, false, Message.KIND_VOICE, "", 10, Message.RECEIVED)!!
        s.attachmentPut(voice, 0, 0, 4200, byteArrayOf(1, 2))
        assertEquals(4200, s.attachmentInfo(voice)!!.durationMs)
        assertEquals(Message.KIND_VOICE, s.messagesFor(a, 50).last().kind)
        assertEquals(voice, s.lastWrittenFor(a)!!.id)

        s.messageDelete(voice)
        assertNull(s.attachmentData(voice))
        val first = s.messagesFor(a, 50).first().id
        s.contactDelete(a)
        assertNull("deleting the contact deletes their photos", s.attachmentData(first))
        assertNull(s.attachmentInfo(first))
    }

    @Test fun notesShowInTheChatButNotAsThePreview() {
        val s = make()
        val a = s.contactInsert(onion, "A", false, Contact.ACTIVE, null, null, 1)
        s.messageInsert(a, ByteArray(16) { 1 }, true, Message.KIND_TEXT, "hello", 10, Message.DELIVERED)
        s.messageInsert(a, ByteArray(16) { 2 }, true, Message.KIND_NOTE, "3600", 11, Message.DELIVERED)
        s.messageInsert(a, ByteArray(16) { 3 }, true, Message.KIND_TIMER, "3600", 11, Message.QUEUED)
        assertEquals(listOf(Message.KIND_TEXT, Message.KIND_NOTE), s.messagesFor(a, 10).map { it.kind })
        assertEquals("hello", s.lastWrittenFor(a)!!.body)
        assertEquals(1, s.queuedFor(a).size)
    }

    @Test fun aFailedTransactionChangesNothing() {
        val s = make()
        if (s is MemStorage) return
        try {
            s.transaction {
                s.kvPut("k", byteArrayOf(1))
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
        }
        assertNull(s.kvGet("k"))
        assertEquals(7, s.transaction { s.kvPut("k", byteArrayOf(2)); 7 })
        assertArrayEquals(byteArrayOf(2), s.kvGet("k"))
    }
}

class MemStorageTest : StorageContract() {
    override fun create(): Storage = MemStorage()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SqlStorageTest : StorageContract() {
    override fun create(): Storage =
        SqlStorage.open(ApplicationProvider.getApplicationContext(), null, FrameworkSQLiteOpenHelperFactory())

    @Test fun aBackupDumpRestoresEverythingIntoANewDatabase() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val src = SqlStorage.open(context, null, FrameworkSQLiteOpenHelperFactory())
        val a = src.contactInsert("a".repeat(56), "Dave", true, Contact.ACTIVE, byteArrayOf(5, 5), null, 100)
        val b = src.contactInsert("b".repeat(56), "New contact", false, Contact.PENDING, byteArrayOf(6), ByteArray(16) { 2 }, 200)
        src.contactSetTimer(a, 3600)
        src.contactSetPhoto(a, ByteArray(3000) { it.toByte() })
        src.contactAddUnread(a, 4)
        src.kvPut("self:identity", ByteArray(70) { 9 })
        src.kvPut("ss:" + "a".repeat(56), ByteArray(4000) { (it * 3).toByte() })
        src.invitePut(ByteArray(16) { 7 }, 10, 99_999)
        val text = src.messageInsert(a, ByteArray(16) { 1 }, false, Message.KIND_TEXT, "caf\u00e9 \uD83D\uDC4D", 50, Message.RECEIVED, 3600)!!
        src.messageStartExpiry(text, 1000)
        val photo = src.messageInsert(a, ByteArray(16) { 2 }, true, Message.KIND_PHOTO, "caption", 60, Message.QUEUED)!!
        val bytes = ByteArray(700_000).also { java.util.Random(4).nextBytes(it) }
        src.attachmentPut(photo, 1600, 1200, 0, bytes)
        src.messageInsert(b, ByteArray(16) { 3 }, true, Message.KIND_HELLO, "", 70, Message.QUEUED)

        val dump = java.io.ByteArrayOutputStream()
        src.dump(java.io.DataOutputStream(dump))
        src.close()

        val dst = SqlStorage.open(context, null, FrameworkSQLiteOpenHelperFactory())
        try {
            dst.restore(java.io.DataInputStream(java.io.ByteArrayInputStream(dump.toByteArray())))
            val dave = dst.contactByOnion("a".repeat(56))!!
            assertEquals(a, dave.id)
            assertEquals("Dave", dave.name)
            assertEquals(3600, dave.timerS)
            assertEquals(4, dave.unread)
            assertEquals(3000, dave.photo!!.size)
            assertArrayEquals(byteArrayOf(5, 5), dave.identityKey)
            assertEquals(Contact.PENDING, dst.contactById(b)!!.state)
            assertEquals(16, dst.contactById(b)!!.pendingToken!!.size)
            assertEquals(70, dst.kvGet("self:identity")!!.size)
            assertEquals(4000, dst.kvGet("ss:" + "a".repeat(56))!!.size)
            assertNotNull(dst.inviteGet(ByteArray(16) { 7 }))
            val msgs = dst.messagesFor(a, 10)
            assertEquals(listOf("caf\u00e9 \uD83D\uDC4D", "caption"), msgs.map { it.body })
            assertEquals(3_601_000L, msgs[0].expiresAt)
            assertArrayEquals("the photo survives the backup exactly", bytes, dst.attachmentData(photo))
            assertEquals(listOf(a, b).toSet(), dst.contactsWithQueued().toSet())
            // New rows carry on from where the old phone left off, without clashing.
            val next = dst.messageInsert(a, ByteArray(16) { 9 }, true, Message.KIND_TEXT, "new", 80, Message.QUEUED)!!
            assertTrue(next > photo)
        } finally {
            dst.close()
        }

        // A file naming a table that does not exist is refused outright.
        val bad = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bad).apply {
            writeInt(1); writeInt(2)
            val name = "contacts; DROP TABLE kv".toByteArray()
            writeInt(name.size); write(name); writeInt(1)
            val col = "id".toByteArray(); writeInt(col.size); write(col); writeByte(0); writeInt(0)
        }
        val victim = SqlStorage.open(context, null, FrameworkSQLiteOpenHelperFactory())
        try {
            victim.kvPut("k", byteArrayOf(1))
            try {
                victim.restore(java.io.DataInputStream(java.io.ByteArrayInputStream(bad.toByteArray())))
                org.junit.Assert.fail("doctored backup accepted")
            } catch (_: java.io.IOException) {
            }
            assertArrayEquals(byteArrayOf(1), victim.kvGet("k"))
        } finally {
            victim.close()
        }
    }

    /** A phone that already has version 0.1 installed must keep its contacts and messages after updating. */
    @Test fun aDatabaseFromVersion01IsUpgradedInPlace() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (sql in SqlStorage.SCHEMA_V1) db.execSQL(sql)
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        v1.writableDatabase.apply {
            execSQL("INSERT INTO kv (k, v) VALUES ('self:onion', x'6162')")
            execSQL(
                "INSERT INTO contacts (onion, name, custom_name, state, created_at) VALUES (?, 'Dave', 1, 1, 5)",
                arrayOf<Any?>("d".repeat(56)),
            )
            execSQL(
                "INSERT INTO messages (contact_id, msg_id, outgoing, kind, body, sent_at, status) VALUES (1, x'01', 0, 1, 'from 0.1', 9, 2)",
            )
        }
        v1.close()

        val s = SqlStorage.open(context, name, FrameworkSQLiteOpenHelperFactory())
        try {
            assertArrayEquals("ab".toByteArray(), s.kvGet("self:onion"))
            val dave = s.contactByOnion("d".repeat(56))!!
            assertEquals("Dave", dave.name)
            assertEquals(0, dave.timerS)
            assertNull(dave.photo)
            val old = s.messagesFor(dave.id, 10).single()
            assertEquals("from 0.1", old.body)
            assertEquals(0, old.expireS)
            // The new features work on the upgraded database.
            s.contactSetTimer(dave.id, 300)
            val id = s.messageInsert(dave.id, ByteArray(16) { 5 }, true, Message.KIND_PHOTO, "", 10, Message.QUEUED, 300)!!
            s.attachmentPut(id, 10, 10, 0, ByteArray(300_000) { it.toByte() })
            assertEquals(300_000, s.attachmentData(id)!!.size)
            assertEquals(300, s.contactById(dave.id)!!.timerS)
        } finally {
            s.close()
            context.deleteDatabase(name)
        }
    }
}
