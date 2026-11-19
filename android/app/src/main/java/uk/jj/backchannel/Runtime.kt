package uk.jj.backchannel

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import uk.jj.backchannel.core.Backup
import uk.jj.backchannel.core.Keys
import uk.jj.backchannel.core.Node
import uk.jj.backchannel.core.Socks5
import uk.jj.backchannel.core.Updates
import uk.jj.backchannel.data.SqlStorage
import uk.jj.backchannel.media.Scratch
import uk.jj.backchannel.net.TorManager
import uk.jj.backchannel.net.TorStatus
import uk.jj.backchannel.vault.KeystoreWrap
import uk.jj.backchannel.vault.Vault
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The running app in one place: whether it is set up, whether it is open, and the pieces
 * that exist only while it is open (the database, the messaging engine, Tor).
 */
object Runtime {
    enum class Phase {
        /** First run: nothing created yet. */
        SETUP,

        /** Set up, but the database key is not in memory. Nothing can be read or received. */
        SEALED,

        /** Unlocked: the database is open and the phone is reachable. */
        OPEN,
    }

    enum class Unlocked { YES, WRONG, WIPED }

    sealed class Update {
        object Unknown : Update()
        object Checking : Update()
        object UpToDate : Update()
        object NothingPublished : Update()
        class Available(val version: String) : Update()
        class Failed(val reason: String) : Update()
    }

    val phase = MutableStateFlow(Phase.SEALED)

    /** True when the screens are locked. Messages still arrive in the background. */
    val screensLocked = MutableStateFlow(false)

    /** Goes up by one whenever stored data changes, so screens know to reload. */
    val changes = MutableStateFlow(0L)

    val tor = MutableStateFlow(TorStatus())

    val update = MutableStateFlow<Update>(Update.Unknown)

    @Volatile var storage: SqlStorage? = null
        private set
    @Volatile var node: Node? = null
        private set
    @Volatile var appVisible = false

    /** The chat currently on screen, so its messages don't raise a notification. */
    @Volatile var openChat: Long? = null

    /**
     * Set just before the app sends the user to the camera, a file chooser and so on, so that
     * coming back from it doesn't count as "the app was put away" and lock the screens.
     */
    @Volatile private var awayOnPurposeUntil = 0L

