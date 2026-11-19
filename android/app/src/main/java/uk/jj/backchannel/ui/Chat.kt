package uk.jj.backchannel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Contact
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.media.Photos
import uk.jj.backchannel.media.Scratch
import uk.jj.backchannel.media.VoicePlayer
import java.io.File

private class ChatData(val contact: Contact?, val messages: List<Message>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(contactId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by Runtime.changes.collectAsState()
    val data by produceState<ChatData?>(initialValue = null, changes, contactId) {
        value = withContext(Dispatchers.IO) {
            val s = Runtime.storage
            if (s == null) ChatData(null, emptyList()) else ChatData(s.contactById(contactId), s.messagesFor(contactId, 500))
        }
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var choosingTimer by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<Photos.Prepared?>(null) }
    var viewing by remember { mutableStateOf<Long?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()

    // While this chat is on screen its messages count as read and raise no notification.
    DisposableEffect(contactId) {
        Runtime.openChat = contactId
        onDispose {
            if (Runtime.openChat == contactId) Runtime.openChat = null
            VoicePlayer.stop()
        }
    }
    LaunchedEffect(contactId, changes) {
        withContext(Dispatchers.IO) { Runtime.node?.markRead(contactId) }
    }
    val current = data
    LaunchedEffect(current?.messages?.lastOrNull()?.id) {
        if (current != null && current.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    if (current != null && current.contact == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val contact = current?.contact
    val messages = current?.messages.orEmpty()
    val waiting = messages.count { it.outgoing && it.status == Message.QUEUED }
    val usable = contact != null && contact.state != Contact.REFUSED

    // ---- attaching a photo: shrink it and strip its hidden details, then show it before sending
    fun usePhoto(uri: Uri, scratch: File?) {
        preparing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Photos.prepare(context, uri)
                } catch (e: Exception) {
                    null
                } finally {
                    Scratch.wipe(scratch)
                }
            }
            preparing = false
            if (result == null) problem = "That picture could not be opened." else pendingPhoto = result
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) usePhoto(uri, null)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = captureFile
        captureFile = null
        if (saved && f != null) {
            usePhoto(FileProvider.getUriForFile(context, context.packageName + ".files", f), f)
        } else {
            Scratch.wipe(f)
        }
    }
    fun openCamera() {
        try {
            val f = File(Scratch.dir(context, "capture"), "photo-${System.nanoTime()}.jpg")
            captureFile = f
            Runtime.leavingForPicker()
            takePhoto.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
        } catch (e: Exception) {
            captureFile = null
            problem = "The camera could not be opened."
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else problem = "Backchannel needs the camera permission to take a photo."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (contact != null) Avatar(contact.name, contact.photo, 36.dp, Modifier.padding(end = 10.dp))
                        Column {
                            Text(contact?.name ?: "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = when {
                                contact == null -> ""
                                contact.state == Contact.PENDING -> "Waiting to reach their phone…"
                                contact.state == Contact.REFUSED -> "Invite refused"
                                waiting > 0 -> "Waiting for them to come online"
                                contact.lastSeenAt > 0 -> "Last connected ${shortTime(context, contact.lastSeenAt)}"
                                else -> "Connected"
                            }
                            // The status shortens if it has to; the timer always stays visible.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    sub, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (contact != null && contact.timerS > 0) {
                                    Icon(
                                        Glyphs.Timer, contentDescription = "Disappearing messages on",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp, end = 3.dp).size(13.dp),
                                    )
                                    Text(
                                        timerLabel(contact.timerS), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Disappearing messages") }, enabled = usable, onClick = { menu = false; choosingTimer = true })
                        DropdownMenuItem(text = { Text("Name and picture") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete contact") }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        bottomBar = {
            ChatInput(
                enabled = usable,
                busy = preparing,
                onSendText = { Runtime.node?.sendText(contactId, it) },
                onSendVoice = { clip ->
                    try {
                        Runtime.node?.sendMedia(contactId, Content.MEDIA_VOICE, clip.audio, 0, 0, clip.durationMs)
                    } catch (e: IllegalArgumentException) {
                        problem = e.message
                    }
                },
                onPickPhoto = {
                    Runtime.leavingForPicker()
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onTakePhoto = {
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openCamera()
                    } else {
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                },
                onProblem = { problem = it },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (contact?.state) {
                Contact.PENDING -> Banner(
                    "Your phone is trying to reach theirs to swap keys. This happens by itself once both phones are online and connected to Tor. Anything you send now goes as soon as it does.",
                )
                Contact.REFUSED -> Banner(
                    "Their phone refused this invite. It may have been used already or be more than 7 days old. Ask them for a new code, then delete this contact and add them again.",
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m ->
                    MessageRow(m, contactName = contact?.name ?: "", onOpenPhoto = { viewing = m.id })
                }
            }
        }
    }

    pendingPhoto?.let { photo ->
        PhotoPreviewDialog(
            photo = photo,
            onCancel = { pendingPhoto = null },
            onSend = { caption ->
                pendingPhoto = null
                try {
                    Runtime.node?.sendMedia(contactId, Content.MEDIA_PHOTO, photo.jpeg, photo.width, photo.height, 0, caption)
                } catch (e: IllegalArgumentException) {
                    problem = e.message
                }
            },
        )
    }
    viewing?.let { id -> PhotoViewer(messageId = id, onClose = { viewing = null }) }

    problem?.let { text ->
        AlertDialog(
            onDismissRequest = { problem = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }

    if (choosingTimer && contact != null) {
        var chosen by remember { mutableStateOf(contact.timerS) }
        AlertDialog(
            onDismissRequest = { choosingTimer = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    Text(
                        "New messages in this chat delete themselves from both phones after the time you pick. Your copy starts counting when it is delivered; theirs when they have seen it. Either of you can change this, and you both see the change.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    val choices = if (contact.timerS in TIMER_CHOICES) TIMER_CHOICES else TIMER_CHOICES + contact.timerS
                    for (seconds in choices) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = chosen == seconds, role = Role.RadioButton, onClick = { chosen = seconds }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == seconds, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(timerLabel(seconds))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingTimer = false; Runtime.node?.setTimer(contactId, chosen) }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { choosingTimer = false }) { Text("Cancel") } },
        )
    }

    if (renaming && contact != null) {
        var name by remember { mutableStateOf(contact.name) }
        var picked by remember { mutableStateOf<PickedContact?>(null) }
        val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            if (uri != null) {
                val p = readPickedContact(context, uri)
                if (p == null) problem = "That address-book entry could not be read." else {
                    picked = p
                    if (p.name.isNotBlank()) name = p.name.take(Content.MAX_NAME)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name and picture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(Content.MAX_NAME) }, singleLine = true,
                        label = { Text("Name") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    TextButton(onClick = {
                        Runtime.leavingForPicker()
                        try { pickContact.launch(null) } catch (e: Exception) { problem = "No address book app was found." }
                    }) { Text("Choose from address book") }
                    Text(
                        when {
                            picked?.photo != null -> "Their name and picture will be copied from your address book."
                            picked != null -> "Their name will be copied. That entry has no picture, or the phone didn't share it."
                            else -> "Copies the name and picture of one entry you choose. Backchannel never reads the rest of your address book, and nothing is sent anywhere."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val p = picked
                        if (p != null) Runtime.node?.linkContact(contactId, name, p.photo) else Runtime.node?.renameContact(contactId, name)
                        renaming = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting && contact != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${contact.name}?") },
            text = { Text("This removes the contact, every message, photo and voice note with them, and the keys you share, from this phone only. It cannot be undone. To talk again you would have to add each other afresh.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; Runtime.node?.deleteContact(contactId); onBack() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
