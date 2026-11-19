package uk.jj.backchannel

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.core.WrongSecretException
import uk.jj.backchannel.data.SqlStorage
import uk.jj.backchannel.vault.KeyWrap
import uk.jj.backchannel.vault.Vault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app's own top-level logic (set up, unlock, duress wipe, change PIN, backup, restore)
 * run for real, with two stand-ins for things that only exist on a phone: the security chip
 * and the encrypted database engine. Everything else is the code that ships.
 */
@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34], application = Application::class)
class RuntimeTest {
    private class Chip : KeyWrap {
        var key: ByteArray? = null
        override fun wrap(plain: ByteArray): ByteArray {
            val k = key ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            key = k
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun unwrap(blob: ByteArray): ByteArray {
            val k = key ?: throw GeneralSecurityException("no key")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
        override fun destroy() { key = null }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val chip = Chip()
    private val pin = "839471"

    @Before fun setUp() {
        Runtime.hardware = { chip }
        Runtime.database = { SqlStorage.open(app, SqlStorage.FILE, FrameworkSQLiteOpenHelperFactory()) }
        Runtime.init(app)
        assertEquals(Runtime.Phase.SETUP, Runtime.phase.value)
    }

    @After fun tearDown() {
        Runtime.wipe()
    }

    private fun dbFile(): File = app.getDatabasePath(SqlStorage.FILE)
    private fun vaultFile() = File(app.filesDir, "vault.bin")

    private fun addDave(): Long {
        val s = Runtime.storage!!
        val id = s.contactInsert("d".repeat(56), "Dave", true, Contact.ACTIVE, ByteArray(33) { 5 }, null, 1)
        s.messageInsert(id, ByteArray(16) { 1 }, false, Message.KIND_TEXT, "the plan for thursday", 10, Message.RECEIVED)
        val photo = s.messageInsert(id, ByteArray(16) { 2 }, true, Message.KIND_PHOTO, "", 11, Message.DELIVERED)!!
        s.attachmentPut(photo, 100, 100, 0, ByteArray(300_000) { it.toByte() })
        s.kvPut("ss:" + "d".repeat(56), ByteArray(500) { 3 })
        s.kvPut("id:" + "d".repeat(56), ByteArray(33) { 5 })
        return id
    }

    @Test fun settingUpOpensTheAppAndTheRightPinUnlocksIt() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "Jon")
        assertEquals(Runtime.Phase.OPEN, Runtime.phase.value)
        assertEquals("Jon", Runtime.node!!.profileName)
        assertTrue(vaultFile().exists())
        assertFalse(Runtime.hasDuress())

        Runtime.lockScreens()
        assertTrue(Runtime.screensLocked.value)
        assertEquals(Runtime.Unlocked.WRONG, Runtime.unlock("839472".toCharArray()))
        assertTrue("a wrong PIN leaves the screens locked", Runtime.screensLocked.value)
        assertEquals(Runtime.Unlocked.YES, Runtime.unlock(pin.toCharArray()))
        assertFalse(Runtime.screensLocked.value)
    }

