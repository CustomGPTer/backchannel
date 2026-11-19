package uk.jj.backchannel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Strength
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val rating = Strength.ratePassphrase(pass)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        val chosen = pass.toCharArray()
        scope.launch {
            note = withContext(Dispatchers.IO) {
                try {
                    val out = context.contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("could not open that location")
                    Runtime.exportBackup(out, chosen)
                    true to "Backup saved. It can only be opened with the passphrase you just chose. There is no way to recover a forgotten one."
                } catch (e: Exception) {
                    false to "The backup could not be saved: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    chosen.fill('\u0000')
                }
            }
            busy = false
            if (note?.first == true) { pass = ""; again = "" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Save a backup file")
            Text(
                "With no server, your messages, contacts and keys exist only on this phone. A backup puts all of them, photos and voice notes included, into one encrypted file that you keep wherever you like.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Choose a passphrase for the file", fontWeight = FontWeight.SemiBold)
            Text(
                "This is separate from your app PIN. Anyone who gets hold of the file can keep guessing for as long as they like, so it has to be strong: four or five unrelated words. Lose it and the file is useless.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretField(pass, { pass = it; note = null }, isPin = false, label = "Backup passphrase", enabled = !busy, imeAction = ImeAction.Next)
            if (pass.isNotEmpty()) StrengthMeter(rating)
            SecretField(again, { again = it; note = null }, isPin = false, label = "Passphrase again", enabled = !busy)
            Busy(busy, "Writing the backup…") {
                Button(
                    enabled = rating.level >= Strength.Level.GOOD && again.isNotEmpty(),
                    onClick = {
                        if (pass != again) {
                            note = false to "The two passphrases don't match."
                        } else {
                            Runtime.leavingForPicker()
                            val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
                            try {
                                save.launch("backchannel-$day.bcbackup")
                            } catch (e: Exception) {
                                note = false to "No file manager was found on this phone."
                            }
                        }
                    },
                ) { Text("Choose where to save it") }
            }
            if (pass.isNotEmpty() && rating.level < Strength.Level.GOOD) {
                Text("The passphrase needs to be rated Good or better.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Note(note)

            HorizontalDivider()
            Heading("Things to know")
            Bullet("A backup is a snapshot. Anything sent or received after it was made is not in it, so make a fresh one before changing phones.")
            Bullet("To restore: install Backchannel on the new phone and, on the first screen, tap Restore from a backup file.")
            Bullet("Use a backup on one phone only, and remove Backchannel from the old phone. Two phones running as the same person will break each other's messages.")
            Bullet("After restoring, your phone sets up fresh keys with each contact by itself the next time you are both online. Until then messages to and from them wait.")
            Bullet("Your app PIN, fingerprint setting and duress PIN are not in the file. You choose a PIN again on the new phone.")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
