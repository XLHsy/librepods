package me.kavishdevar.librepods.adaptive

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.DetectedActivity as PlayDetectedActivity
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.services.AirPodsService

/** Runs only while a compatible AirPods connection and the user toggle are active. */
class SoftwareAdaptiveController(
    private val service: AirPodsService,
    private val preferences: SharedPreferences
) {
    companion object {
        const val ENABLED_KEY = "software_adaptive_enabled"
        const val ACTIVITY_KEY = "software_adaptive_activity"
        private const val TAG = "SoftwareAdaptive"
        private const val ACTIVITY_INTERVAL_MS = 10_000L
        private const val TICK_MS = 2_000L
        private const val ACK_TIMEOUT_MS = 8_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val policy = AdaptiveSoundPolicy()
    private val stabilizer = AdaptiveModeStabilizer()
    private val activityClient by lazy { ActivityRecognition.getClient(service) }
    private val activityIntent by lazy {
        PendingIntent.getBroadcast(
            service,
            0,
            Intent(service, SoftwareAdaptiveActivityReceiver::class.java)
                .setAction("me.kavishdevar.librepods.SOFTWARE_ADAPTIVE_ACTIVITY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private var active = false
    private var activityUpdatesRequested = false
    private var registrationGeneration = 0L
    private var activity = DetectedActivity.UNKNOWN
    private var callActive = false
    private var manualOverride = false
    private var lastReportedMode: Int? = null
    private var expectedMode: Int? = null
    private var expectedAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            evaluate()
            if (active) handler.postDelayed(this, TICK_MS)
        }
    }

    fun refresh() = onMain {
        val enabled = preferences.getBoolean(ENABLED_KEY, false)
        if (enabled && service.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            preferences.edit().putBoolean(ENABLED_KEY, false).apply()
            stop()
            return@onMain
        }

        val capabilities = service.airpodsInstance?.model?.capabilities
        val supported = capabilities != null &&
            Capability.LISTENING_MODE in capabilities &&
            Capability.ADAPTIVE_AUDIO !in capabilities
        val connected = BluetoothConnectionManager.aacpSocket?.isConnected == true
        if (enabled && supported && connected) start() else stop()
    }

    fun onActivityUpdate(type: Int, confidence: Int) = onMain {
        if (!active) return@onMain
        val detected = if (confidence < 60) DetectedActivity.UNKNOWN else when (type) {
            PlayDetectedActivity.STILL -> DetectedActivity.STAYING
            PlayDetectedActivity.WALKING, PlayDetectedActivity.ON_FOOT,
            PlayDetectedActivity.ON_BICYCLE -> DetectedActivity.WALKING
            PlayDetectedActivity.RUNNING -> DetectedActivity.RUNNING
            PlayDetectedActivity.IN_VEHICLE -> DetectedActivity.TRANSPORT
            else -> DetectedActivity.UNKNOWN
        }
        if (detected != activity) {
            if (detected != DetectedActivity.UNKNOWN) manualOverride = false
            activity = detected
            stabilizer.reset()
            evaluate()
        }
    }

    fun onCallStateChanged(inCall: Boolean) = onMain {
        callActive = inCall
        if (inCall) stabilizer.reset()
        evaluate()
    }

    fun onModeReported(mode: Int) = onMain {
        if (!active) return@onMain
        val previous = lastReportedMode
        lastReportedMode = mode
        if (mode == expectedMode) {
            expectedMode = null
        } else if (previous != null && previous != mode) {
            manualOverride = true
            stabilizer.reset()
            Log.d(TAG, "Manual mode change; waiting for the next activity change")
            evaluate()
        }
    }

    fun onManualModeSelected() = onMain {
        if (!active) return@onMain
        manualOverride = true
        expectedMode = null
        stabilizer.reset()
        evaluate()
    }

    fun close() = onMain { stop() }

    private fun start() {
        if (active) return
        active = true
        lastReportedMode = service.getANC()
        activity = DetectedActivity.UNKNOWN
        setStatus("UNKNOWN")
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(service)
            == ConnectionResult.SUCCESS
        ) {
            try {
                val generation = ++registrationGeneration
                activityClient.requestActivityUpdates(ACTIVITY_INTERVAL_MS, activityIntent)
                    .addOnSuccessListener {
                        if (!active) {
                            removeActivityUpdates()
                        } else if (generation == registrationGeneration) {
                            activityUpdatesRequested = true
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Activity recognition unavailable; using media state", error)
                        if (generation == registrationGeneration) activityUpdatesRequested = false
                    }
            } catch (error: SecurityException) {
                activityUpdatesRequested = false
                Log.w(TAG, "Activity recognition permission unavailable", error)
            }
        } else {
            Log.w(TAG, "Google Play services unavailable; using media state")
        }
        handler.post(tick)
    }

    private fun stop() {
        if (!active) return
        active = false
        registrationGeneration++
        handler.removeCallbacks(tick)
        if (activityUpdatesRequested) {
            removeActivityUpdates()
            activityUpdatesRequested = false
        }
        activity = DetectedActivity.UNKNOWN
        setStatus("OFF")
        manualOverride = false
        expectedMode = null
        lastReportedMode = null
        stabilizer.reset()
    }

    private fun evaluate() {
        if (!active) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            refresh()
            return
        }
        val phoneCall = callActive || audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager.mode == AudioManager.MODE_RINGTONE
        val mediaPlaying = audioManager.isMusicActive
        setStatus(when {
            phoneCall -> "CALL"
            manualOverride -> "MANUAL"
            activity != DetectedActivity.UNKNOWN -> activity.name
            mediaPlaying -> "MEDIA"
            else -> "IDLE"
        })
        val now = SystemClock.elapsedRealtime()
        if (expectedMode != null) {
            if (now - expectedAtMs < ACK_TIMEOUT_MS) return
            expectedMode = null
        }
        val current = AdaptiveListeningMode.entries.firstOrNull {
            it.protocolValue == service.getANC()
        } ?: return
        val recommended = policy.recommendForPhone(activity, mediaPlaying, phoneCall)
        if (manualOverride && !phoneCall) return
        val command = if (phoneCall) {
            stabilizer.reset()
            recommended.takeIf { it != current }
        } else {
            stabilizer.nextCommand(recommended, current, now)
        } ?: return

        val sent = service.aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            command.protocolValue
        )
        if (sent) {
            expectedMode = command.protocolValue
            expectedAtMs = now
            Log.d(TAG, "Selected ${command.name} for $activity")
        } else {
            Log.w(TAG, "Failed to send listening mode")
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    private fun setStatus(status: String) {
        if (preferences.getString(ACTIVITY_KEY, null) != status) {
            preferences.edit().putString(ACTIVITY_KEY, status).apply()
        }
    }

    private fun removeActivityUpdates() {
        try {
            activityClient.removeActivityUpdates(activityIntent)
                .addOnFailureListener { Log.w(TAG, "Unable to remove activity updates", it) }
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to remove activity updates", error)
        }
    }
}
