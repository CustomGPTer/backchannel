package uk.jj.backchannel.core

import java.security.SecureRandom
import java.util.Base64

internal object Bytes {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/** A v3 onion address without the ".onion" ending: 56 characters, a-z and 2-7. */
internal val ONION_REGEX = Regex("^[a-z2-7]{56}$")

fun isOnion(s: String): Boolean = ONION_REGEX.matches(s)
