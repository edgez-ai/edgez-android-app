package ai.edgez.edgez

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    @Test
    fun sustainedSilenceIsNotSent() {
        val detector = VoiceActivityDetector()

        repeat(20) {
            assertFalse(detector.analyze(ShortArray(320) { 100 }).shouldSend)
        }
    }

    @Test
    fun speechStartsImmediatelyAndKeepsShortHangover() {
        val detector = VoiceActivityDetector(hangoverFrames = 2, calibrationFrames = 0)

        val firstSpeech = detector.analyze(ShortArray(320) { 2_000 })
        assertTrue(firstSpeech.shouldSend)
        assertTrue(firstSpeech.speechStarted)
        assertTrue(detector.analyze(ShortArray(320)).shouldSend)
        assertTrue(detector.analyze(ShortArray(320)).shouldSend)
        assertFalse(detector.analyze(ShortArray(320)).shouldSend)
    }

    @Test
    fun stableLowBackgroundNoiseRemainsSuppressed() {
        val detector = VoiceActivityDetector()

        repeat(100) { frame ->
            val amplitude = 120 + frame % 20
            assertFalse(detector.analyze(ShortArray(320) { amplitude.toShort() }).shouldSend)
        }
    }

    @Test
    fun quietDeviceSpeechIsStillDetected() {
        val detector = VoiceActivityDetector()

        repeat(10) {
            assertFalse(detector.analyze(ShortArray(320) { 20 }).shouldSend)
        }
        val decision = detector.analyze(ShortArray(320) { 80 })
        assertTrue(decision.shouldSend)
        assertTrue(decision.speechStarted)
    }

    @Test
    fun louderPhoneCalibratesWithoutSendingItsNoiseFloor() {
        val detector = VoiceActivityDetector()

        repeat(10) {
            assertFalse(detector.analyze(ShortArray(320) { 120 }).shouldSend)
        }
        assertFalse(detector.analyze(ShortArray(320) { 130 }).shouldSend)
        assertTrue(detector.analyze(ShortArray(320) { 300 }).shouldSend)
    }
}
