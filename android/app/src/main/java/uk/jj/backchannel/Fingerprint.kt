package uk.jj.backchannel

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Fingerprint (or face, where the phone rates it as strong) for opening the screens while
 * the app is already running. It never replaces the PIN: after a restart, or whenever the
 * app has been cleared from memory, the PIN or passphrase is the only way in, because it is
 * part of the key that decrypts the database.
 */
object Fingerprint {
    /** True if the phone has a strong biometric sensor with at least one print enrolled. */
    fun available(context: Context): Boolean {
        val bm = context.getSystemService(BiometricManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Shows Android's own fingerprint sheet. Exactly one of the two callbacks is called, on the main thread. */
    fun ask(context: Context, title: String, cancelLabel: String, onSuccess: () -> Unit, onCancelled: (String?) -> Unit) {
        try {
            val builder = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setNegativeButton(cancelLabel, context.mainExecutor) { _, _ -> onCancelled(null) }
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            builder.build().authenticate(
                CancellationSignal(), context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val quiet = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        onCancelled(if (quiet) null else errString.toString())
                    }
                },
            )
        } catch (e: Exception) {
            onCancelled("Fingerprint is not available on this phone.")
        }
    }
}
