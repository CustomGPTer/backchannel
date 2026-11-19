package uk.jj.backchannel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Config
import uk.jj.backchannel.Fingerprint
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Strength
import uk.jj.backchannel.core.WrongSecretException
import uk.jj.backchannel.vault.Vault

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentIsPin = remember { Runtime.vault().lockType() == Vault.LOCK_PIN }
    val currentWord = if (currentIsPin) "PIN" else "passphrase"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lock", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------------- how strong is each kind
            Heading("How strong each lock is")
            Text(
                "You are using a $currentWord. Whichever you use is combined with a key held in this phone's security chip, and wrong guesses are slowed down.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StrengthRow("PIN, 6 digits", "Fair", 0.4f, "1 million possibilities. Fine against someone who picks up your phone.")
            StrengthRow("PIN, 7 or 8 digits", "Good", 0.6f, "10 to 100 million possibilities.")
            StrengthRow("PIN, 9 digits or more", "Strong", 0.8f, "A billion or more possibilities.")
            StrengthRow("Passphrase, four or five words", "Very strong", 1f, "Out of reach of guessing, even with the phone taken apart. Slowest to type.")
            StrengthRow(
                "Fingerprint as well", "Speed, not strength", 0f,
                "Opens the screens while the app is running. After a restart your $currentWord is always needed. Be aware that someone could make you touch the sensor.",
            )

            // ---------------------------------------------------------------- fingerprint
            HorizontalDivider()
            Heading("Fingerprint")
            val hasSensor = remember { Fingerprint.available(context) }
            var fingerprintOn by remember { mutableStateOf(Config.fingerprint(context)) }
            var fingerprintNote by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hasSensor) "Open the screens with a fingerprint" else "No fingerprint is set up on this phone. Add one in the phone's own settings first.",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = fingerprintOn && hasSensor,
                    enabled = hasSensor,
                    onCheckedChange = { wanted ->
                        fingerprintNote = null
                        if (!wanted) {
                            Config.setFingerprint(context, false)
                            fingerprintOn = false
                        } else {
                            // Turning it on needs a successful scan, so it can only be enabled by a finger the phone already knows.
                            Fingerprint.ask(
                                context, "Turn on fingerprint unlock", "Cancel",
                                onSuccess = { Config.setFingerprint(context, true); fingerprintOn = true },
                                onCancelled = { why -> fingerprintNote = why },
                            )
                        }
                    },
                )
            }
            fingerprintNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // ---------------------------------------------------------------- auto-lock
            HorizontalDivider()
            Heading("Lock the screens")
            Text("How long the app can be out of sight before it asks for your $currentWord again. Messages keep arriving while the screens are locked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var lockAfter by remember { mutableLongStateOf(Config.lockAfterMs(context)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, ms) in listOf("Straight away" to 0L, "1 minute" to 60_000L, "5 minutes" to 300_000L)) {
                    FilterChip(selected = lockAfter == ms, onClick = { Config.setLockAfterMs(context, ms); lockAfter = ms }, label = { Text(label) })
                }
            }
            OutlinedButton(onClick = { Runtime.lockScreens() }) { Text("Lock now") }

            // ---------------------------------------------------------------- change PIN / passphrase
            HorizontalDivider()
            Heading("Change your $currentWord")
            var old by remember { mutableStateOf("") }
            var newIsPin by remember { mutableStateOf(currentIsPin) }
            var new by remember { mutableStateOf("") }
            var again by remember { mutableStateOf("") }
            var changeBusy by remember { mutableStateOf(false) }
            var changeNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            SecretField(old, { old = it; changeNote = null }, currentIsPin, "Current $currentWord", enabled = !changeBusy, imeAction = ImeAction.Next)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = newIsPin, onClick = { newIsPin = true; new = ""; again = "" }, label = { Text("New PIN") })
                FilterChip(selected = !newIsPin, onClick = { newIsPin = false; new = ""; again = "" }, label = { Text("New passphrase") })
            }
            val rating = if (newIsPin) Strength.ratePin(new) else Strength.ratePassphrase(new)
            SecretField(new, { new = it; changeNote = null }, newIsPin, if (newIsPin) "New PIN" else "New passphrase", enabled = !changeBusy, imeAction = ImeAction.Next)
            if (new.isNotEmpty()) StrengthMeter(rating)
            SecretField(again, { again = it; changeNote = null }, newIsPin, "New one again", enabled = !changeBusy)
            Busy(changeBusy, "Changing…") {
                Button(
                    enabled = old.isNotEmpty() && rating.level >= Strength.Level.FAIR && again.isNotEmpty(),
                    onClick = {
                        if (new != again) {
                            changeNote = false to "The two new entries don't match."
                            return@Button
                        }
                        changeBusy = true
                        scope.launch {
                            changeNote = withContext(Dispatchers.Default) {
                                try {
                                    Runtime.changeSecret(old.toCharArray(), new.toCharArray(), if (newIsPin) Vault.LOCK_PIN else Vault.LOCK_PASSPHRASE)
                                    true to "Changed. Use the new one from now on. If you wrote the old one down, destroy it."
                                } catch (_: WrongSecretException) {
                                    false to "The current $currentWord is wrong."
                                } catch (e: IllegalArgumentException) {
                                    false to (e.message ?: "That can't be used.")
                                } catch (e: Exception) {
                                    false to "Could not change it: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                            changeBusy = false
                            if (changeNote?.first == true) { old = ""; new = ""; again = "" }
                        }
                    },
                ) { Text("Change") }
            }
            Note(changeNote)

            // ---------------------------------------------------------------- duress PIN
            HorizontalDivider()
            Heading("Duress PIN")
            Text(
                "A second ${if (currentIsPin) "PIN" else "passphrase"} for when you are made to unlock the app. Typing it at the lock screen wipes every message, contact and key in seconds, and the app opens as if newly installed. Nothing can be recovered afterwards, by anyone. There is no other way to trigger it, so it cannot go off by accident.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var duressSet by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { duressSet = withContext(Dispatchers.IO) { Runtime.hasDuress() } }
            Text(if (duressSet) "A duress ${if (currentIsPin) "PIN" else "passphrase"} is set." else "None set.", fontWeight = FontWeight.SemiBold)
            var dCurrent by remember { mutableStateOf("") }
            var dNew by remember { mutableStateOf("") }
            var dAgain by remember { mutableStateOf("") }
            var dBusy by remember { mutableStateOf(false) }
            var dNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
            SecretField(dCurrent, { dCurrent = it; dNote = null }, currentIsPin, "Your real $currentWord", enabled = !dBusy, imeAction = ImeAction.Next)
            SecretField(dNew, { dNew = it; dNote = null }, currentIsPin, "Duress ${if (currentIsPin) "PIN" else "passphrase"}", enabled = !dBusy, imeAction = ImeAction.Next)
            SecretField(dAgain, { dAgain = it; dNote = null }, currentIsPin, "Duress one again", enabled = !dBusy)
            fun saveDuress(remove: Boolean) {
                if (!remove) {
                    val minimum = if (currentIsPin) Strength.MIN_PIN else Strength.MIN_PASSPHRASE
                    if (dNew.length < minimum) { dNote = false to "Use at least $minimum ${if (currentIsPin) "digits" else "characters"}."; return }
                    if (dNew != dAgain) { dNote = false to "The two duress entries don't match."; return }
                }
                dBusy = true
                scope.launch {
                    dNote = withContext(Dispatchers.Default) {
                        try {
                            Runtime.setDuress(dCurrent.toCharArray(), if (remove) null else dNew.toCharArray())
                            true to if (remove) "Duress ${if (currentIsPin) "PIN" else "passphrase"} removed." else "Set. Remember: typing it at the lock screen wipes everything, at once, with no warning and no undo."
                        } catch (_: WrongSecretException) {
                            false to "Your real $currentWord is wrong."
                        } catch (e: IllegalArgumentException) {
                            false to (e.message ?: "That can't be used.")
                        } catch (e: Exception) {
                            false to "Could not save it: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    dBusy = false
                    if (dNote?.first == true) {
                        duressSet = !remove
                        dCurrent = ""; dNew = ""; dAgain = ""
                    }
                }
            }
            Busy(dBusy, "Saving…") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveDuress(remove = false) }, enabled = dCurrent.isNotEmpty() && dNew.isNotEmpty() && dAgain.isNotEmpty()) {
                        Text(if (duressSet) "Replace" else "Set duress ${if (currentIsPin) "PIN" else "passphrase"}")
                    }
                    if (duressSet) OutlinedButton(onClick = { saveDuress(remove = true) }, enabled = dCurrent.isNotEmpty()) { Text("Remove") }
                }
            }
            Note(dNote)
        }
    }
}

@Composable
internal fun Heading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun Note(note: Pair<Boolean, String>?) {
    if (note == null) return
    Text(note.second, color = if (note.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun Busy(busy: Boolean, label: String, content: @Composable () -> Unit) {
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Text(label)
        }
    } else {
        content()
    }
}

@Composable
private fun StrengthRow(title: String, rating: String, fraction: Float, note: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val colour = when {
                    fraction == 0f -> MaterialTheme.colorScheme.onSurfaceVariant
                    fraction <= 0.4f -> Color(0xFFE0A030)
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(rating, style = MaterialTheme.typography.labelLarge, color = colour)
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
