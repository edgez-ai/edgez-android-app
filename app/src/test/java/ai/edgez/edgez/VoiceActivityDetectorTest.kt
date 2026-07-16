package ai.edgez.edgez

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private fun audioFrame(amplitude: Int, dcOffset: Int = 0) =
        ShortArray(320) { index -> (dcOffset + if (index and 1 == 0) amplitude else -amplitude).toShort() }

    @Test
    fun sustainedSilenceIsNotSent() {
        val detector = VoiceActivityDetector()

        repeat(20) {
            assertFalse(detector.analyze(audioFrame(10)).shouldSend)
        }
    }

    @Test
    fun speechStartsImmediatelyAndKeepsShortHangover() {
        val detector = VoiceActivityDetector(hangoverFrames = 2, calibrationFrames = 0)

        val firstSpeech = detector.analyze(audioFrame(2_000))
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
            assertFalse(detector.analyze(audioFrame(amplitude)).shouldSend)
        }
    }

    @Test
    fun quietDeviceSpeechIsStillDetected() {
        val detector = VoiceActivityDetector()

        repeat(10) {
            assertFalse(detector.analyze(audioFrame(20)).shouldSend)
        }
        val decision = detector.analyze(audioFrame(80))
        assertTrue(decision.shouldSend)
        assertTrue(decision.speechStarted)
    }

    @Test
    fun speechIsNotBlockedByStartupCalibration() {
        val detector = VoiceActivityDetector()

        repeat(3) {
            assertFalse(detector.analyze(audioFrame(20)).shouldSend)
        }
        val decision = detector.analyze(audioFrame(100))

        assertTrue(decision.shouldSend)
        assertTrue(decision.speechStarted)
    }

    @Test
    fun louderPhoneCalibratesWithoutSendingItsNoiseFloor() {
        val detector = VoiceActivityDetector()

        repeat(10) {
            assertFalse(detector.analyze(audioFrame(120)).shouldSend)
        }
        assertFalse(detector.analyze(audioFrame(130)).shouldSend)
        assertTrue(detector.analyze(audioFrame(300)).shouldSend)
    }

    @Test
    fun microphoneDcOffsetIsNotTreatedAsSpeech() {
        val detector = VoiceActivityDetector(calibrationFrames = 0)

        repeat(20) {
            assertFalse(detector.analyze(ShortArray(320) { 600 }).shouldSend)
        }
        assertTrue(detector.analyze(audioFrame(amplitude = 200, dcOffset = 600)).shouldSend)
    }

    @Test
    fun oneQuietCalibrationFrameDoesNotUnderestimateNoiseFloor() {
        val detector = VoiceActivityDetector()

        detector.analyze(audioFrame(5))
        repeat(9) { detector.analyze(audioFrame(100)) }

        assertFalse(detector.analyze(audioFrame(110)).shouldSend)
        assertTrue(detector.analyze(audioFrame(220)).shouldSend)
    }
}
