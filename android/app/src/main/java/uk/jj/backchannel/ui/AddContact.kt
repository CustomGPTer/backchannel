package uk.jj.backchannel.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.jj.backchannel.Runtime
import uk.jj.backchannel.core.Content
import uk.jj.backchannel.core.Invite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(onBack: () -> Unit, onAdded: (Long) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add contact", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My code") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Add someone") })
            }
            if (tab == 0) MyCode() else AddSomeone(onAdded)
        }
    }
}

@Composable
private fun MyCode() {
    val context = LocalContext.current
    val changes by Runtime.changes.collectAsState()
    var generation by remember { mutableIntStateOf(0) }
    // The code can only be made once Tor has given this phone its address.
    val code by produceState<String?>(initialValue = null, changes, generation) {
        value = withContext(Dispatchers.IO) {
            val node = Runtime.node
            if (node?.selfOnion == null) null else node.invite(fresh = false).encode()
        }
    }
    val qr = remember(code) { code?.let { qrBitmap(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val text = code
        if (text == null || qr == null) {
            CircularProgressIndicator(Modifier.padding(top = 48.dp).size(32.dp), strokeWidth = 3.dp)
            Text("Getting your address ready… this takes a few seconds the first time.", textAlign = TextAlign.Center)
            return@Column
        }
        Text(
            "Have the other person scan this in Backchannel (Add contact, then Add someone).",
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
        ) {
            Image(bitmap = qr.asImageBitmap(), contentDescription = "Your Backchannel code", modifier = Modifier.fillMaxSize())
        }
        Text(
            "Can't meet? Send the invite as text instead. Scanning in person is safer, because nobody else can see the code.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { shareInvite(context, text) }) { Text("Send invite") }
            OutlinedButton(onClick = { copySensitive(context, inviteMessage(text)) }) { Text("Copy") }
        }
        TextButton(
            onClick = {
                Runtime.node?.let { n ->
                    if (n.selfOnion != null) n.invite(fresh = true)
                    generation++
                }
            },
        ) { Text("Make a new code") }
        Text(
            "One code adds one person and stops working once it is used, or after 7 days. Make a new code for each person.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddSomeone(onAdded: (Long) -> Unit) {
    val context = LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Invite?>(null) }

    fun take(text: String?) {
        val invite = text?.let { Invite.parse(it) }
        when {
            invite == null -> error = "That isn't a Backchannel invite."
            invite.onion == Runtime.node?.selfOnion -> error = "That is your own code."
            else -> {
                error = null
                found = invite
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) take(result.contents)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("In person", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Ask them to open Add contact and show My code, then scan it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                Runtime.leavingForPicker()
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Point the camera at their Backchannel code")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan their code") }

        Text("From an invite they sent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text("Copy the whole message they sent you, then paste it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it.take(4000); error = null },
            label = { Text("Invite") },
            minLines = 3, maxLines = 5,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (clip.isNullOrBlank()) error = "Nothing copied to paste." else { pasted = clip.take(4000); error = null }
                },
            ) { Text("Paste") }
            Button(onClick = { take(pasted) }, enabled = pasted.isNotBlank()) { Text("Add") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    found?.let { invite ->
        var name by remember(invite) { mutableStateOf("") }
        var picked by remember(invite) { mutableStateOf<PickedContact?>(null) }
        val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            if (uri != null) {
                val p = readPickedContact(context, uri)
                if (p != null) {
                    picked = p
                    if (p.name.isNotBlank()) name = p.name.take(Content.MAX_NAME)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Name this contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Leave it blank to use the name they chose for themselves.")
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(Content.MAX_NAME) }, singleLine = true,
                        label = { Text("Name (optional)") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    TextButton(onClick = {
                        Runtime.leavingForPicker()
                        try { pickContact.launch(null) } catch (e: Exception) { error = "No address book app was found." }
                    }) { Text("Choose from address book") }
                    Text(
                        when {
                            picked?.photo != null -> "Their name and picture will be copied from your address book."
                            picked != null -> "Their name will be copied from your address book."
                            else -> "Copies the name and picture of one entry you choose. Backchannel never reads the rest of your address book."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val node = Runtime.node
                        val p = picked
                        found = null
                        if (node != null) {
                            try {
                                val id = node.addFromInvite(invite, name)
                                if (p != null) node.linkContact(id, name, p.photo)
                                onAdded(id)
                            } catch (e: IllegalArgumentException) {
                                error = e.message
                            }
                        }
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }
}

private fun inviteMessage(code: String): String =
    "Add me on Backchannel. Copy this whole message, then in the app tap Add contact, Add someone, Paste.\n\n$code"

private fun shareInvite(context: Context, code: String) {
    Runtime.leavingForPicker()
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteMessage(code))
    context.startActivity(Intent.createChooser(send, "Send invite").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copies text and asks Android not to show it in the clipboard preview. */
private fun copySensitive(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Backchannel invite", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
