package uk.jj.backchannel.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import uk.jj.backchannel.core.Attachment
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.InviteRow
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.Storage
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The app's database. On the phone the factory passed in is SQLCipher's, so every page of
 * the file on disk is encrypted with the key that the PIN unlocks.
 */
class SqlStorage(private val helper: SupportSQLiteOpenHelper) : Storage, Closeable {

    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    override fun close() = helper.close()

    // ------------------------------------------------------------------ key/value

    override fun kvGet(key: String): ByteArray? =
        db.query("SELECT v FROM kv WHERE k = ?", arrayOf<Any?>(key)).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    override fun kvPut(key: String, value: ByteArray) {
        db.execSQL("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)", arrayOf<Any?>(key, value))
    }

    override fun kvDelete(key: String) {
        db.execSQL("DELETE FROM kv WHERE k = ?", arrayOf<Any?>(key))
    }

    override fun kvKeys(prefix: String): List<String> =
        db.query("SELECT k FROM kv WHERE substr(k, 1, ?) = ? ORDER BY k", arrayOf<Any?>(prefix.length, prefix)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    // ------------------------------------------------------------------ invites

    override fun invitePut(token: ByteArray, createdAt: Long, expiresAt: Long) {
        db.execSQL(
            "INSERT OR REPLACE INTO invites (token, created_at, expires_at, used, requests) VALUES (?, ?, ?, 0, 0)",
            arrayOf<Any?>(token, createdAt, expiresAt),
        )
    }

    private fun invite(c: Cursor) = InviteRow(c.getBlob(0), c.getLong(1), c.getLong(2), c.getInt(3) != 0, c.getInt(4))

    override fun inviteGet(token: ByteArray): InviteRow? =
        db.query("SELECT token, created_at, expires_at, used, requests FROM invites WHERE token = ?", arrayOf<Any?>(token)).use { c ->
            if (c.moveToFirst()) invite(c) else null
        }

    override fun inviteMarkUsed(token: ByteArray) {
        db.execSQL("UPDATE invites SET used = 1 WHERE token = ?", arrayOf<Any?>(token))
    }

    override fun inviteCountRequest(token: ByteArray) {
        db.execSQL("UPDATE invites SET requests = requests + 1 WHERE token = ?", arrayOf<Any?>(token))
    }

    override fun inviteLatestOpen(now: Long): InviteRow? =
        db.query(
            "SELECT token, created_at, expires_at, used, requests FROM invites " +
                "WHERE used = 0 AND requests = 0 AND expires_at > ? ORDER BY created_at DESC LIMIT 1",
            arrayOf<Any?>(now),
        ).use { c -> if (c.moveToFirst()) invite(c) else null }

    // ------------------------------------------------------------------ contacts

    override fun contactInsert(
        onion: String, name: String, customName: Boolean, state: Int,
        identityKey: ByteArray?, pendingToken: ByteArray?, now: Long,
    ): Long {
        val v = ContentValues().apply {
            put("onion", onion)
            put("name", name)
            put("custom_name", if (customName) 1 else 0)
            put("state", state)
            put("identity_key", identityKey)
            put("pending_token", pendingToken)
            put("created_at", now)
        }
        return db.insert("contacts", SQLiteDatabase.CONFLICT_ABORT, v)
    }

    private fun contact(c: Cursor) = Contact(
        id = c.getLong(0), onion = c.getString(1), name = c.getString(2), customName = c.getInt(3) != 0,
        state = c.getInt(4), identityKey = if (c.isNull(5)) null else c.getBlob(5),
        pendingToken = if (c.isNull(6)) null else c.getBlob(6),
        createdAt = c.getLong(7), lastSeenAt = c.getLong(8), unread = c.getInt(9),
        timerS = c.getInt(10), photo = if (c.isNull(11)) null else c.getBlob(11),
    )

    override fun contactById(id: Long): Contact? =
        db.query("SELECT $CONTACT_COLS FROM contacts WHERE id = ?", arrayOf<Any?>(id)).use { c ->
            if (c.moveToFirst()) contact(c) else null
        }

    override fun contactByOnion(onion: String): Contact? =
        db.query("SELECT $CONTACT_COLS FROM contacts WHERE onion = ?", arrayOf<Any?>(onion)).use { c ->
            if (c.moveToFirst()) contact(c) else null
        }

    override fun contacts(): List<Contact> =
        db.query("SELECT $CONTACT_COLS FROM contacts ORDER BY id").use { c ->
            val out = ArrayList<Contact>()
            while (c.moveToNext()) out += contact(c)
            out
        }

    override fun contactSetName(id: Long, name: String, custom: Boolean) {
        db.execSQL("UPDATE contacts SET name = ?, custom_name = ? WHERE id = ?", arrayOf<Any?>(name, if (custom) 1 else 0, id))
    }

    override fun contactSetState(id: Long, state: Int) {
        db.execSQL("UPDATE contacts SET state = ? WHERE id = ?", arrayOf<Any?>(state, id))
    }

    override fun contactSetIdentity(id: Long, identityKey: ByteArray) {
        db.execSQL("UPDATE contacts SET identity_key = ? WHERE id = ?", arrayOf<Any?>(identityKey, id))
    }

    override fun contactSeen(id: Long, now: Long) {
        db.execSQL("UPDATE contacts SET last_seen_at = ? WHERE id = ?", arrayOf<Any?>(now, id))
    }

    override fun contactAddUnread(id: Long, delta: Int) {
        db.execSQL("UPDATE contacts SET unread = unread + ? WHERE id = ?", arrayOf<Any?>(delta, id))
    }

    override fun contactClearUnread(id: Long) {
        db.execSQL("UPDATE contacts SET unread = 0 WHERE id = ?", arrayOf<Any?>(id))
    }

    override fun contactSetTimer(id: Long, seconds: Int) {
        db.execSQL("UPDATE contacts SET timer_s = ? WHERE id = ?", arrayOf<Any?>(seconds, id))
    }

    override fun contactSetPhoto(id: Long, photo: ByteArray?) {
        val v = ContentValues().apply { put("photo", photo) }
        db.update("contacts", SQLiteDatabase.CONFLICT_NONE, v, "id = ?", arrayOf<Any?>(id))
    }

    override fun contactDelete(id: Long) {
        transaction {
            val mine = "SELECT id FROM messages WHERE contact_id = ?"
            db.execSQL("DELETE FROM attachment_chunks WHERE message_id IN ($mine)", arrayOf<Any?>(id))
            db.execSQL("DELETE FROM attachments WHERE message_id IN ($mine)", arrayOf<Any?>(id))
            db.execSQL("DELETE FROM messages WHERE contact_id = ?", arrayOf<Any?>(id))
            db.execSQL("DELETE FROM contacts WHERE id = ?", arrayOf<Any?>(id))
        }
    }

    // ------------------------------------------------------------------ messages

    override fun messageInsert(
        contactId: Long, msgId: ByteArray, outgoing: Boolean, kind: Int,
        body: String, sentAt: Long, status: Int, expireS: Int,
    ): Long? {
        val v = ContentValues().apply {
            put("contact_id", contactId)
            put("msg_id", msgId)
            put("outgoing", if (outgoing) 1 else 0)
            put("kind", kind)
            put("body", body)
            put("sent_at", sentAt)
            put("status", status)
            put("expire_s", expireS)
        }
        val id = db.insert("messages", SQLiteDatabase.CONFLICT_IGNORE, v)
        return if (id == -1L) null else id
    }

    private fun message(c: Cursor) = Message(
        id = c.getLong(0), contactId = c.getLong(1), msgId = c.getBlob(2), outgoing = c.getInt(3) != 0,
        kind = c.getInt(4), body = c.getString(5), sentAt = c.getLong(6), status = c.getInt(7),
        expireS = c.getInt(8), expiresAt = c.getLong(9),
    )

    private fun messages(sql: String, args: Array<Any?>): List<Message> =
        db.query(sql, args).use { c ->
            val out = ArrayList<Message>()
            while (c.moveToNext()) out += message(c)
            out
        }

    override fun messagesFor(contactId: Long, limit: Int): List<Message> = messages(
        "SELECT * FROM (SELECT $MESSAGE_COLS FROM messages WHERE contact_id = ? AND kind IN ($SHOWN) " +
            "ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
        arrayOf(contactId, limit),
    )

    override fun lastWrittenFor(contactId: Long): Message? = messages(
        "SELECT $MESSAGE_COLS FROM messages WHERE contact_id = ? AND kind IN ($WRITTEN) ORDER BY id DESC LIMIT 1",
        arrayOf(contactId),
    ).firstOrNull()

    override fun queuedFor(contactId: Long): List<Message> = messages(
        "SELECT $MESSAGE_COLS FROM messages WHERE contact_id = ? AND outgoing = 1 AND status = ${Message.QUEUED} ORDER BY id ASC",
        arrayOf(contactId),
    )

    override fun contactsWithQueued(): List<Long> =
        db.query("SELECT DISTINCT contact_id FROM messages WHERE outgoing = 1 AND status = ${Message.QUEUED}").use { c ->
            val out = ArrayList<Long>()
            while (c.moveToNext()) out += c.getLong(0)
            out
        }

    override fun messageSetStatus(id: Long, status: Int) {
        db.execSQL("UPDATE messages SET status = ? WHERE id = ?", arrayOf<Any?>(status, id))
    }

    override fun messageDelete(id: Long) {
        transaction {
            db.execSQL("DELETE FROM attachment_chunks WHERE message_id = ?", arrayOf<Any?>(id))
            db.execSQL("DELETE FROM attachments WHERE message_id = ?", arrayOf<Any?>(id))
            db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf<Any?>(id))
        }
    }

