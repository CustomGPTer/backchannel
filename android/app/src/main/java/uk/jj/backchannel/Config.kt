package uk.jj.backchannel

import android.content.Context

/**
 * The handful of settings that have to be readable while the app is still locked. None of
 * them is sensitive: they say how the lock behaves, not what it protects.
 */
object Config {
    private const val FILE = "config"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** How long the app may be out of sight before the screens lock. 0 = straight away. */
    fun lockAfterMs(context: Context): Long = prefs(context).getLong("lock_after_ms", 60_000)

    fun setLockAfterMs(context: Context, ms: Long) = prefs(context).edit().putLong("lock_after_ms", ms).apply()

    /** Whether a fingerprint may open the screens while the app is running. */
    fun fingerprint(context: Context): Boolean = prefs(context).getBoolean("fingerprint", false)

    fun setFingerprint(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fingerprint", on).apply()

    fun autoUpdateCheck(context: Context): Boolean = prefs(context).getBoolean("auto_update", true)

    fun setAutoUpdateCheck(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto_update", on).apply()

    fun lastUpdateCheck(context: Context): Long = prefs(context).getLong("last_update_check", 0)

    fun setLastUpdateCheck(context: Context, at: Long) = prefs(context).edit().putLong("last_update_check", at).apply()

    fun clear(context: Context) {
        for (name in listOf(FILE, "ui", "attempts")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
