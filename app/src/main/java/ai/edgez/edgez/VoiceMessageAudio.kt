package ai.edgez.edgez

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

const val VOICE_CODEC_AMR_NB = 1
const val VOICE_CODEC_OPUS = 2

class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var codec: Int = VOICE_CODEC_AMR_NB
    private var startedAtMs: Long = 0

    fun start(): Result<Unit> = runCatching {
        stop(delete = true)
        val useOpus = Build.VERSION.SDK_INT >= 29
        val nextCodec = if (useOpus) VOICE_CODEC_OPUS else VOICE_CODEC_AMR_NB
        val file = newVoiceFile(context, nextCodec)
        val nextRecorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        nextRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        if (useOpus) {
            nextRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            nextRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            nextRecorder.setAudioEncodingBitRate(12_000)
            nextRecorder.setAudioSamplingRate(16_000)
        } else {
            nextRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            nextRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            nextRecorder.setAudioEncodingBitRate(4750)
            nextRecorder.setAudioSamplingRate(8000)
        }
        nextRecorder.setOutputFile(file.absolutePath)
        nextRecorder.prepare()
        nextRecorder.start()
        recorder = nextRecorder
        outputFile = file
        codec = nextCodec
        startedAtMs = System.currentTimeMillis()
    }

    fun stop(delete: Boolean = false): VoiceRecording? {
        val file = outputFile
        val duration = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val activeRecorder = recorder
        recorder = null
        outputFile = null
        startedAtMs = 0
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        if (delete) {
            file?.delete()
            return null
        }
        return file?.takeIf { it.exists() && it.length() > 0 }?.let {
            VoiceRecording(
                path = it.absolutePath,
                bytes = it.readBytes(),
                durationMs = duration,
                codec = codec,
            )
        }
    }
}

data class VoiceRecording(
    val path: String,
    val bytes: ByteArray,
    val durationMs: Long,
    val codec: Int,
)

object VoiceMessagePlayer {
    private var player: MediaPlayer? = null

    fun play(path: String): Result<Unit> = runCatching {
        stop()
        val nextPlayer = MediaPlayer()
        nextPlayer.setDataSource(path)
        nextPlayer.setOnCompletionListener {
            stop()
        }
        nextPlayer.prepare()
        nextPlayer.start()
        player = nextPlayer
    }

    fun stop() {
        player?.release()
        player = null
    }
}

fun saveVoiceMessage(context: Context, bytes: ByteArray, codec: Int): String {
    val file = newVoiceFile(context, codec)
    file.writeBytes(bytes)
    return file.absolutePath
}

fun voiceCodecFromPath(path: String): Int {
    return if (path.endsWith(".ogg", ignoreCase = true)) {
        VOICE_CODEC_OPUS
    } else {
        VOICE_CODEC_AMR_NB
    }
}

private fun newVoiceFile(context: Context, codec: Int): File {
    val dir = File(context.filesDir, "voice_messages")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val extension = if (codec == VOICE_CODEC_OPUS) "ogg" else "3gp"
    return File(dir, "voice_${System.currentTimeMillis()}_${(0..9999).random()}.$extension")
}
