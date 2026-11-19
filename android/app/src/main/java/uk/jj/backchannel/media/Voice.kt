package uk.jj.backchannel.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Records a voice note with the phone's microphone. */
class VoiceRecorder(private val context: Context) {
    class Clip(val audio: ByteArray, val durationMs: Int)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording: Boolean get() = recorder != null

    fun elapsedMs(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** @param onLimit called if the recording reaches the maximum length and stops itself. */
    fun start(onLimit: () -> Unit) {
        cancel()
        val f = File(Scratch.dir(context, "voice"), "rec-${System.nanoTime()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit()
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            Scratch.wipe(f)
            throw e
        }
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops and returns the recording, or null if it was too short to be anything. */
    fun finish(): Clip? {
        val r = recorder ?: return null
        val f = file
        val length = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(MAX_MS.toLong()).toInt()
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stopped before any sound was captured
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        return try {
            if (!ok || f == null || length < MIN_MS) return null
            val bytes = f.readBytes()
            if (bytes.isEmpty()) null else Clip(bytes, length)
        } catch (_: Exception) {
            null
        } finally {
            // The recording only ever exists outside the encrypted database for these few moments.
            Scratch.wipe(f)
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: RuntimeException) {}
        try { r.release() } catch (_: Exception) {}
        Scratch.wipe(file)
        file = null
    }

    companion object {
        const val MAX_MS = 180_000
        const val MIN_MS = 700
    }
}

/**
 * Plays one voice note at a time, straight from memory. The audio is never written to a file
 * outside the encrypted database.
 */
object VoicePlayer {
    /** The message being played, or null. */
    val playing = MutableStateFlow<Long?>(null)

    private var player: MediaPlayer? = null

    fun positionMs(): Int = try {
        player?.currentPosition ?: 0
    } catch (_: IllegalStateException) {
        0
    }

    /** Starts this voice note, or stops it if it is the one already playing. */
    fun toggle(messageId: Long, audio: ByteArray) {
        val wasPlaying = playing.value == messageId
        stop()
        if (wasPlaying) return
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(Memory(audio))
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.setOnPreparedListener {
                if (player === p) p.start()
            }
            player = p
            playing.value = messageId
            p.prepareAsync()
        } catch (_: Exception) {
            try { p.release() } catch (_: Exception) {}
            player = null
            playing.value = null
        }
    }

    fun stop() {
        val p = player
        player = null
        playing.value = null
        if (p != null) {
            try { p.stop() } catch (_: IllegalStateException) {}
            try { p.release() } catch (_: Exception) {}
        }
    }

    private class Memory(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }
}
