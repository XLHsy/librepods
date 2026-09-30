/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2026 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.adaptive

/** Phone-side context. Detection is deliberately separate from the policy. */
enum class DetectedActivity { UNKNOWN, STAYING, WALKING, RUNNING, TRANSPORT }

/** Relative sound categories, not calibrated decibel readings. */
enum class EnvironmentSound { UNKNOWN, QUIET, NORMAL, LOUD, SPEECH }

/** Only modes supported by older noise-control AirPods are available here. */
enum class AdaptiveListeningMode(val protocolValue: Int) {
    OFF(1), NOISE_CANCELLATION(2), TRANSPARENCY(3)
}

data class AdaptiveSoundSettings(
    val stayingMode: AdaptiveListeningMode = AdaptiveListeningMode.NOISE_CANCELLATION,
    val walkingMode: AdaptiveListeningMode = AdaptiveListeningMode.TRANSPARENCY,
    val runningMode: AdaptiveListeningMode = AdaptiveListeningMode.TRANSPARENCY,
    val transportMode: AdaptiveListeningMode = AdaptiveListeningMode.NOISE_CANCELLATION,
    val noiseLevelEnabled: Boolean = false,
    val speechAwarenessEnabled: Boolean = false
)

/** Returns a recommendation only when the phone has a known activity. */
class AdaptiveSoundPolicy(private val settings: AdaptiveSoundSettings = AdaptiveSoundSettings()) {
    fun recommendForPhone(
        activity: DetectedActivity,
        mediaPlaying: Boolean,
        callActive: Boolean
    ): AdaptiveListeningMode {
        if (callActive) return AdaptiveListeningMode.TRANSPARENCY
        return recommend(activity) ?: if (mediaPlaying) {
            AdaptiveListeningMode.NOISE_CANCELLATION
        } else {
            AdaptiveListeningMode.TRANSPARENCY
        }
    }

    fun recommend(
        activity: DetectedActivity,
        environment: EnvironmentSound = EnvironmentSound.UNKNOWN
    ): AdaptiveListeningMode? {
        if (activity == DetectedActivity.UNKNOWN) return null

        if (settings.speechAwarenessEnabled && environment == EnvironmentSound.SPEECH) {
            return AdaptiveListeningMode.TRANSPARENCY
        }

        // Keep ambient sound available while moving on foot. Phone microphone input can
        // be unreliable outdoors, so a loud reading must not override this rule.
        if (settings.noiseLevelEnabled && activity == DetectedActivity.STAYING) {
            when (environment) {
                EnvironmentSound.LOUD -> return AdaptiveListeningMode.NOISE_CANCELLATION
                EnvironmentSound.QUIET, EnvironmentSound.NORMAL ->
                    return AdaptiveListeningMode.TRANSPARENCY
                else -> Unit
            }
        }

        return when (activity) {
            DetectedActivity.STAYING -> settings.stayingMode
            DetectedActivity.WALKING -> settings.walkingMode
            DetectedActivity.RUNNING -> settings.runningMode
            DetectedActivity.TRANSPORT -> settings.transportMode
            DetectedActivity.UNKNOWN -> null
        }
    }
}

/**
 * Prevents repeated ANC/transparency commands when context detection fluctuates.
 * Returns a mode only when a new command is due. Call [reset] when the device or
 * automatic-control session changes. Timestamps must use a monotonic clock.
 */
class AdaptiveModeStabilizer(
    private val stableForMs: Long = 5_000,
    private val minimumSwitchIntervalMs: Long = 15_000
) {
    init {
        require(stableForMs >= 0)
        require(minimumSwitchIntervalMs >= 0)
    }

    private var candidate: AdaptiveListeningMode? = null
    private var candidateSinceMs = 0L
    private var lastSwitchMs: Long? = null

    fun nextCommand(
        recommended: AdaptiveListeningMode?,
        current: AdaptiveListeningMode,
        nowMs: Long
    ): AdaptiveListeningMode? {
        if (recommended == null || recommended == current) {
            candidate = null
            return null
        }

        if (candidate != recommended || nowMs < candidateSinceMs) {
            candidate = recommended
            candidateSinceMs = nowMs
            return null
        }

        if (nowMs - candidateSinceMs < stableForMs) return null
        val last = lastSwitchMs
        if (last != null && nowMs >= last && nowMs - last < minimumSwitchIntervalMs) return null

        candidate = null
        lastSwitchMs = nowMs
        return recommended
    }

    fun reset() {
        candidate = null
        lastSwitchMs = null
    }
}
