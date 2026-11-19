package uk.jj.backchannel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.jj.backchannel.core.Kdf
import uk.jj.backchannel.vault.KeyWrap
import uk.jj.backchannel.vault.Vault
import uk.jj.backchannel.core.WrongSecretException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stands in for the phone's secure hardware: a key that exists only inside this object. */
private class FakeHardware : KeyWrap {
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

class VaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fast = Kdf.Params(memoryKiB = 1024, iterations = 1, parallelism = 1)

    @Test fun rightSecretOpensWrongSecretDoesNot() {
        val file = File(tmp.root, "vault.bin")
        val hw = FakeHardware()
        val vault = Vault(file, hw, fast)
        assertFalse(vault.exists())
        val key = vault.create("839471".toCharArray(), Vault.LOCK_PIN)
        assertEquals(32, key.size)
        assertTrue(vault.exists())
        assertEquals(Vault.LOCK_PIN, vault.lockType())

        assertArrayEquals(key, Vault(file, hw, fast).unlock("839471".toCharArray()))
        for (wrong in listOf("839472", "83947", "8394710", "")) {
            try {
                vault.unlock(wrong.toCharArray())
                fail("'$wrong' should not open the vault")
            } catch (_: WrongSecretException) {
            }
        }
    }

    @Test fun theKeyIsNotSittingInTheFile() {
        val file = File(tmp.root, "vault.bin")
        val vault = Vault(file, FakeHardware(), fast)
        val key = vault.create("correct horse battery".toCharArray(), Vault.LOCK_PASSPHRASE)
        val onDisk = file.readBytes()
        assertFalse("database key must not appear in the vault file", contains(onDisk, key))
        assertFalse(contains(onDisk, "correct horse battery".toByteArray()))
        assertEquals(Vault.LOCK_PASSPHRASE, vault.lockType())
    }

    @Test fun theFileIsUselessOnAnotherPhone() {
        val file = File(tmp.root, "vault.bin")
        Vault(file, FakeHardware(), fast).create("839471".toCharArray(), Vault.LOCK_PIN)
        // Same file, right PIN, but a different phone's hardware key.
        val otherPhone = FakeHardware().also { it.wrap(ByteArray(1)) }
        try {
            Vault(file, otherPhone, fast).unlock("839471".toCharArray())
            fail("vault opened without the original hardware key")
        } catch (_: IOException) {
        }
    }