    @Test fun aFingerprintOnlyOpensTheScreensWhenItHasBeenTurnedOn() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "")
        Runtime.lockScreens()
        Runtime.unlockScreensWithFingerprint()
        assertTrue("fingerprint is off by default", Runtime.screensLocked.value)
        Config.setFingerprint(app, true)
        Runtime.unlockScreensWithFingerprint()
        assertFalse(Runtime.screensLocked.value)
    }

    @Test fun theDuressPinWipesEverythingAndLeavesTheAppLikeNew() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "Jon")
        addDave()
        Config.setFingerprint(app, true)
        Runtime.setDuress(pin.toCharArray(), "246810".toCharArray())
        assertTrue(Runtime.hasDuress())
        assertTrue(dbFile().exists())
        Runtime.lockScreens()

        assertEquals(Runtime.Unlocked.WIPED, Runtime.unlock("246810".toCharArray()))

        assertEquals("the app shows the first-run screens", Runtime.Phase.SETUP, Runtime.phase.value)
        assertFalse(Runtime.screensLocked.value)
        assertNull(Runtime.storage)
        assertNull(Runtime.node)
        assertFalse("database file must be gone", dbFile().exists())
        assertFalse("vault file must be gone", vaultFile().exists())
        assertNull("the hardware key must be destroyed", chip.key)
        assertFalse("settings are cleared too", Config.fingerprint(app))
        assertFalse(Runtime.vault().exists())

        // The real PIN opens nothing now: there is nothing left to open.
        try {
            Runtime.unlock(pin.toCharArray())
            fail("something was still there to unlock")
        } catch (_: Exception) {
        }

        // And the app can be set up again from scratch, with no trace of what was there.
        Runtime.setUp("555123".toCharArray(), Vault.LOCK_PIN, "")
        assertTrue(Runtime.storage!!.contacts().isEmpty())
        assertFalse(Runtime.hasDuress())
    }

    @Test fun theDuressPinCannotBeSetWithoutTheRealPin() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "")
        try {
            Runtime.setDuress("000000".toCharArray(), "246810".toCharArray())
            fail("set without the real PIN")
        } catch (_: WrongSecretException) {
        }
        assertFalse(Runtime.hasDuress())
        Runtime.lockScreens()
        assertEquals(Runtime.Unlocked.WRONG, Runtime.unlock("246810".toCharArray()))
        assertEquals(Runtime.Phase.OPEN, Runtime.phase.value)
    }

    @Test fun changingThePinKeepsEverythingAndTheOldPinStopsWorking() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "Jon")
        val dave = addDave()
        Runtime.changeSecret(pin.toCharArray(), "correct horse battery".toCharArray(), Vault.LOCK_PASSPHRASE)
        assertEquals(Vault.LOCK_PASSPHRASE, Runtime.vault().lockType())
        Runtime.lockScreens()
        assertEquals(Runtime.Unlocked.WRONG, Runtime.unlock(pin.toCharArray()))
        assertEquals(Runtime.Unlocked.YES, Runtime.unlock("correct horse battery".toCharArray()))
        assertEquals("Dave", Runtime.storage!!.contactById(dave)!!.name)
    }

    @Test fun aBackupRestoresOntoANewPhoneAndWrongPassphrasesChangeNothing() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "Jon")
        val dave = addDave()
        Runtime.setDuress(pin.toCharArray(), "246810".toCharArray())
        val identity = Runtime.storage!!.kvGet("self:identity")!!
        val file = ByteArrayOutputStream()
        Runtime.exportBackup(file, "four unrelated words here".toCharArray())
        val backup = file.toByteArray()
        assertFalse("nothing readable in the backup file", String(backup, Charsets.ISO_8859_1).contains("thursday"))

        // A different phone: nothing on it, and a different security chip.
        Runtime.wipe()
        assertEquals(Runtime.Phase.SETUP, Runtime.phase.value)

        try {
            Runtime.restore(ByteArrayInputStream(backup), "not the passphrase".toCharArray(), "111222".toCharArray(), Vault.LOCK_PIN)
            fail("restored with the wrong passphrase")
        } catch (_: WrongSecretException) {
        }
        assertEquals("a wrong passphrase leaves the new phone untouched", Runtime.Phase.SETUP, Runtime.phase.value)
        assertFalse(vaultFile().exists())

        Runtime.restore(ByteArrayInputStream(backup), "four unrelated words here".toCharArray(), "111222".toCharArray(), Vault.LOCK_PIN)
        assertEquals(Runtime.Phase.OPEN, Runtime.phase.value)
        val s = Runtime.storage!!
        assertEquals("Dave", s.contactById(dave)!!.name)
        assertEquals("the plan for thursday", s.messagesFor(dave, 10).first().body)
        assertEquals(300_000, s.attachmentData(s.messagesFor(dave, 10).last().id)!!.size)
        assertEquals("Jon", Runtime.node!!.profileName)
        assertTrue("this phone is the same person to its contacts", identity.contentEquals(s.kvGet("self:identity")))
        assertNotNull("contacts' identity keys are kept", s.kvGet("id:" + "d".repeat(56)))
        assertNull("out-of-date sessions are dropped", s.kvGet("ss:" + "d".repeat(56)))
        assertEquals("each contact is queued a fresh-keys handshake", 1, s.queuedFor(dave).count { it.kind == Message.KIND_PROFILE })
        assertFalse("the old phone's duress PIN does not come across", Runtime.hasDuress())

        // The new phone opens with its own new PIN, not the old one.
        Runtime.lockScreens()
        assertEquals(Runtime.Unlocked.WRONG, Runtime.unlock(pin.toCharArray()))
        assertEquals(Runtime.Unlocked.WRONG, Runtime.unlock("246810".toCharArray()))
        assertEquals(Runtime.Unlocked.YES, Runtime.unlock("111222".toCharArray()))
    }

    @Test fun aDamagedBackupLeavesTheNewPhoneClean() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "Jon")
        addDave()
        val file = ByteArrayOutputStream()
        Runtime.exportBackup(file, "four unrelated words here".toCharArray())
        val damaged = file.toByteArray().also { it[it.size - 50] = (it[it.size - 50].toInt() xor 1).toByte() }
        Runtime.wipe()
        try {
            Runtime.restore(ByteArrayInputStream(damaged), "four unrelated words here".toCharArray(), "111222".toCharArray(), Vault.LOCK_PIN)
            fail("a damaged backup was accepted")
        } catch (_: Exception) {
        }
        assertEquals(Runtime.Phase.SETUP, Runtime.phase.value)
        assertFalse(vaultFile().exists())
        assertFalse(dbFile().exists())
    }

    @Test fun comingBackFromTheCameraDoesNotLockButBeingAwayDoes() {
        Runtime.setUp(pin.toCharArray(), Vault.LOCK_PIN, "")
        Config.setLockAfterMs(app, 60_000)
        Runtime.cameBack(awayMs = 5_000)
        assertFalse("a few seconds away does not lock", Runtime.screensLocked.value)
        Runtime.leavingForPicker()
        Runtime.cameBack(awayMs = 120_000)
        assertFalse("time spent in the camera or a file chooser does not lock", Runtime.screensLocked.value)
        Runtime.cameBack(awayMs = 120_000)
        assertTrue("two minutes away, not on purpose, locks", Runtime.screensLocked.value)

        Runtime.unlock(pin.toCharArray())
        Config.setLockAfterMs(app, 0)
        Runtime.cameBack(awayMs = 1)
        assertTrue("'straight away' locks on any return", Runtime.screensLocked.value)
    }
}
