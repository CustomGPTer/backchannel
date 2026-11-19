package uk.jj.backchannel.vault

import uk.jj.backchannel.core.Kdf
import uk.jj.backchannel.core.WrongSecretException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A key that lives in the phone's secure hardware and never leaves it. */
interface KeyWrap {
    /** Encrypts with the hardware key, making one first if there isn't one. */
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
    fun destroy()
}


/**
 * Looks after the one key that encrypts the database.
 *
 * That key is random. It is stored locked twice over: once with a key made from the user's
 * PIN or passphrase (slowly, with Argon2id), and once more with a key held in the phone's
 * secure hardware. Opening it needs both the right PIN and this physical phone.
 *
 * The file also holds a fingerprint of the duress PIN. A file with no duress PIN holds
 * random bytes in the same place, so nobody reading it can tell whether one has been set.
 */
class Vault(
    private val file: File,
    private val hardware: KeyWrap,
    private val params: Kdf.Params = Kdf.DEFAULT,
) {
    sealed class Result {
        /** The right PIN. [key] opens the database. */
        class Open(val key: ByteArray) : Result()

        /** The duress PIN. Everything should be wiped. */
        object Duress : Result()
        object Wrong : Result()
    }

    fun exists(): Boolean = file.isFile && file.length() > 0

    /** Whether the user chose a PIN or a passphrase, so the right keyboard can be shown. */
    fun lockType(): Int = try {
        read().lockType
    } catch (_: IOException) {
        LOCK_PIN
    }

    /** First-time setup. Returns the new database key. */
    fun create(secret: CharArray, lockType: Int): ByteArray {
        val rng = SecureRandom()
        val dataKey = ByteArray(32).also { rng.nextBytes(it) }
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val pinKey = Kdf.derive(secret, salt, params)
        hardware.destroy()
        val outer = hardware.wrap(seal(pinKey, dataKey))
        pinKey.fill(0)
        write(Stored(lockType, params, salt, randomTag(), outer))
        return dataKey
    }

    /** Tries a PIN or passphrase. Costs one slow key derivation whatever the answer. */
    @Throws(IOException::class)
    fun check(secret: CharArray): Result {
        val v = read()
        val inner = try {
            hardware.unwrap(v.outer)
        } catch (e: GeneralSecurityException) {
            throw IOException("this phone's secure key is missing or damaged", e)
        }
        if (inner.size < 12 + 16) throw IOException("vault damaged")
        val pinKey = Kdf.derive(secret, v.salt, v.params)
        try {
            val key = open(pinKey, inner)
            if (key != null) return Result.Open(key)
            val isDuress = v.duressTag != null && MessageDigest.isEqual(tag(pinKey), v.duressTag)
            return if (isDuress) Result.Duress else Result.Wrong
        } finally {
            pinKey.fill(0)
        }
    }

    /** Returns the database key, or throws [WrongSecretException]. The duress PIN counts as wrong here. */
    @Throws(WrongSecretException::class, IOException::class)
    fun unlock(secret: CharArray): ByteArray = when (val r = check(secret)) {
        is Result.Open -> r.key
        else -> throw WrongSecretException()
    }

    /** Swaps the PIN or passphrase for a new one. The database key itself does not change. */
    @Throws(WrongSecretException::class, IOException::class)
    fun changeSecret(old: CharArray, new: CharArray, lockType: Int) {
        val v = read()
        val dataKey = unlock(old)
        val newKey = Kdf.derive(new, v.salt, v.params)
        try {
            if (v.duressTag != null && MessageDigest.isEqual(tag(newKey), v.duressTag)) {
                throw IllegalArgumentException("That is your duress PIN. Choose something different.")
            }
            write(Stored(lockType, v.params, v.salt, v.duressTag ?: randomTag(), hardware.wrap(seal(newKey, dataKey))))
        } finally {
            newKey.fill(0)
            dataKey.fill(0)
        }
    }

    /**
     * Sets the duress PIN, or removes it when [duress] is null. Needs the real PIN, so nobody
     * holding the phone unlocked can quietly plant one.
     */
    @Throws(WrongSecretException::class, IOException::class)
    fun setDuress(current: CharArray, duress: CharArray?) {
        val v = read()
        unlock(current).fill(0)
        val newTag = if (duress == null) {
            randomTag()
        } else {
            val k = Kdf.derive(duress, v.salt, v.params)
            try {
                val inner = hardware.unwrap(v.outer)
                if (open(k, inner) != null) throw IllegalArgumentException("The duress PIN must be different from your real one.")
                tag(k)
            } finally {
                k.fill(0)
            }
        }
        write(Stored(v.lockType, v.params, v.salt, newTag, v.outer))
    }

    fun destroy() {
        file.delete()
        hardware.destroy()
    }

    private class Stored(val lockType: Int, val params: Kdf.Params, val salt: ByteArray, val duressTag: ByteArray?, val outer: ByteArray)

    private fun write(s: Stored) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(MAGIC_V2)
            writeByte(s.lockType)
            writeInt(s.params.memoryKiB)
            writeInt(s.params.iterations)
            writeInt(s.params.parallelism)
            write(s.salt)
            write(s.duressTag ?: randomTag())
            writeInt(s.outer.size)
            write(s.outer)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes.toByteArray())
        if (!tmp.renameTo(file)) throw IOException("could not save the vault")
    }

    private fun read(): Stored {
        val inp = DataInputStream(ByteArrayInputStream(file.readBytes()))
        val magic = ByteArray(MAGIC_V2.size).also { inp.readFully(it) }
        // Versions 0.1 and 0.2 wrote the same file without a duress tag.
        val v2 = magic.contentEquals(MAGIC_V2)
        if (!v2 && !magic.contentEquals(MAGIC_V1)) throw IOException("not a vault file")
        val lockType = inp.readUnsignedByte()
        val p = Kdf.Params(inp.readInt(), inp.readInt(), inp.readInt())
        if (p.memoryKiB !in 8..(1024 * 1024) || p.iterations !in 1..64 || p.parallelism !in 1..16) throw IOException("vault damaged")
        val salt = ByteArray(16).also { inp.readFully(it) }
        val tag = if (v2) ByteArray(32).also { inp.readFully(it) } else null
        val n = inp.readInt()
        if (n < 0 || n > 4096) throw IOException("vault damaged")
        val outer = ByteArray(n).also { inp.readFully(it) }
        return Stored(lockType, p, salt, tag, outer)
    }

    private fun seal(pinKey: ByteArray, dataKey: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return nonce + aes(Cipher.ENCRYPT_MODE, pinKey, nonce, dataKey)
    }

    private fun open(pinKey: ByteArray, inner: ByteArray): ByteArray? = try {
        aes(Cipher.DECRYPT_MODE, pinKey, inner.copyOfRange(0, 12), inner.copyOfRange(12, inner.size))
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun aes(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(AAD)
        return c.doFinal(data)
    }

    private fun tag(pinKey: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(pinKey, "HmacSHA256"))
            doFinal(DURESS_LABEL)
        }

    private fun randomTag() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    companion object {
        const val LOCK_PIN = 1
        const val LOCK_PASSPHRASE = 2
        private val MAGIC_V1 = "BCV1".toByteArray(Charsets.US_ASCII)
        private val MAGIC_V2 = "BCV2".toByteArray(Charsets.US_ASCII)
        private val AAD = "backchannel vault v1".toByteArray(Charsets.US_ASCII)
        private val DURESS_LABEL = "backchannel duress v1".toByteArray(Charsets.US_ASCII)
    }
}
