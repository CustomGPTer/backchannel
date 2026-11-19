package uk.jj.backchannel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import uk.jj.backchannel.core.Strength
import uk.jj.backchannel.net.TorStatus
import android.content.Context
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** A field for a PIN (number pad) or a passphrase (full keyboard). Never shows what is typed. */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    isPin: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(if (isPin) v.filter { it.isDigit() }.take(32) else v.take(128)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun StrengthMeter(rating: Strength.Rating) {
    val colour = when (rating.level) {
        Strength.Level.TOO_SHORT, Strength.Level.WEAK -> MaterialTheme.colorScheme.error
        Strength.Level.FAIR -> Color(0xFFE0A030)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { rating.fraction },
            color = colour,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(6.dp))
        Text("Strength: ${rating.level.label}", style = MaterialTheme.typography.labelLarge, color = colour)
        Text(rating.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TorStatusLine(status: TorStatus, modifier: Modifier = Modifier) {
    val (text, colour) = when (status.phase) {
        TorStatus.Phase.ONLINE ->
            if (status.reachable) "Online" to Color(0xFF34D399)
            else "Online · announcing your address…" to Color(0xFFE0A030)
        TorStatus.Phase.CONNECTING -> "Connecting to Tor… ${status.percent}%" to Color(0xFFE0A030)
        TorStatus.Phase.STARTING, TorStatus.Phase.OFF -> "Starting…" to Color(0xFFE0A030)
        TorStatus.Phase.NO_INTERNET -> "No internet connection" to MaterialTheme.colorScheme.error
        TorStatus.Phase.FAILED -> "Tor could not start" to MaterialTheme.colorScheme.error
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "14:05" today, "Mon 14:05" this week, otherwise the date. */
fun shortTime(context: Context, ms: Long): String {
    if (ms <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    // Follows the phone's own 12 or 24 hour setting.
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return time
    val days = (now.timeInMillis - ms) / 86_400_000L
    return if (days < 6) {
        "${android.text.format.DateFormat.format("EEE", then)} $time"
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}

/** The choices offered for a chat's disappearing-message timer, in seconds. */
val TIMER_CHOICES = listOf(0, 5 * 60, 60 * 60, 8 * 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60, 28 * 24 * 60 * 60)

fun timerLabel(seconds: Int): String {
    fun n(count: Int, unit: String) = if (count == 1) "1 $unit" else "$count ${unit}s"
    return when {
        seconds <= 0 -> "Off"
        seconds % (7 * 86_400) == 0 -> n(seconds / (7 * 86_400), "week")
        seconds % 86_400 == 0 -> n(seconds / 86_400, "day")
        seconds % 3_600 == 0 -> n(seconds / 3_600, "hour")
        seconds % 60 == 0 -> n(seconds / 60, "minute")
        else -> n(seconds, "second")
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** Decoded pictures kept ready for the screen. Emptied whenever the screens lock. */
object Pictures {
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(40)

    fun get(key: String): androidx.compose.ui.graphics.ImageBitmap? = cache.get(key)

    fun put(key: String, image: androidx.compose.ui.graphics.ImageBitmap) {
        cache.put(key, image)
    }

    fun clear() = cache.evictAll()
}

/** A contact's picture from the address book if there is one, otherwise their initial. */
@Composable
fun Avatar(name: String, photo: ByteArray?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val image = androidx.compose.runtime.remember(photo) {
        photo?.let { bytes ->
            try {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
