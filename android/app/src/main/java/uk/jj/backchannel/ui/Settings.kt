package uk.jj.backchannel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.BuildConfig
import uk.jj.backchannel.Config
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Updates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLock: () -> Unit = {}, onBackup: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by Prefs.theme.collectAsState()
    val tor by Runtime.tor.collectAsState()
    val update by Runtime.update.collectAsState()
    var name by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf(batteryUnrestricted(context)) }
    var autoCheck by remember { mutableStateOf(Config.autoUpdateCheck(context)) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) { Runtime.node?.profileName ?: "" }
        name = n
        savedName = n
    }
    LifecycleResumeEffect(Unit) {
        battery = batteryUnrestricted(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Security")
            LinkRow("Lock", "PIN or passphrase, fingerprint, auto-lock, duress PIN", onLock)
            LinkRow("Backup", "Save everything to one encrypted file", onBackup)

            HorizontalDivider()
            Heading("Your name")
            Text("Shown to people you add from now on. It is only ever sent encrypted, to your contacts.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(Content.MAX_NAME) }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    Runtime.node?.profileName = name
                    savedName = name
                },
                enabled = name != savedName,
            ) { Text("Save name") }

            HorizontalDivider()
            Heading("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = theme == Prefs.THEME_DARK, onClick = { Prefs.setTheme(context, Prefs.THEME_DARK) }, label = { Text("Dark") })
                FilterChip(selected = theme == Prefs.THEME_LIGHT, onClick = { Prefs.setTheme(context, Prefs.THEME_LIGHT) }, label = { Text("Light") })
                FilterChip(selected = theme == Prefs.THEME_SYSTEM, onClick = { Prefs.setTheme(context, Prefs.THEME_SYSTEM) }, label = { Text("Same as phone") })
            }

            HorizontalDivider()
            Heading("Running in the background")
            Text(
                if (battery) {
                    "Allowed. Android will leave Backchannel running so messages can arrive."
                } else {
                    "Not allowed yet. Android may put the app to sleep, and messages will not arrive until you open it."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            if (!battery) Button(onClick = { askBatteryUnrestricted(context) }) { Text("Allow") }
            Text(
                "After the phone restarts, open Backchannel and unlock it once. Until then nothing can be received, because the key is not in memory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Heading("Connection")
            TorStatusLine(tor)
            tor.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Heading("Updates")
            Text("You have version ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodyMedium)
            val status = when (val u = update) {
                Runtime.Update.Unknown -> null
                Runtime.Update.Checking -> "Checking over Tor…"
                Runtime.Update.UpToDate -> "This is the newest version."
                Runtime.Update.NothingPublished -> "No version has been published yet."
                is Runtime.Update.Available -> "Version ${u.version} is available."
                is Runtime.Update.Failed -> u.reason
            }
            if (status != null) {
                Text(status, fontWeight = if (update is Runtime.Update.Available) FontWeight.SemiBold else FontWeight.Normal)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = update != Runtime.Update.Checking,
                    onClick = { scope.launch(Dispatchers.IO) { Runtime.checkForUpdate() } },
                ) { Text("Check now") }
                if (update is Runtime.Update.Available) {
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.DOWNLOAD_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                        }
                    }) { Text("Open download page") }
                }
            }
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Backchannel download page", Updates.DOWNLOAD_PAGE))
                copied = true
            }) { Text(if (copied) "Link copied" else "Copy download page link") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check once a day by itself", modifier = Modifier.weight(1f))
                Switch(checked = autoCheck, onCheckedChange = { Config.setAutoUpdateCheck(context, it); autoCheck = it })
            }
            Text(
                "The check goes through Tor, so GitHub does not see where you are. Backchannel only tells you a new version exists. Android will refuse to install any update that is not signed with the same key as this one, which is what stops a fake update.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Heading("About")
            Text("Backchannel ${BuildConfig.VERSION_NAME} (test build, stage 3)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Messages are encrypted with the Signal Protocol (libsignal) and travel over Tor. Stored data is encrypted with SQLCipher. Source code is licensed AGPL-3.0.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
