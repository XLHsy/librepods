package me.kavishdevar.librepods.adaptive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import me.kavishdevar.librepods.services.ServiceManager

/** Receives only the PendingIntent registered by the active AirPods service. */
class SoftwareAdaptiveActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = result.mostProbableActivity
        ServiceManager.getService()?.onSoftwareAdaptiveActivity(activity.type, activity.confidence)
    }
}