    // ------------------------------------------------------------------ disappearing messages

    override fun messageStartExpiry(id: Long, now: Long) {
        db.execSQL(
            "UPDATE messages SET expires_at = ? + expire_s * 1000 WHERE id = ? AND expire_s > 0 AND expires_at = 0",
            arrayOf<Any?>(now, id),
        )
    }

    override fun messagesStartExpiryOnRead(contactId: Long, now: Long): Int {
        val st = db.compileStatement(
            "UPDATE messages SET expires_at = ? + expire_s * 1000 " +
                "WHERE contact_id = ? AND outgoing = 0 AND expire_s > 0 AND expires_at = 0",
        )
        return st.use {
            it.bindLong(1, now)
            it.bindLong(2, contactId)
            it.executeUpdateDelete()
        }
    }

    override fun deleteExpired(now: Long): Int = transaction {
        val gone = "SELECT id FROM messages WHERE expires_at > 0 AND expires_at <= ?"
        db.execSQL("DELETE FROM attachment_chunks WHERE message_id IN ($gone)", arrayOf<Any?>(now))
        db.execSQL("DELETE FROM attachments WHERE message_id IN ($gone)", arrayOf<Any?>(now))
        db.compileStatement("DELETE FROM messages WHERE expires_at > 0 AND expires_at <= ?").use {
            it.bindLong(1, now)
            it.executeUpdateDelete()
        }
    }

