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
    private val minimumSpeechRms: Double = 400.0,
    private val noiseMultiplier: Double = 2.5,
    private val hangoverFrames: Int = 5,
) {
    private var noiseRms = minimumSpeechRms / noiseMultiplier
    private var remainingHangoverFrames = 0

    fun analyze(samples: ShortArray): VoiceActivityDecision {
        if (samples.isEmpty()) return VoiceActivityDecision(false, false)

        val rms = calculateRms(samples)
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
        var sumSquares = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / samples.size)
    }
}
