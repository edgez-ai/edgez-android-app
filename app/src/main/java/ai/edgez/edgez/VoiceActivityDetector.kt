package ai.edgez.edgez

import kotlin.math.max
import kotlin.math.sqrt

internal data class VoiceActivityDecision(
    val shouldSend: Boolean,
    val speechStarted: Boolean,
)

/**
 * Lightweight adaptive energy detector for realtime call PCM.
 *
 * A short hangover keeps quiet consonants and word endings, while the adaptive
 * noise floor avoids continuously transmitting stable background noise.
 */
internal class VoiceActivityDetector(
    private val minimumSpeechRms: Double = 40.0,
    private val noiseMultiplier: Double = 1.8,
    private val hangoverFrames: Int = 5,
    private val calibrationFrames: Int = 10,
) {
    private var noiseRms = minimumSpeechRms / noiseMultiplier
    private var remainingHangoverFrames = 0
    private var framesObserved = 0
    private val calibrationRms = DoubleArray(calibrationFrames.coerceAtLeast(0))
    private val calibrationSeedFrames = minOf(3, calibrationFrames)

    fun analyze(samples: ShortArray): VoiceActivityDecision {
        if (samples.isEmpty()) return VoiceActivityDecision(false, false)

        val rms = calculateRms(samples)
        if (framesObserved < calibrationFrames) {
            if (framesObserved >= calibrationSeedFrames) {
                val calibrationThreshold = max(minimumSpeechRms, noiseRms * noiseMultiplier)
                if (rms >= calibrationThreshold) {
                    val speechStarted = remainingHangoverFrames == 0
                    remainingHangoverFrames = hangoverFrames
                    return VoiceActivityDecision(true, speechStarted)
                }
                if (remainingHangoverFrames > 0) {
                    remainingHangoverFrames--
                    return VoiceActivityDecision(true, false)
                }
            }

            calibrationRms[framesObserved++] = rms
            // The median is resistant to one short sound or one unusually
            // quiet frame during the three-frame startup seed.
            val observed = calibrationRms.copyOf(framesObserved).sortedArray()
            noiseRms = observed[framesObserved / 2].coerceAtLeast(1.0)
            return VoiceActivityDecision(false, false)
        }

        val speechThreshold = max(minimumSpeechRms, noiseRms * noiseMultiplier)
        if (rms >= speechThreshold) {
            val speechStarted = remainingHangoverFrames == 0
            remainingHangoverFrames = hangoverFrames
            return VoiceActivityDecision(true, speechStarted)
        }

        if (remainingHangoverFrames > 0) {
            remainingHangoverFrames--
            return VoiceActivityDecision(true, false)
        }

        // Learn only from frames already classified as silence. A slow update
        // follows changing room noise without allowing speech to raise the gate.
        noiseRms = noiseRms * 0.95 + rms * 0.05
        return VoiceActivityDecision(false, false)
    }

    private fun calculateRms(samples: ShortArray): Double {
        // Remove the microphone's DC offset before measuring energy. Several
        // handsets expose a stable non-zero bias which is not audible speech.
        var sum = 0.0
        for (sample in samples) sum += sample
        val mean = sum / samples.size
        var sumSquares = 0.0
        for (sample in samples) {
            val value = sample - mean
            sumSquares += value * value
        }
        return sqrt(sumSquares / samples.size)
    }
}
