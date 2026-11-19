package uk.jj.backchannel.vault

import android.content.Context

/**
 * Counts wrong PIN attempts and makes each further guess wait longer. The count has to be
 * readable before the vault is open, so it is kept outside the encrypted database.
 */
class Attempts(context: Context) {
    private val prefs = context.getSharedPreferences("attempts", Context.MODE_PRIVATE)

    /** Milliseconds until another try is allowed; 0 if one is allowed now. */
    fun waitMs(now: Long = System.currentTimeMillis()): Long {
        val until = prefs.getLong(UNTIL, 0)
        val set = prefs.getLong(SET_AT, 0)
        // If the clock has been wound back, restart the wait rather than skip it.
        if (now < set) {
            val fails = prefs.getInt(FAILS, 0)
            prefs.edit().putLong(SET_AT, now).putLong(UNTIL, now + delayFor(fails)).apply()
            return delayFor(fails)
        }
        return (until - now).coerceAtLeast(0)
    }

    fun fails(): Int = prefs.getInt(FAILS, 0)

    fun recordFailure(now: Long = System.currentTimeMillis()) {
        val fails = prefs.getInt(FAILS, 0) + 1
        prefs.edit().putInt(FAILS, fails).putLong(SET_AT, now).putLong(UNTIL, now + delayFor(fails)).commit()
    }

    fun recordSuccess() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FAILS = "fails"
        private const val UNTIL = "until"
        private const val SET_AT = "set_at"
        const val FREE_TRIES = 4

        fun delayFor(fails: Int): Long = when {
            fails <= FREE_TRIES -> 0
            fails == 5 -> 30_000
            fails == 6 -> 60_000
            fails == 7 -> 5 * 60_000
            fails == 8 -> 15 * 60_000
            else -> 60 * 60_000
        }
    }
}
