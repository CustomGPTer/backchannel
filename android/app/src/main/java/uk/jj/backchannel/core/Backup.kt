package uk.jj.backchannel.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file format. The file is useless without its passphrase: the passphrase is
 * turned into a key with Argon2id (set heavier than for the app's PIN, because a backup file
 * may sit somewhere an attacker can keep guessing at it for as long as they like), and the
 * contents are encrypted with AES-256-GCM in blocks.
 *
 * Each block is checked as it is read, the blocks are numbered so none can be dropped or
 * swapped, and the last block is marked so a cut-short file is noticed.
 */
object Backup {
    private val MAGIC = "BCBACKUP1\n".toByteArray(Charsets.US_ASCII)
    private const val BLOCK = 64 * 1024
    val KDF = Kdf.Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    class Writer(out: OutputStream, passphrase: CharArray, params: Kdf.Params = KDF) : OutputStream() {
        private val out = DataOutputStream(out)
        private val key: SecretKeySpec
        private val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        private val buf = ByteArray(BLOCK)
        private var used = 0
        private var counter = 0
        private var closed = false

        init {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            this.out.write(MAGIC)
            this.out.writeInt(params.memoryKiB)
            this.out.writeInt(params.iterations)
            this.out.writeInt(params.parallelism)
            this.out.write(salt)
            this.out.write(prefix)
            key = SecretKeySpec(Kdf.derive(passphrase, salt, params), "AES")
        }

        override fun write(b: Int) {
            if (used == BLOCK) flushBlock(last = false)
            buf[used++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                if (used == BLOCK) flushBlock(last = false)
                val n = minOf(left, BLOCK - used)
                System.arraycopy(b, at, buf, used, n)
                used += n
                at += n
                left -= n
            }
        }

        private fun flushBlock(last: Boolean) {
            val sealed = crypt(Cipher.ENCRYPT_MODE, key, prefix, counter++, last, buf, used)
            out.writeInt(sealed.size)
            out.write(sealed)
            used = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            flushBlock(last = true)
            buf.fill(0)
            out.close()
        }
    }

    class Reader(inp: InputStream, passphrase: CharArray) : InputStream() {
        private val inp = DataInputStream(inp)
        private val key: SecretKeySpec
        private val prefix = ByteArray(8)
        private var block = ByteArray(0)
        private var at = 0
        private var counter = 0
        private var sawLast = false

        init {
            val magic = ByteArray(MAGIC.size)
            try {
                this.inp.readFully(magic)
            } catch (_: EOFException) {
                throw IOException("That is not a Backchannel backup file.")
            }
            if (!magic.contentEquals(MAGIC)) throw IOException("That is not a Backchannel backup file.")
            val p = Kdf.Params(this.inp.readInt(), this.inp.readInt(), this.inp.readInt())
            if (p.memoryKiB !in 8..(512 * 1024) || p.iterations !in 1..32 || p.parallelism !in 1..8) throw IOException("The backup file is damaged.")
            val salt = ByteArray(16).also { this.inp.readFully(it) }
            this.inp.readFully(prefix)
            key = SecretKeySpec(Kdf.derive(passphrase, salt, p), "AES")
            // Read the first block now, so a wrong passphrase is reported before anything else is done.
            if (!next()) throw IOException("The backup file is empty.")
        }

        private fun next(): Boolean {
            if (sawLast) return false
            val n = try {
                inp.readInt()
            } catch (_: EOFException) {
                throw IOException("The backup file is cut short.")
            }
            if (n < 16 || n > BLOCK + 16) throw IOException("The backup file is damaged.")
            val sealed = ByteArray(n)
            try {
                inp.readFully(sealed)
            } catch (_: EOFException) {
                throw IOException("The backup file is cut short.")
            }
            // A block opens either as "more to come" or as "the last one", never both.
            val plain = tryOpen(sealed, last = false) ?: tryOpen(sealed, last = true)?.also { sawLast = true }
            if (plain == null) {
                if (counter == 0) throw WrongSecretException("Wrong passphrase, or the backup file is damaged.")
                throw IOException("The backup file is damaged.")
            }
            counter++
            block = plain
            at = 0
            return true
        }

        private fun tryOpen(sealed: ByteArray, last: Boolean): ByteArray? = try {
            crypt(Cipher.DECRYPT_MODE, key, prefix, counter, last, sealed, sealed.size)
        } catch (_: GeneralSecurityException) {
            null
        }

        override fun read(): Int {
            while (at == block.size) if (!next()) return -1
            return block[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == block.size) if (!next()) return -1
            val n = minOf(len, block.size - at)
            System.arraycopy(block, at, b, off, n)
            at += n
            return n
        }

        override fun close() = inp.close()
    }

    private fun crypt(mode: Int, key: SecretKeySpec, prefix: ByteArray, counter: Int, last: Boolean, data: ByteArray, len: Int): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, 8)
        nonce[8] = (counter ushr 24).toByte()
        nonce[9] = (counter ushr 16).toByte()
        nonce[10] = (counter ushr 8).toByte()
        nonce[11] = counter.toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, nonce))
        c.updateAAD(byteArrayOf(if (last) 1 else 0))
        return c.doFinal(data, 0, len)
    }
}

/** Thrown for a wrong PIN, passphrase or backup passphrase. */
class WrongSecretException(message: String? = null) : Exception(message)