    private var sweeper: java.util.Timer? = null
    private var torManager: TorManager? = null
    private var server: ServerSocket? = null
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
        phase.value = if (vault().exists()) Phase.SEALED else Phase.SETUP
    }

    fun vault(): Vault = Vault(File(app.filesDir, "vault.bin"), hardware())

    /** The phone's secure hardware, and the encrypted database. Swapped for stand-ins only in the on-computer tests. */
    internal var hardware: () -> uk.jj.backchannel.vault.KeyWrap = { KeystoreWrap() }
    internal var database: (ByteArray) -> SqlStorage = { key -> openEncrypted(key) }

    // ------------------------------------------------------------------ locking

    fun leavingForPicker() {
        awayOnPurposeUntil = android.os.SystemClock.elapsedRealtime() + 5 * 60_000
    }

    /** Called when the app comes back into view after [awayMs] out of sight. */
    fun cameBack(awayMs: Long) {
        val onPurpose = android.os.SystemClock.elapsedRealtime() < awayOnPurposeUntil
        awayOnPurposeUntil = 0
        if (!onPurpose && awayMs >= Config.lockAfterMs(app)) {
            lockScreens()
            Scratch.clear(app)
        }
    }

    fun lockScreens() {
        if (phase.value == Phase.OPEN) screensLocked.value = true
    }

    /** A fingerprint can open the screens only while the database is already open. */
    fun unlockScreensWithFingerprint() {
        if (phase.value == Phase.OPEN && Config.fingerprint(app)) screensLocked.value = false
    }

    /** First-time setup: makes the vault, the database and this phone's keys. */
    @Synchronized
    fun setUp(secret: CharArray, lockType: Int, name: String) {
        check(phase.value == Phase.SETUP)
        app.deleteDatabase(SqlStorage.FILE)
        val s = openStorage(vault().create(secret, lockType))
        start(s)
        node?.profileName = name
    }

    /**
     * Tries a PIN or passphrase. The right one opens the database (or just the screens, if
     * the app was already running). The duress PIN wipes everything.
     */
    @Synchronized
    fun unlock(secret: CharArray): Unlocked =
        when (val r = vault().check(secret)) {
            is Vault.Result.Open -> {
                if (phase.value != Phase.OPEN) start(openStorage(r.key)) else r.key.fill(0)
                screensLocked.value = false
                Unlocked.YES
            }
            Vault.Result.Duress -> {
                wipe()
                Unlocked.WIPED
            }
            Vault.Result.Wrong -> Unlocked.WRONG
        }

    @Synchronized
    fun changeSecret(old: CharArray, new: CharArray, lockType: Int) = vault().changeSecret(old, new, lockType)

    @Synchronized
    fun setDuress(current: CharArray, duress: CharArray?) {
        vault().setDuress(current, duress)
        storage?.kvPut(K_DURESS, byteArrayOf(if (duress == null) 0 else 1))
    }

    /** Whether a duress PIN is set. Only knowable once the app is unlocked. */
    fun hasDuress(): Boolean = storage?.kvGet(K_DURESS)?.firstOrNull()?.toInt() == 1

    private fun openStorage(key: ByteArray): SqlStorage = database(key)

    private fun openEncrypted(key: ByteArray): SqlStorage {
        System.loadLibrary("sqlcipher")
        // The key is random already, so it is given to SQLCipher as a raw key.
        val raw = ("x'" + key.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)
        key.fill(0)
        return SqlStorage.open(app, SqlStorage.FILE, SupportOpenHelperFactory(raw))
    }

    private fun start(s: SqlStorage) {
        val n = Node(
            storage = s,
            transport = { onion ->
                val t = torManager
                if (t == null || !t.connected) throw IOException("Tor is not connected")
                Socks5.connect(t.socksPort, "$onion.onion", TorManager.ONION_PORT, 120_000)
            },
            events = object : Node.Events {
                override fun onChanged() = changes.update { it + 1 }

                override fun onIncomingText(contactId: Long) {
                    val watching = appVisible && !screensLocked.value && openChat == contactId
                    if (!watching) Notify.message(app)
                }

                override fun onContactAdded(contactId: Long) {
                    if (!appVisible || screensLocked.value) Notify.message(app)
                }
            },
        )
        storage = s
        node = n
        phase.value = Phase.OPEN
        screensLocked.value = false
        // Disappearing messages are cleared out every few seconds for as long as the app is running.
        sweeper?.cancel()
        sweeper = kotlin.concurrent.timer("bc-sweep", daemon = true, initialDelay = 1_000, period = 10_000) {
            try { n.sweep() } catch (_: Exception) {}
        }
        NodeService.start(app)
    }

    // ------------------------------------------------------------------ wipe

    /**
     * Destroys everything: messages, contacts, keys, this phone's Tor address, settings. The
     * hardware key is deleted first, which on its own makes the database unreadable for good;
     * the files are then overwritten and removed. The app is left as if newly installed.
     */
    @Synchronized
    fun wipe() {
        sweeper?.cancel()
        sweeper = null
        val n = node
        val s = storage
        val t = torManager
        node = null
        storage = null
        torManager = null
        openChat = null
        vault().destroy()
        try { n?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { s?.close() } catch (_: Exception) {}
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            Scratch.wipe(app.getDatabasePath(SqlStorage.FILE + suffix))
        }
        Scratch.clear(app)
        Config.clear(app)
        val torDir = app.getDir("tor", android.content.Context.MODE_PRIVATE)
        if (t != null) t.stop { torDir.deleteRecursively() } else torDir.deleteRecursively()
        try { app.stopService(Intent(app, NodeService::class.java)) } catch (_: Exception) {}
        app.getSystemService(NotificationManager::class.java)?.cancelAll()
        tor.value = TorStatus()
        update.value = Update.Unknown
        screensLocked.value = false
        changes.update { it + 1 }
        phase.value = Phase.SETUP
    }

    // ------------------------------------------------------------------ backup

    /** Writes everything into one encrypted file. Blocks until done; call it off the main thread. */
    @Throws(IOException::class)
    fun exportBackup(out: OutputStream, passphrase: CharArray) {
        val s = storage ?: throw IOException("Backchannel is locked.")
        Backup.Writer(out, passphrase).use { sealed ->
            val data = DataOutputStream(BufferedOutputStream(sealed, 64 * 1024))
            s.dump(data)
            data.flush()
        }
    }

    /**
     * Sets up a new phone from a backup file. Throws [uk.jj.backchannel.core.WrongSecretException]
     * if the backup passphrase is wrong, before anything on this phone has been changed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun restore(file: InputStream, backupPassphrase: CharArray, secret: CharArray, lockType: Int) {
        check(phase.value == Phase.SETUP)
        Backup.Reader(BufferedInputStream(file, 64 * 1024), backupPassphrase).use { opened ->
            app.deleteDatabase(SqlStorage.FILE)
            val s = openStorage(vault().create(secret, lockType))
            try {
                s.restore(DataInputStream(opened))
                // The old phone's duress PIN does not come across: it lived in that phone's vault.
                s.kvPut(K_DURESS, byteArrayOf(0))
                Node.prepareRestored(s, System.currentTimeMillis())
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                app.deleteDatabase(SqlStorage.FILE)
                vault().destroy()
                throw e
            }
            start(s)
        }
    }

    // ------------------------------------------------------------------ updates

    /** Asks GitHub, over Tor, for the newest published version. Blocks; call it off the main thread. */
    fun checkForUpdate() {
        val t = torManager
        if (t == null || !t.connected) {
            update.value = Update.Failed("Tor is not connected yet. Try again once it says Online.")
            return
        }
        update.value = Update.Checking
        update.value = try {
            val latest = Updates.latest { host, port -> Socks5.connect(t.socksPort, host, port, 60_000) }
            Config.setLastUpdateCheck(app, System.currentTimeMillis())
            when {
                latest == null -> Update.NothingPublished
                Updates.isNewer(latest.version, BuildConfig.VERSION_NAME) -> Update.Available(latest.version)
                else -> Update.UpToDate
            }
        } catch (e: Exception) {
            Update.Failed("Could not reach GitHub over Tor. Try again later.")
        }
    }

    private fun checkForUpdateIfDue() {
        if (!Config.autoUpdateCheck(app)) return
        if (System.currentTimeMillis() - Config.lastUpdateCheck(app) < 24 * 60 * 60_000L) return
        Thread({ checkForUpdate() }, "bc-update").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ network

    /** Called by the background service: start listening and bring Tor up. Safe to call twice. */
    @Synchronized
    fun startNetwork() {
        val n = node ?: return
        val s = storage ?: return
        if (torManager != null) return
        val listen = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        server = listen
        n.serve(listen)
        val t = TorManager(app, tor)
        torManager = t
        val savedKey = s.kvGet(Keys.K_ONION_KEY)?.let { String(it, Charsets.US_ASCII) }
        t.start(
            localPort = listen.localPort,
            savedKey = savedKey,
            onAddress = { onion, key ->
                s.kvPut(Keys.K_ONION_KEY, key.toByteArray(Charsets.US_ASCII))
                n.selfOnion = onion
                changes.update { it + 1 }
            },
            onConnected = {
                n.kick()
                checkForUpdateIfDue()
            },
        )
    }

    /** Used only by the on-computer screen tests: run on a database that is already open. */
    internal fun attachForTests(application: Application, s: SqlStorage, n: Node?) {
        app = application
        storage = s
        node = n
        phase.value = Phase.OPEN
        screensLocked.value = false
    }

    private const val K_DURESS = "self:duress"
}
