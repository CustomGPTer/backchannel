package uk.jj.backchannel.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.math.ln
import kotlin.math.pow

/** Turns a PIN or passphrase into an encryption key, slowly on purpose (Argon2id). */
object Kdf {
    class Params(val memoryKiB: Int, val iterations: Int, val parallelism: Int)

    val DEFAULT = Params(memoryKiB = 32 * 1024, iterations = 3, parallelism = 1)

    fun derive(secret: CharArray, salt: ByteArray, p: Params): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(p.memoryKiB)
            .withIterations(p.iterations)
            .withParallelism(p.parallelism)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(secret, out)
        return out
    }
}

/** A plain-English rating of how hard a PIN or passphrase is to guess. */
object Strength {
    enum class Level(val label: String) {
        TOO_SHORT("Too short"),
        WEAK("Weak"),
        FAIR("Fair"),
        GOOD("Good"),
        STRONG("Strong"),
        VERY_STRONG("Very strong"),
    }

    class Rating(val level: Level, val bits: Double, val note: String) {
        /** 0.0 to 1.0, for a meter. */
        val fraction: Float get() = when (level) {
            Level.TOO_SHORT -> 0.05f
            Level.WEAK -> 0.2f
            Level.FAIR -> 0.4f
            Level.GOOD -> 0.6f
            Level.STRONG -> 0.8f
            Level.VERY_STRONG -> 1f
        }
    }

    const val MIN_PIN = 6
    const val MIN_PASSPHRASE = 8

    private val COMMON_PINS = setOf(
        "123456", "654321", "111111", "000000", "121212", "112233", "123123", "666666",
        "222222", "333333", "444444", "555555", "777777", "888888", "999999", "123321",
        "159753", "147258", "789456", "101010", "12345678", "87654321", "11111111", "00000000",
        "1234567", "123456789", "1234567890", "0123456789",
    )

    fun ratePin(pin: String): Rating {
        if (pin.length < MIN_PIN) return Rating(Level.TOO_SHORT, 0.0, "Use at least $MIN_PIN digits.")
        if (pin in COMMON_PINS || pin.toSet().size <= 2 || isRun(pin)) {
            return Rating(Level.WEAK, 8.0, "This is one of the first PINs anyone would try. Pick something less obvious.")
        }
        if (looksLikeDate(pin)) {
            return Rating(Level.WEAK, 15.0, "This looks like a date. Birthdays are among the first things people try.")
        }
        val bits = pin.length * log2(10.0)
        val combos = 10.0.pow(pin.length)
        return when {
            pin.length >= 12 -> Rating(Level.VERY_STRONG, bits, "${describe(combos)} possible PINs.")
            pin.length >= 9 -> Rating(Level.STRONG, bits, "${describe(combos)} possible PINs.")
            pin.length >= 7 -> Rating(Level.GOOD, bits, "${describe(combos)} possible PINs.")
            else -> Rating(Level.FAIR, bits, "1 million possible PINs. Fine against someone who picks up your phone, because the app slows down wrong guesses.")
        }
    }

    fun ratePassphrase(p: String): Rating {
        if (p.length < MIN_PASSPHRASE) {
            return Rating(Level.TOO_SHORT, 0.0, "Use at least $MIN_PASSPHRASE characters.")
        }
        if (p.toSet().size <= 3 || p.lowercase() in setOf("password", "passw0rd", "password1", "qwertyuiop", "letmein123", "iloveyou1")) {
            return Rating(Level.WEAK, 10.0, "Too easy to guess. Use several unrelated words.")
        }
        var pool = 0
        if (p.any { it in 'a'..'z' }) pool += 26
        if (p.any { it in 'A'..'Z' }) pool += 26
        if (p.any { it.isDigit() }) pool += 10
        if (p.any { !it.isLetterOrDigit() }) pool += 20
        // People don't choose characters at random, so count each one for about half.
        val bits = p.length * log2(pool.coerceAtLeast(10).toDouble()) * 0.5
        return when {
            bits >= 60 -> Rating(Level.VERY_STRONG, bits, "Out of reach of guessing, even with the phone taken apart.")
            bits >= 45 -> Rating(Level.STRONG, bits, "Very hard to guess.")
            bits >= 32 -> Rating(Level.GOOD, bits, "Hard to guess. Longer is stronger: four or five unrelated words is ideal.")
            else -> Rating(Level.FAIR, bits, "Better than a short PIN. Longer is stronger.")
        }
    }

    private fun isRun(s: String): Boolean {
        if (!s.all { it.isDigit() }) return false
        val up = s.zipWithNext().all { (a, b) -> (b - a + 10) % 10 == 1 }
        val down = s.zipWithNext().all { (a, b) -> (a - b + 10) % 10 == 1 }
        return up || down
    }

    /** Birthdays are the first thing people try: ddmmyy, mmddyy, ddmmyyyy. */
    private fun looksLikeDate(s: String): Boolean {
        fun dm(d: Int, m: Int) = d in 1..31 && m in 1..12
        if (s.length == 6) {
            val a = s.substring(0, 2).toInt(); val b = s.substring(2, 4).toInt()
            return dm(a, b) || dm(b, a)
        }
        if (s.length == 8) {
            val a = s.substring(0, 2).toInt(); val b = s.substring(2, 4).toInt(); val y = s.substring(4).toInt()
            return (dm(a, b) || dm(b, a)) && y in 1930..2030
        }
        return false
    }

    private fun describe(n: Double): String = when {
        n >= 1e12 -> "${(n / 1e12).toLong()} trillion"
        n >= 1e9 -> "${(n / 1e9).toLong()} billion"
        n >= 1e6 -> "${(n / 1e6).toLong()} million"
        else -> n.toLong().toString()
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