    // ------------------------------------------------------------------ photos and voice notes

    /**
     * Stored in pieces, because Android can only read about 2 MB out of a database row at a time.
     * Like everything else in this database, the pieces are encrypted on disk.
     */
    override fun attachmentPut(messageId: Long, width: Int, height: Int, durationMs: Int, data: ByteArray) {
        transaction {
            db.execSQL("DELETE FROM attachment_chunks WHERE message_id = ?", arrayOf<Any?>(messageId))
            db.execSQL(
                "INSERT OR REPLACE INTO attachments (message_id, width, height, duration_ms, size) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(messageId, width, height, durationMs, data.size),
            )
            var seq = 0
            var at = 0
            while (at < data.size) {
                val end = minOf(at + CHUNK, data.size)
                db.execSQL(
                    "INSERT INTO attachment_chunks (message_id, seq, data) VALUES (?, ?, ?)",
                    arrayOf<Any?>(messageId, seq++, data.copyOfRange(at, end)),
                )
                at = end
            }
        }
    }

    override fun attachmentInfo(messageId: Long): Attachment? =
        db.query("SELECT width, height, duration_ms, size FROM attachments WHERE message_id = ?", arrayOf<Any?>(messageId)).use { c ->
            if (c.moveToFirst()) Attachment(messageId, c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3)) else null
        }

    override fun attachmentData(messageId: Long): ByteArray? {
        val info = attachmentInfo(messageId) ?: return null
        val out = ByteArray(info.size)
        var at = 0
        var seq = 0
        // One piece per query keeps each read well inside Android's row-size limit.
        while (at < out.size) {
            val piece = db.query(
                "SELECT data FROM attachment_chunks WHERE message_id = ? AND seq = ?", arrayOf<Any?>(messageId, seq++),
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null } ?: return null
            if (at + piece.size > out.size) return null
            System.arraycopy(piece, 0, out, at, piece.size)
            at += piece.size
        }
        return out
    }

    // ------------------------------------------------------------------ backup

    private fun tables(): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name").use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    private fun columns(table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out += c.getString(1)
            out
        }

    /** Writes every row of every table, as one consistent snapshot. The caller encrypts the stream. */
    fun dump(out: DataOutputStream) {
        transaction {
            out.writeInt(DUMP_FORMAT)
            out.writeInt(VERSION)
            for (table in tables()) {
                db.query("SELECT * FROM $table").use { c ->
                    str(out, table)
                    out.writeInt(c.columnCount)
                    for (name in c.columnNames) str(out, name)
                    while (c.moveToNext()) {
                        out.writeByte(1)
                        for (i in 0 until c.columnCount) {
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> out.writeByte(0)
                                Cursor.FIELD_TYPE_INTEGER -> { out.writeByte(1); out.writeLong(c.getLong(i)) }
                                Cursor.FIELD_TYPE_FLOAT -> { out.writeByte(2); out.writeDouble(c.getDouble(i)) }
                                Cursor.FIELD_TYPE_STRING -> { out.writeByte(3); str(out, c.getString(i)) }
                                else -> {
                                    val b = c.getBlob(i)
                                    out.writeByte(4); out.writeInt(b.size); out.write(b)
                                }
                            }
                        }
                    }
                    out.writeByte(0)
                }
            }
            str(out, "")
        }
    }

    /**
     * Fills this (new, empty) database from a dump. Table and column names in the file are
     * checked against the real database before use, so a doctored file cannot inject anything.
     */
    @Throws(IOException::class)
    fun restore(inp: DataInputStream) {
        transaction {
            if (inp.readInt() != DUMP_FORMAT) throw IOException("This backup is in a format this version cannot read.")
            if (inp.readInt() > VERSION) throw IOException("This backup was made by a newer version of Backchannel. Update the app first.")
            val known = tables().associateWith { columns(it) }
            while (true) {
                val table = str(inp, 64)
                if (table.isEmpty()) break
                val n = inp.readInt()
                if (n < 1 || n > 64) throw IOException("The backup file is damaged.")
                val cols = List(n) { str(inp, 64) }
                val have = known[table]
                if (have == null || !have.containsAll(cols)) throw IOException("The backup file does not match this version of Backchannel.")
                db.execSQL("DELETE FROM $table")
                val sql = "INSERT INTO $table (${cols.joinToString(", ")}) VALUES (${cols.joinToString(", ") { "?" }})"
                db.compileStatement(sql).use { st ->
                    while (inp.readUnsignedByte() == 1) {
                        st.clearBindings()
                        for (i in 1..n) {
                            when (inp.readUnsignedByte()) {
                                0 -> st.bindNull(i)
                                1 -> st.bindLong(i, inp.readLong())
                                2 -> st.bindDouble(i, inp.readDouble())
                                3 -> st.bindString(i, str(inp, 4 * 1024 * 1024))
                                4 -> {
                                    val len = inp.readInt()
                                    if (len < 0 || len > 4 * 1024 * 1024) throw IOException("The backup file is damaged.")
                                    st.bindBlob(i, ByteArray(len).also { inp.readFully(it) })
                                }
                                else -> throw IOException("The backup file is damaged.")
                            }
                        }
                        st.executeInsert()
                    }
                }
            }
        }
    }

    private fun str(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    private fun str(inp: DataInputStream, max: Int): String {
        val n = inp.readInt()
        if (n < 0 || n > max) throw IOException("The backup file is damaged.")
        return String(ByteArray(n).also { inp.readFully(it) }, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ transactions

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    companion object {
        const val FILE = "backchannel.db"
        private const val VERSION = 2
        private const val DUMP_FORMAT = 1
        private const val CHUNK = 256 * 1024
        private const val CONTACT_COLS =
            "id, onion, name, custom_name, state, identity_key, pending_token, created_at, last_seen_at, unread, timer_s, photo"
        private const val MESSAGE_COLS = "id, contact_id, msg_id, outgoing, kind, body, sent_at, status, expire_s, expires_at"
        private val SHOWN = Message.SHOWN.joinToString(",")
        private val WRITTEN = Message.WRITTEN.joinToString(",")

        /** The database as version 0.1 created it. */
        internal val SCHEMA_V1 = listOf(
            "CREATE TABLE kv (k TEXT PRIMARY KEY NOT NULL, v BLOB NOT NULL)",
            "CREATE TABLE invites (token BLOB PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, " +
                "expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0, requests INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, onion TEXT NOT NULL UNIQUE, " +
                "name TEXT NOT NULL, custom_name INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL, " +
                "identity_key BLOB, pending_token BLOB, created_at INTEGER NOT NULL, " +
                "last_seen_at INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id INTEGER NOT NULL, " +
                "msg_id BLOB NOT NULL, outgoing INTEGER NOT NULL, kind INTEGER NOT NULL, body TEXT NOT NULL, " +
                "sent_at INTEGER NOT NULL, status INTEGER NOT NULL, UNIQUE (contact_id, outgoing, msg_id))",
            "CREATE INDEX messages_by_contact ON messages (contact_id, id)",
            "CREATE INDEX messages_by_status ON messages (status, contact_id)",
        )

        /** What version 0.2 adds: disappearing timers, address-book pictures, photos and voice notes. */
        internal val UPGRADE_TO_V2 = listOf(
            "ALTER TABLE contacts ADD COLUMN timer_s INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE contacts ADD COLUMN photo BLOB",
            "ALTER TABLE messages ADD COLUMN expire_s INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX messages_by_expiry ON messages (expires_at)",
            "CREATE TABLE attachments (message_id INTEGER PRIMARY KEY NOT NULL, width INTEGER NOT NULL, " +
                "height INTEGER NOT NULL, duration_ms INTEGER NOT NULL, size INTEGER NOT NULL)",
            "CREATE TABLE attachment_chunks (message_id INTEGER NOT NULL, seq INTEGER NOT NULL, " +
                "data BLOB NOT NULL, PRIMARY KEY (message_id, seq))",
        )

        fun open(context: Context, name: String?, factory: SupportSQLiteOpenHelper.Factory): SqlStorage {
            val callback = object : SupportSQLiteOpenHelper.Callback(VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (sql in SCHEMA_V1) db.execSQL(sql)
                    for (sql in UPGRADE_TO_V2) db.execSQL(sql)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    if (oldVersion < 2) for (sql in UPGRADE_TO_V2) db.execSQL(sql)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Deleted rows are overwritten, not just unlinked.
                    db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
                }
            }
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build()
            return SqlStorage(factory.create(config))
        }
    }
}
