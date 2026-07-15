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
private val CALL_MAGIC = byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte())

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
    private val onSend: (HaLowUser, ByteArray, Int) -> Result<Unit>,
    private val onState: (VoiceCallState) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val sending = AtomicBoolean(false)
    private var state = VoiceCallState()
    private var sequence = 1
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

    fun transportDisconnected() {
        if (state.phase != VoiceCallPhase.IDLE) reset()
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
        val packetSequence = sequence++
        return onSend(
            peer,
            encodeVoiceCallPacket(VoiceCallPacket(type, state.callId, packetSequence, audio)),
            packetSequence,
        )
    }

    private fun startCapture() {
        if (!sending.compareAndSet(false, true)) return
        executor.execute {
            val minBuffer = AudioRecord.getMinBufferSize(CALL_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CALL_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, CALL_SAMPLES_PER_FRAME * 4))
            val pcm = ShortArray(CALL_SAMPLES_PER_FRAME)
            val voiceDetector = VoiceActivityDetector()
            var preRollFrame: ShortArray? = null
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
                        val decision = voiceDetector.analyze(pcm)
                        if (decision.speechStarted) {
                            preRollFrame?.let { frame -> sendAudioFrame(frame) }
                            preRollFrame = null
                        }
                        if (decision.shouldSend) {
                            sendAudioFrame(pcm)
                        } else {
                            // Retain only the most recent silent frame so speech
                            // resumes with up to 40 ms of pre-roll, not a backlog.
                            preRollFrame = pcm.copyOf()
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

    private fun sendAudioFrame(pcm: ShortArray) {
        send(CALL_AUDIO, encodeImaAdpcm(pcm)).onFailure {
            Log.w(TAG_VOICE_CALL, "TX audio failed", it)
        }
    }

    private fun play(audio: ByteArray) {
        val track = player ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(CALL_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(CALL_SAMPLES_PER_FRAME * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play(); player = it }
        val pcm = decodeImaAdpcm(audio, CALL_SAMPLES_PER_FRAME) ?: run {
            Log.w(TAG_VOICE_CALL, "RX ADPCM frame malformed bytes=${audio.size}")
            return
        }
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
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

private val IMA_INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
private val IMA_STEP_TABLE = intArrayOf(
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
    34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
    157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
    2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
    9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
    29794, 32767,
)

/** Encodes one independently decodable 40 ms block: predictor, index, reserved, then 4-bit samples. */
private fun encodeImaAdpcm(samples: ShortArray): ByteArray {
    if (samples.isEmpty()) return ByteArray(0)
    var predictor = samples[0].toInt()
    val probeCount = minOf(samples.size - 1, 16)
    val averageDelta = if (probeCount > 0) {
        (1..probeCount).sumOf { kotlin.math.abs(samples[it].toInt() - samples[it - 1].toInt()) } / probeCount
    } else {
        0
    }
    var stepIndex = IMA_STEP_TABLE.indexOfFirst { it >= averageDelta }.let { if (it < 0) IMA_STEP_TABLE.lastIndex else it }
    val output = ByteArray(4 + (samples.size - 1 + 1) / 2)
    output[0] = predictor.toByte()
    output[1] = (predictor shr 8).toByte()
    output[2] = stepIndex.toByte()
    output[3] = 0

    for (sampleIndex in 1 until samples.size) {
        val step = IMA_STEP_TABLE[stepIndex]
        var difference = samples[sampleIndex].toInt() - predictor
        var code = 0
        if (difference < 0) {
            code = 8
            difference = -difference
        }
        var delta = step shr 3
        if (difference >= step) { code = code or 4; difference -= step; delta += step }
        if (difference >= step shr 1) { code = code or 2; difference -= step shr 1; delta += step shr 1 }
        if (difference >= step shr 2) { code = code or 1; delta += step shr 2 }
        predictor = (predictor + if (code and 8 != 0) -delta else delta).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        stepIndex = (stepIndex + IMA_INDEX_TABLE[code]).coerceIn(0, IMA_STEP_TABLE.lastIndex)

        val nibbleIndex = sampleIndex - 1
        val outputIndex = 4 + nibbleIndex / 2
        output[outputIndex] = if (nibbleIndex and 1 == 0) {
            code.toByte()
        } else {
            (output[outputIndex].toInt() or (code shl 4)).toByte()
        }
    }
    return output
}

private fun decodeImaAdpcm(bytes: ByteArray, sampleCount: Int): ShortArray? {
    if (sampleCount <= 0 || bytes.size < 4 + (sampleCount - 1 + 1) / 2) return null
    var predictor = ((bytes[0].toInt() and 0xff) or (bytes[1].toInt() shl 8)).toShort().toInt()
    var stepIndex = bytes[2].toInt() and 0xff
    if (stepIndex > IMA_STEP_TABLE.lastIndex) return null
    val output = ShortArray(sampleCount)
    output[0] = predictor.toShort()

    for (sampleIndex in 1 until sampleCount) {
        val nibbleIndex = sampleIndex - 1
        val packed = bytes[4 + nibbleIndex / 2].toInt() and 0xff
        val code = if (nibbleIndex and 1 == 0) packed and 0x0f else packed shr 4
        val step = IMA_STEP_TABLE[stepIndex]
        var delta = step shr 3
        if (code and 4 != 0) delta += step
        if (code and 2 != 0) delta += step shr 1
        if (code and 1 != 0) delta += step shr 2
        predictor = (predictor + if (code and 8 != 0) -delta else delta).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        stepIndex = (stepIndex + IMA_INDEX_TABLE[code]).coerceIn(0, IMA_STEP_TABLE.lastIndex)
        output[sampleIndex] = predictor.toShort()
    }
    return output
}
