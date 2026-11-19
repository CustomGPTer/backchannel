package uk.jj.backchannel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Config
import uk.jj.backchannel.Fingerprint
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.vault.Attempts
import uk.jj.backchannel.vault.Vault

@Composable
fun UnlockScreen() {
    val context = LocalContext.current
    val attempts = remember { Attempts(context.applicationContext) }
    val isPin = remember { Runtime.vault().lockType() == Vault.LOCK_PIN }
    val phase by Runtime.phase.collectAsState()
    var secret by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var waitMs by remember { mutableLongStateOf(attempts.waitMs()) }
    val scope = rememberCoroutineScope()

    // A fingerprint only opens the screens while the app is already running. After a restart
    // the key is not in memory, and only the PIN or passphrase can bring it back.
    val canUseFingerprint = phase == Runtime.Phase.OPEN && remember { Config.fingerprint(context) && Fingerprint.available(context) }
    fun askFingerprint() {
        Fingerprint.ask(
            context, title = "Unlock Backchannel", cancelLabel = if (isPin) "Use PIN" else "Use passphrase",
            onSuccess = { Runtime.unlockScreensWithFingerprint() },
            onCancelled = { why -> if (why != null) error = why },
        )
    }
    LaunchedEffect(canUseFingerprint) {
        if (canUseFingerprint) askFingerprint()
    }

    // Count down any wait after wrong attempts.
    LaunchedEffect(Unit) {
        while (true) {
            waitMs = attempts.waitMs()
            delay(1000)
        }
    }

    fun submit() {
        if (working || secret.isEmpty() || waitMs > 0) return
        working = true
        error = null
        val typed = secret.toCharArray()
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    when (Runtime.unlock(typed)) {
                        Runtime.Unlocked.YES, Runtime.Unlocked.WIPED -> {
                            attempts.recordSuccess()
                            null
                        }
                        Runtime.Unlocked.WRONG -> {
                            attempts.recordFailure()
                            "Wrong ${if (isPin) "PIN" else "passphrase"}."
                        }
                    }
                } catch (e: Exception) {
                    "Could not unlock: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    typed.fill('\u0000')
                }
            }
            working = false
            secret = ""
            error = result
            waitMs = attempts.waitMs()
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Backchannel", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (phase == Runtime.Phase.SEALED) {
                "Locked. Nothing can be read or received until you unlock."
            } else {
                "Screen locked. Messages are still arriving."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        SecretField(
            secret, { secret = it; error = null }, isPin,
            if (isPin) "PIN" else "Passphrase",
            enabled = !working && waitMs == 0L, isError = error != null, onDone = { submit() },
        )
        when {
            waitMs > 0 -> Text(
                "Too many wrong tries. Wait ${formatWait(waitMs)} before trying again.",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
            )
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (working) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            Button(onClick = { submit() }, enabled = secret.isNotEmpty() && waitMs == 0L, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock")
            }
            if (canUseFingerprint) {
                OutlinedButton(onClick = { askFingerprint() }, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint") }
            }
        }
    }
}

private fun formatWait(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s >= 60) "${(s + 59) / 60} min" else "$s sec"
}
