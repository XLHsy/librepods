package me.kavishdevar.librepods.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveSoundPolicyTest {
    @Test
    fun phoneCallOverridesTransportAndUnknownActivityUsesMediaFallback() {
        val policy = AdaptiveSoundPolicy()

        assertEquals(
            AdaptiveListeningMode.TRANSPARENCY,
            policy.recommendForPhone(DetectedActivity.TRANSPORT, mediaPlaying = true, callActive = true)
        )
        assertEquals(
            AdaptiveListeningMode.NOISE_CANCELLATION,
            policy.recommendForPhone(DetectedActivity.UNKNOWN, mediaPlaying = true, callActive = false)
        )
        assertEquals(
            AdaptiveListeningMode.TRANSPARENCY,
            policy.recommendForPhone(DetectedActivity.UNKNOWN, mediaPlaying = false, callActive = false)
        )
    }

    @Test
    fun defaultActivityRulesNeverRequestHardwareAdaptiveMode() {
        val policy = AdaptiveSoundPolicy()

        assertNull(policy.recommend(DetectedActivity.UNKNOWN))
        assertEquals(AdaptiveListeningMode.NOISE_CANCELLATION, policy.recommend(DetectedActivity.STAYING))
        assertEquals(AdaptiveListeningMode.TRANSPARENCY, policy.recommend(DetectedActivity.WALKING))
        assertEquals(AdaptiveListeningMode.TRANSPARENCY, policy.recommend(DetectedActivity.RUNNING))
        assertEquals(AdaptiveListeningMode.NOISE_CANCELLATION, policy.recommend(DetectedActivity.TRANSPORT))
        assertEquals(setOf(1, 2, 3), AdaptiveListeningMode.entries.map { it.protocolValue }.toSet())
    }

    @Test
    fun optionalSoundRulesPreserveWalkingTransparency() {
        val policy = AdaptiveSoundPolicy(
            AdaptiveSoundSettings(noiseLevelEnabled = true, speechAwarenessEnabled = true)
        )

        assertEquals(
            AdaptiveListeningMode.TRANSPARENCY,
            policy.recommend(DetectedActivity.STAYING, EnvironmentSound.QUIET)
        )
        assertEquals(
            AdaptiveListeningMode.NOISE_CANCELLATION,
            policy.recommend(DetectedActivity.STAYING, EnvironmentSound.LOUD)
        )
        assertEquals(
            AdaptiveListeningMode.TRANSPARENCY,
            policy.recommend(DetectedActivity.WALKING, EnvironmentSound.LOUD)
        )
        assertEquals(
            AdaptiveListeningMode.TRANSPARENCY,
            policy.recommend(DetectedActivity.TRANSPORT, EnvironmentSound.SPEECH)
        )
    }

    @Test
    fun unstableContextDoesNotCauseCommands() {
        val stabilizer = AdaptiveModeStabilizer()
        val anc = AdaptiveListeningMode.NOISE_CANCELLATION
        val transparency = AdaptiveListeningMode.TRANSPARENCY

        assertNull(stabilizer.nextCommand(transparency, anc, 0))
        assertNull(stabilizer.nextCommand(anc, anc, 3_000))
        assertNull(stabilizer.nextCommand(transparency, anc, 4_000))
        assertNull(stabilizer.nextCommand(transparency, anc, 8_999))
        assertEquals(transparency, stabilizer.nextCommand(transparency, anc, 9_000))
        assertNull(stabilizer.nextCommand(transparency, transparency, 9_100))
    }

    @Test
    fun minimumIntervalAndResetAreEnforced() {
        val stabilizer = AdaptiveModeStabilizer()
        val anc = AdaptiveListeningMode.NOISE_CANCELLATION
        val transparency = AdaptiveListeningMode.TRANSPARENCY

        assertNull(stabilizer.nextCommand(transparency, anc, 0))
        assertEquals(transparency, stabilizer.nextCommand(transparency, anc, 5_000))
        assertNull(stabilizer.nextCommand(anc, transparency, 6_000))
        assertNull(stabilizer.nextCommand(anc, transparency, 11_000))
        assertEquals(anc, stabilizer.nextCommand(anc, transparency, 20_000))

        stabilizer.reset()
        assertNull(stabilizer.nextCommand(transparency, anc, 21_000))
        assertEquals(transparency, stabilizer.nextCommand(transparency, anc, 26_000))
    }
}
