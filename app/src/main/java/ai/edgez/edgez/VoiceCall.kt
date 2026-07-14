package ai.edgez.edgez

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val CALL_SAMPLE_RATE = 8_000
private const val TAG_VOICE_CALL = "EdgeZVoiceCall"
private const val CALL_FRAME_MS = 40
private const val CALL_SAMPLES_PER_FRAME = CALL_SAMPLE_RATE * CALL_FRAME_MS / 1_000
private val CALL_MAGIC = byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

private const val CALL_INVITE: Byte = 1
private const val CALL_ACCEPT: Byte = 2
private const val CALL_END: Byte = 3
private const val CALL_AUDIO: Byte = 4

data class VoiceCallPacket(val type: Byte, val callId: Long, val sequence: Int, val audio: ByteArray = ByteArray(0))

data class VoiceCallState(
    val peer: HaLowUser? = null,
    val callId: Long = 0,
    val phase: VoiceCallPhase = VoiceCallPhase.IDLE,
)

enum class VoiceCallPhase { IDLE, OUTGOING, INCOMING, ACTIVE }

fun encodeVoiceCallPacket(packet: VoiceCallPacket): ByteArray = ByteBuffer
    .allocate(CALL_MAGIC.size + 1 + 8 + 4 + packet.audio.size)
    .order(ByteOrder.LITTLE_ENDIAN)
    .put(CALL_MAGIC)
    .put(packet.type)
    .putLong(packet.callId)
    .putInt(packet.sequence)
    .put(packet.audio)
    .array()

fun decodeVoiceCallPacket(payload: ByteArray): VoiceCallPacket? {
    if (payload.size < CALL_MAGIC.size + 13 || !payload.copyOfRange(0, CALL_MAGIC.size).contentEquals(CALL_MAGIC)) return null
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
    buffer.position(CALL_MAGIC.size)
    return VoiceCallPacket(buffer.get(), buffer.long, buffer.int, ByteArray(buffer.remaining()).also(buffer::get))
}

class VoiceCallSession(
    private val onSend: (HaLowUser, ByteArray) -> Result<Unit>,
    private val onState: (VoiceCallState) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private var state = VoiceCallState()
    private var sequence = 0
    private var player: AudioTrack? = null

    fun start(peer: HaLowUser): Result<Unit> {
        if (state.phase != VoiceCallPhase.IDLE) return Result.failure(IllegalStateException("A call is already active"))
        val callId = System.nanoTime()
        state = VoiceCallState(peer, callId, VoiceCallPhase.OUTGOING)
        publishState()
        return send(CALL_INVITE).onFailure { reset() }
    }

    fun accept(): Result<Unit> {
        if (state.phase != VoiceCallPhase.INCOMING) return Result.failure(IllegalStateException("No incoming call"))
        return send(CALL_ACCEPT).onSuccess {
            state = state.copy(phase = VoiceCallPhase.ACTIVE)
            publishState()
            startCapture()
        }
    }

    fun end() {
        if (state.phase != VoiceCallPhase.IDLE) send(CALL_END)
        reset()
    }

    fun receive(peer: HaLowUser, packet: VoiceCallPacket) {
        when (packet.type) {
            CALL_INVITE -> if (state.phase == VoiceCallPhase.IDLE) {
                state = VoiceCallState(peer, packet.callId, VoiceCallPhase.INCOMING)
                publishState()
            }
            CALL_ACCEPT -> if (state.phase == VoiceCallPhase.OUTGOING && packet.callId == state.callId) {
                state = state.copy(phase = VoiceCallPhase.ACTIVE)
                publishState()
                startCapture()
            }
            CALL_END -> if (packet.callId == state.callId) reset()
            CALL_AUDIO -> if (state.phase == VoiceCallPhase.ACTIVE && packet.callId == state.callId) {
                Log.d(TAG_VOICE_CALL, "RX audio seq=${packet.sequence} bytes=${packet.audio.size}")
                play(packet.audio)
            }
        }
    }

    private fun send(type: Byte, audio: ByteArray = ByteArray(0)): Result<Unit> {
        val peer = state.peer ?: return Result.failure(IllegalStateException("Call peer unavailable"))
        return onSend(peer, encodeVoiceCallPacket(VoiceCallPacket(type, state.callId, sequence++, audio)))
    }

    private fun startCapture() {
        if (!sending.compareAndSet(false, true)) return
        executor.execute {
            val minBuffer = AudioRecord.getMinBufferSize(CALL_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CALL_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CALL_SAMPLES_PER_FRAME * 4))
            val pcm = ShortArray(CALL_SAMPLES_PER_FRAME)
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG_VOICE_CALL, "AudioRecord initialization failed minBuffer=$minBuffer")
                    reset()
                    return@execute
                }
                recorder.startRecording()
                Log.i(TAG_VOICE_CALL, "Microphone capture started")
                while (sending.get()) {
                    var offset = 0
                    while (offset < pcm.size && sending.get()) {
                        val read = recorder.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset == pcm.size && state.phase == VoiceCallPhase.ACTIVE) {
                        send(CALL_AUDIO, pcmToMuLaw(pcm)).onFailure {
                            Log.w(TAG_VOICE_CALL, "TX audio failed", it)
                        }
                    } else if (offset <= 0) {
                        Log.w(TAG_VOICE_CALL, "AudioRecord read failed: $offset state=${recorder.recordingState}")
                    }
                }
            } finally {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            }
        }
    }

    private fun play(audio: ByteArray) {
        val track = player ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(CALL_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(CALL_SAMPLES_PER_FRAME * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play(); player = it }
        track.write(muLawToPcm(audio), 0, audio.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun reset() {
        sending.set(false)
        player?.run { pause(); flush(); release() }
        player = null
        state = VoiceCallState()
        publishState()
    }

    private fun publishState() = onState(state)
}

private fun pcmToMuLaw(samples: ShortArray): ByteArray = ByteArray(samples.size) { index ->
    encodeMuLaw(samples[index])
}

private fun encodeMuLaw(value: Short): Byte {
    val sample = value.toInt()
    val sign = if (sample < 0) 0x80 else 0
    var magnitude = if (sample < 0) -sample else sample
    magnitude = (magnitude + 132).coerceAtMost(32635)
    var exponent = 7
    var mask = 0x4000
    while (exponent > 0 && magnitude and mask == 0) { exponent--; mask = mask shr 1 }
    return (sign or (exponent shl 4) or ((magnitude shr (exponent + 3)) and 0x0f)).inv().toByte()
}

private fun muLawToPcm(bytes: ByteArray): ShortArray = ShortArray(bytes.size) { index ->
    val value = bytes[index].toInt().inv() and 0xff
    val magnitude = (((value and 0x0f) shl 3) + 132) shl ((value shr 4) and 7)
    (if (value and 0x80 != 0) 132 - magnitude else magnitude - 132).toShort()
}