    @Test fun aTamperedFileDoesNotOpen() {
        val file = File(tmp.root, "vault.bin")
        val hw = FakeHardware()
        Vault(file, hw, fast).create("839471".toCharArray(), Vault.LOCK_PIN)
        val bytes = file.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            Vault(file, hw, fast).unlock("839471".toCharArray())
            fail("tampered vault opened")
        } catch (_: IOException) {
        } catch (_: WrongSecretException) {
        }
    }

    @Test fun theDuressPinIsRecognisedAndIsNeverMistakenForTheRealOne() {
        val file = File(tmp.root, "vault.bin")
        val vault = Vault(file, FakeHardware(), fast)
        val key = vault.create("839471".toCharArray(), Vault.LOCK_PIN)

        // Before one is set, nothing counts as a duress PIN.
        assertTrue(vault.check("555555".toCharArray()) is Vault.Result.Wrong)

        vault.setDuress("839471".toCharArray(), "246810".toCharArray())
        assertTrue(vault.check("246810".toCharArray()) is Vault.Result.Duress)
        assertTrue(vault.check("246811".toCharArray()) is Vault.Result.Wrong)
        val real = vault.check("839471".toCharArray())
        assertTrue(real is Vault.Result.Open)
        assertArrayEquals(key, (real as Vault.Result.Open).key)
        try {
            vault.unlock("246810".toCharArray())
            fail("the duress PIN must never return the database key")
        } catch (_: WrongSecretException) {
        }

        // Removing it again.
        vault.setDuress("839471".toCharArray(), null)
        assertTrue(vault.check("246810".toCharArray()) is Vault.Result.Wrong)
    }

    @Test fun settingADuressPinNeedsTheRealPinAndMustDifferFromIt() {
        val file = File(tmp.root, "vault.bin")
        val vault = Vault(file, FakeHardware(), fast)
        vault.create("839471".toCharArray(), Vault.LOCK_PIN)
        try {
            vault.setDuress("000000".toCharArray(), "246810".toCharArray())
            fail("set a duress PIN without the real PIN")
        } catch (_: WrongSecretException) {
        }
        try {
            vault.setDuress("839471".toCharArray(), "839471".toCharArray())
            fail("duress PIN equal to the real PIN was accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(vault.check("246810".toCharArray()) is Vault.Result.Wrong)
    }

    @Test fun theFileGivesNoSignOfWhetherADuressPinExists() {
        val a = File(tmp.root, "a.bin")
        val b = File(tmp.root, "b.bin")
        Vault(a, FakeHardware(), fast).create("839471".toCharArray(), Vault.LOCK_PIN)
        Vault(b, FakeHardware(), fast).apply {
            create("839471".toCharArray(), Vault.LOCK_PIN)
            setDuress("839471".toCharArray(), "246810".toCharArray())
        }
        assertEquals("same size with or without a duress PIN", a.length(), b.length())
        assertFalse(contains(b.readBytes(), "246810".toByteArray()))
    }

    @Test fun changingThePinKeepsTheSameDatabaseKeyAndTheDuressPin() {
        val file = File(tmp.root, "vault.bin")
        val hw = FakeHardware()
        val vault = Vault(file, hw, fast)
        val key = vault.create("839471".toCharArray(), Vault.LOCK_PIN)
        vault.setDuress("839471".toCharArray(), "246810".toCharArray())

        try {
            vault.changeSecret("111111".toCharArray(), "new passphrase here".toCharArray(), Vault.LOCK_PASSPHRASE)
            fail("changed the PIN without knowing the old one")
        } catch (_: WrongSecretException) {
        }
        try {
            vault.changeSecret("839471".toCharArray(), "246810".toCharArray(), Vault.LOCK_PIN)
            fail("the duress PIN was accepted as the new real PIN")
        } catch (_: IllegalArgumentException) {
        }

        vault.changeSecret("839471".toCharArray(), "new passphrase here".toCharArray(), Vault.LOCK_PASSPHRASE)
        assertEquals(Vault.LOCK_PASSPHRASE, vault.lockType())
        assertTrue("the old PIN must stop working", vault.check("839471".toCharArray()) is Vault.Result.Wrong)
        assertArrayEquals(key, Vault(file, hw, fast).unlock("new passphrase here".toCharArray()))
        assertTrue("the duress PIN carries over", vault.check("246810".toCharArray()) is Vault.Result.Duress)
    }

    @Test fun aVaultFileFromVersion02StillOpensAndCanTakeADuressPin() {
        val file = File(tmp.root, "vault.bin")
        val hw = FakeHardware()
        val vault = Vault(file, hw, fast)
        val key = vault.create("839471".toCharArray(), Vault.LOCK_PIN)
        // Rewrite it the way 0.1 and 0.2 did: "BCV1" and no duress field.
        val v2 = file.readBytes()
        val head = 4 + 1 + 12 + 16
        file.writeBytes("BCV1".toByteArray() + v2.copyOfRange(4, head) + v2.copyOfRange(head + 32, v2.size))

        val old = Vault(file, hw, fast)
        assertEquals(Vault.LOCK_PIN, old.lockType())
        assertArrayEquals(key, old.unlock("839471".toCharArray()))
        assertTrue(old.check("000000".toCharArray()) is Vault.Result.Wrong)
        old.setDuress("839471".toCharArray(), "246810".toCharArray())
        assertTrue(old.check("246810".toCharArray()) is Vault.Result.Duress)
        assertArrayEquals(key, old.unlock("839471".toCharArray()))
    }

    @Test fun destroyingTheVaultLeavesNothingToOpen() {
        val file = File(tmp.root, "vault.bin")
        val hw = FakeHardware()
        val vault = Vault(file, hw, fast)
        vault.create("839471".toCharArray(), Vault.LOCK_PIN)
        vault.destroy()
        assertFalse(vault.exists())
        assertEquals(null, hw.key)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
