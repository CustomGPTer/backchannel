package uk.jj.backchannel.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Message

private class ChatRow(val contact: Contact, val last: Message?, val waiting: Int)

private fun loadRows(): List<ChatRow> {
    val s = Runtime.storage ?: return emptyList()
    return s.contacts().map { c ->
        ChatRow(c, s.lastWrittenFor(c.id), s.queuedFor(c.id).count { it.kind in Message.WRITTEN })
    }.sortedByDescending { maxOf(it.last?.sentAt ?: 0L, it.contact.createdAt) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenChat: (Long) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val changes by Runtime.changes.collectAsState()
    val tor by Runtime.tor.collectAsState()
    val update by Runtime.update.collectAsState()
    val rows by produceState<List<ChatRow>?>(initialValue = null, changes) {
        value = withContext(Dispatchers.IO) { loadRows() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Backchannel", fontWeight = FontWeight.SemiBold)
                        TorStatusLine(tor)
                    }
                },
                actions = {
                    IconButton(onClick = { Runtime.lockScreens() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock now") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add contact") },
            )
        },
    ) { pad ->
        val list = rows
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { BatteryCard() }
            (update as? Runtime.Update.Available)?.let { u ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onSettings),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Version ${u.version} is available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Tap to open Settings, then Updates.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            tor.error?.let { msg ->
                item { Notice(msg) }
            }
            if (list != null && list.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text("No contacts yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Tap Add contact to show your code or scan someone else's. Each of you needs Backchannel installed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            items(list.orEmpty(), key = { it.contact.id }) { row ->
                ChatListItem(row, onClick = { onOpenChat(row.contact.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun ChatListItem(row: ChatRow, onClick: () -> Unit) {
    val c = row.contact
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.name, c.photo, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val preview = when {
                c.state == Contact.PENDING -> "Waiting to reach their phone…"
                c.state == Contact.REFUSED -> "Invite was refused. Ask them for a new code."
                row.last == null -> "Connected. Say hello."
                else -> {
                    val what = when (row.last.kind) {
                        Message.KIND_PHOTO -> if (row.last.body.isBlank()) "Photo" else "Photo: ${row.last.body}"
                        Message.KIND_VOICE -> "Voice note"
                        else -> row.last.body
                    }
                    if (row.last.outgoing) "You: $what" else what
                }
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.state == Contact.REFUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                shortTime(LocalContext.current, row.last?.sentAt ?: 0L),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                c.unread > 0 -> Box(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (c.unread > 99) "99+" else c.unread.toString(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                row.waiting > 0 -> Text(
                    "${row.waiting} waiting",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

fun batteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun askBatteryUnrestricted(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/** Android will quietly stop the app in the background unless the user allows it to keep running. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    var ok by remember { mutableStateOf(batteryUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        ok = batteryUnrestricted(context)
        onPauseOrDispose { }
    }
    if (ok) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Let Backchannel run in the background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "There is no server holding your messages, so they can only arrive while this phone is connected. Android will put the app to sleep unless you allow it to keep running.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { askBatteryUnrestricted(context) }) { Text("Allow") }
        }
    }
}
