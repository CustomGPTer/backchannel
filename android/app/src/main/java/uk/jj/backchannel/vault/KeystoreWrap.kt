package uk.jj.backchannel.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * An AES key kept in the Android Keystore. Where the phone has a dedicated security chip
 * (StrongBox, as on recent Galaxy S phones) the key is made inside it; otherwise it lives
 * in the processor's secure area. Either way the app can use the key but never read it.
 */
class KeystoreWrap(private val alias: String = "backchannel.vault.v1") : KeyWrap {

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existing(): SecretKey? = keystore().getKey(alias, null) as? SecretKey

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    /** Uses the hardware key if there is one; otherwise makes it, in the security chip where the phone has one. */
    override fun wrap(plain: ByteArray): ByteArray {
        val key = existing() ?: try {
            generate(strongBox = true)
        } catch (_: ProviderException) {
            generate(strongBox = false)
        } catch (_: GeneralSecurityException) {
            generate(strongBox = false)
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(iv.size)
            write(iv)
            write(ct)
        }.toByteArray()
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val key = existing() ?: throw GeneralSecurityException("hardware key missing")
        val ivLen = blob[0].toInt()
        if (ivLen !in 12..16 || blob.size < 1 + ivLen + 16) throw GeneralSecurityException("bad blob")
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 1, ivLen))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    override fun destroy() {
        try {
            keystore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
