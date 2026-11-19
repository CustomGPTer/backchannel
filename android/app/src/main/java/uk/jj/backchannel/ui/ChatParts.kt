package uk.jj.backchannel.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Attachment
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Message
import uk.jj.backchannel.media.Photos
import uk.jj.backchannel.media.VoicePlayer
import uk.jj.backchannel.media.VoiceRecorder

// ------------------------------------------------------------------ the bar you type in

@Composable
fun ChatInput(
    enabled: Boolean,
    busy: Boolean,
    onSendText: (String) -> Unit,
    onSendVoice: (VoiceRecorder.Clip) -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onProblem: (String) -> Unit,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    fun finish(send: Boolean) {
        if (!recording) return
        recording = false
        if (!send) {
            recorder.cancel()
            return
        }
        val clip = recorder.finish()
        if (clip == null) onProblem("That recording was too short.") else onSendVoice(clip)
    }

    fun startRecording() {
        try {
            VoicePlayer.stop()
            recorder.start(onLimit = { finish(send = true) })
            elapsed = 0
            recording = true
        } catch (e: Exception) {
            onProblem("The microphone could not be started.")
        }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else onProblem("Backchannel needs the microphone permission to record a voice note.")
    }

    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    // Leaving the app throws the recording away rather than leaving the microphone on.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { finish(send = false) }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs()
            delay(200)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                TextButton(onClick = { finish(send = false) }) { Text("Cancel") }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(8.dp))
                    Text("Recording ${clock(elapsed)}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "  of ${clock(VoiceRecorder.MAX_MS.toLong())}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(onClick = { finish(send = true) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note")
                }
            } else {
                Box {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 3.dp)
                    } else {
                        IconButton(onClick = { attachMenu = true }, enabled = enabled) {
                            Icon(Icons.Filled.Add, contentDescription = "Attach a photo")
                        }
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo from gallery") }, onClick = { attachMenu = false; onPickPhoto() })
                        DropdownMenuItem(text = { Text("Take a photo") }, onClick = { attachMenu = false; onTakePhoto() })
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Content.MAX_TEXT * 4) },
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (draft.isBlank()) {
                    FilledIconButton(
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else {
                                askMic.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = enabled,
                    ) { Icon(Glyphs.Mic, contentDescription = "Record a voice note") }
                } else {
                    FilledIconButton(
                        onClick = {
                            val text = draft
                            draft = ""
                            onSendText(text)
                        },
                        enabled = enabled,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ one line of the chat

@Composable
fun MessageRow(m: Message, contactName: String, onOpenPhoto: () -> Unit) {
    if (m.kind == Message.KIND_NOTE) {
        val seconds = m.body.toIntOrNull() ?: 0
        val who = if (m.outgoing) "You" else contactName.ifBlank { "They" }
        val text = if (seconds > 0) {
            "$who set messages to disappear after ${timerLabel(seconds)}"
        } else {
            "$who turned off disappearing messages"
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp),
        )
        return
    }
    val context = LocalContext.current
    val mine = m.outgoing
    val onBubble = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            when (m.kind) {
                Message.KIND_PHOTO -> {
                    PhotoThumb(m.id, onOpenPhoto)
                    if (m.body.isNotBlank()) {
                        SelectionContainer { Text(m.body, color = onBubble, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                Message.KIND_VOICE -> VoiceRow(m.id, onBubble)
                else -> SelectionContainer { Text(m.body, color = onBubble) }
            }
            val status = when {
                !mine -> ""
                m.status == Message.QUEUED -> " · Waiting"
                m.status == Message.DELIVERED -> " · Delivered"
                m.status == Message.FAILED -> " · Not delivered"
                else -> ""
            }
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.expireS > 0) {
                    Icon(
                        Glyphs.Timer, contentDescription = "Disappears after ${timerLabel(m.expireS)}",
                        tint = onBubble.copy(alpha = 0.7f), modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    shortTime(context, m.sentAt) + status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.status == Message.FAILED) MaterialTheme.colorScheme.error else onBubble.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun PhotoThumb(messageId: Long, onOpen: () -> Unit) {
    val key = "thumb:$messageId"
    val image by produceState(initialValue = Pictures.get(key), messageId) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                Runtime.storage?.attachmentData(messageId)?.let { Photos.thumbnail(it, 720)?.asImageBitmap() }
            }?.also { Pictures.put(key, it) }
        }
    }
    val img = image
    if (img == null) {
        Box(
            Modifier.width(220.dp).height(165.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Photo, contentDescription = "Photo", tint = Color.White.copy(alpha = 0.6f)) }
    } else {
        Image(
            bitmap = img,
            contentDescription = "Photo. Tap to view.",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(240.dp)
                .heightIn(max = 320.dp)
                .aspectRatio((img.width.toFloat() / img.height).coerceIn(0.6f, 2.2f))
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpen),
        )
    }
}

@Composable
private fun VoiceRow(messageId: Long, tint: Color) {
    val scope = rememberCoroutineScope()
    val playing by VoicePlayer.playing.collectAsState()
    val isPlaying = playing == messageId
    val info by produceState<Attachment?>(initialValue = null, messageId) {
        value = withContext(Dispatchers.IO) { Runtime.storage?.attachmentInfo(messageId) }
    }
    var position by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            position = VoicePlayer.positionMs()
            delay(150)
        }
        position = 0
    }
    val length = info?.durationMs ?: 0
    Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (isPlaying) {
                    VoicePlayer.stop()
                } else {
                    scope.launch {
                        val audio = withContext(Dispatchers.IO) { Runtime.storage?.attachmentData(messageId) }
                        if (audio != null) VoicePlayer.toggle(messageId, audio)
                    }
                }
            },
        ) {
            Icon(
                if (isPlaying) Glyphs.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice note" else "Play voice note", tint = tint,
            )
        }
        LinearProgressIndicator(
            progress = { if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f },
            color = tint, trackColor = tint.copy(alpha = 0.25f),
            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            clock(if (isPlaying) position.toLong() else length.toLong()),
            style = MaterialTheme.typography.labelMedium, color = tint,
        )
    }
}

