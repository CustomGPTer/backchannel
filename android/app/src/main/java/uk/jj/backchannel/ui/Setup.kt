package uk.jj.backchannel.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Strength
import uk.jj.backchannel.core.WrongSecretException
import uk.jj.backchannel.vault.Vault

@Composable
fun SetupFlow() {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var isPin by remember { mutableStateOf(true) }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Restoring from a backup uses the same lock steps, with one extra step for the file's passphrase.
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    var backupPass by remember { mutableStateOf("") }
    val chooseBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreFrom = uri
            backupPass = ""
            error = null
            step = STEP_BACKUP_PASS
        }
    }

    fun back() {
        step = when {
            step == STEP_BACKUP_PASS -> { restoreFrom = null; 0 }
            step == 2 && restoreFrom != null -> STEP_BACKUP_PASS
            else -> step - 1
        }
        confirm = ""
        error = null
    }
    BackHandler(enabled = step > 0 && !working) { back() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (step) {
            0 -> {
                Spacer(Modifier.height(24.dp))
                Text("Backchannel", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text("Private messages, phone to phone.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Point("No server", "Messages go straight from your phone to theirs over the Tor network. Nothing is stored anywhere in between.")
                Point("Encrypted end to end", "Every message is locked with the Signal Protocol before it leaves your phone. Only the person you sent it to can open it.")
                Point("Locked on this phone", "Everything the app keeps is encrypted and opens only with your PIN or passphrase.")
                Point("Both phones must be online", "With no server, a message waits on your phone until the other person's phone is connected, then sends itself.")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
                TextButton(
                    onClick = {
                        try { chooseBackup.launch(arrayOf("*/*")) } catch (e: Exception) { error = "No file manager was found on this phone." }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from a backup file") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            STEP_BACKUP_PASS -> {
                Header("Backup passphrase", "Restore · step 1 of 3")
                Text(
                    "Type the passphrase you chose when you saved the backup file. This is the file's own passphrase, not your app PIN.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecretField(backupPass, { backupPass = it; error = null }, isPin = false, label = "Backup passphrase", isError = error != null, onDone = { if (backupPass.isNotEmpty()) step = 2 })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { error = null; step = 2 }, enabled = backupPass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Next") }
                Text(
                    "Next you choose a PIN or passphrase for this phone. Then remove Backchannel from the old phone: a backup must only ever be running on one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { back() }) { Text("Back") }
            }

            1 -> {
                Header("Your name", "Step 1 of 3")
                Text(
                    "This is what people see when you add each other. It is sent only to people you add, encrypted, and never to anyone else. You can leave it blank.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Content.MAX_NAME) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Next") }
            }

            2 -> {
                Header("Choose your lock", if (restoreFrom != null) "Restore · step 2 of 3" else "Step 2 of 3")
                Text(
                    "This opens the app and is part of the key that encrypts everything in it. Pick the kind of lock you want. The meter shows how strong your choice is as you type.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isPin, onClick = { isPin = true; secret = "" }, label = { Text("PIN") })
                    FilterChip(selected = !isPin, onClick = { isPin = false; secret = "" }, label = { Text("Passphrase") })
                }
                Text(
                    if (isPin) {
                        "PIN: digits only, 6 or more. Quickest to type. 6 digits is rated Fair, 7 or 8 Good, 9 or more Strong."
                    } else {
                        "Passphrase: letters, numbers, anything. Slower to type but far harder to guess. Four or five unrelated words is rated Very strong."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rating = if (isPin) Strength.ratePin(secret) else Strength.ratePassphrase(secret)
                val acceptable = rating.level >= Strength.Level.FAIR
                SecretField(secret, { secret = it }, isPin, if (isPin) "PIN" else "Passphrase", onDone = { if (acceptable) step = 3 })
                if (secret.isNotEmpty()) StrengthMeter(rating)
                Button(onClick = { confirm = ""; error = null; step = 3 }, enabled = acceptable, modifier = Modifier.fillMaxWidth()) { Text("Next") }
            }

            else -> {
                Header("Type it again", if (restoreFrom != null) "Restore · step 3 of 3" else "Step 3 of 3")
                Text(
                    "There is no reset and no recovery. If you forget this ${if (isPin) "PIN" else "passphrase"}, your messages and contacts cannot be opened by anyone, including you.",
                    color = MaterialTheme.colorScheme.error,
                )
                fun finish() {
                    if (working) return
                    if (confirm != secret) {
                        error = "That doesn't match. Try again, or go back and choose a new one."
                        return
                    }
                    working = true
                    error = null
                    val backup = restoreFrom
                    scope.launch {
                        var wrongBackupPass = false
                        val failure = withContext(Dispatchers.Default) {
                            val lockType = if (isPin) Vault.LOCK_PIN else Vault.LOCK_PASSPHRASE
                            try {
                                if (backup == null) {
                                    Runtime.setUp(secret.toCharArray(), lockType, name.trim())
                                } else {
                                    val file = context.contentResolver.openInputStream(backup) ?: throw java.io.IOException("That file could not be opened.")
                                    Runtime.restore(file, backupPass.toCharArray(), secret.toCharArray(), lockType)
                                }
                                null
                            } catch (e: WrongSecretException) {
                                wrongBackupPass = true
                                e.message ?: "Wrong backup passphrase."
                            } catch (e: Exception) {
                                e.message ?: e.javaClass.simpleName
                            }
                        }
                        working = false
                        if (wrongBackupPass) {
                            // Nothing on this phone was changed. Go back and let them try the passphrase again.
                            error = failure
                            confirm = ""
                            step = STEP_BACKUP_PASS
                        } else if (failure != null) {
                            error = if (backup == null) "Setup failed: $failure" else "The backup could not be restored. $failure"
                        }
                    }
                }
                SecretField(confirm, { confirm = it; error = null }, isPin, if (isPin) "PIN again" else "Passphrase again", enabled = !working, isError = error != null, onDone = { finish() })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(if (restoreFrom != null) "Restoring your backup…" else "Creating your keys…")
                    }
                } else {
                    Button(onClick = { finish() }, enabled = confirm.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                    TextButton(onClick = { back() }) { Text("Back") }
                }
            }
        }
    }
}

private const val STEP_BACKUP_PASS = 10

@Composable
private fun Header(title: String, step: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text(step, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