// ------------------------------------------------------------------ photos, full size

@Composable
fun PhotoPreviewDialog(photo: Photos.Prepared, onCancel: () -> Unit, onSend: (String) -> Unit) {
    Dialog(onDismissRequest = onCancel) { PhotoPreview(photo, onCancel, onSend) }
}

/** The inside of the "send this photo?" pop-up, kept separate so it can be drawn in tests. */
@Composable
fun PhotoPreview(photo: Photos.Prepared, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    val image = remember(photo) { Photos.thumbnail(photo.jpeg, 1200)?.asImageBitmap() }
    run {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Send this photo?", style = MaterialTheme.typography.titleLarge)
                if (image != null) {
                    Image(
                        bitmap = image, contentDescription = "The photo to send", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    "Shrunk to ${photo.width} × ${photo.height} (${photo.jpeg.size / 1024} KB). Location, camera details and all other hidden information have been removed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it.take(Content.MAX_TEXT) },
                    label = { Text("Caption (optional)") }, maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSend(caption) }) { Text("Send") }
                }
            }
        }
    }
}

/** Shows one photo filling the screen. Pinch to zoom, drag to move, double-tap to reset. */
@Composable
fun PhotoViewer(messageId: Long, onClose: () -> Unit) {
    val image by produceState<ImageBitmap?>(initialValue = null, messageId) {
        value = withContext(Dispatchers.IO) {
            Runtime.storage?.attachmentData(messageId)?.let { Photos.thumbnail(it, 2048)?.asImageBitmap() }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val gestures = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val img = image
            if (img != null) {
                Image(
                    bitmap = img, contentDescription = "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            })
                        }
                        .transformable(gestures)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
