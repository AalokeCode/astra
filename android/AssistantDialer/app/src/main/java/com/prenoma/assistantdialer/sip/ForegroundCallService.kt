package com.prenoma.assistantdialer.sip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.prenoma.assistantdialer.MainActivity
import com.prenoma.assistantdialer.telecom.ActiveCall
import com.prenoma.assistantdialer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ForegroundCallService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val serviceType = if (android.os.Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification("SIP service active"),
            serviceType,
        )
        SipManager.start(applicationContext)
        serviceScope.launch {
            combine(SipManager.registrationState, SipManager.callState) { registration, call ->
                if (call == CallState.IDLE) "SIP ${registration.name.lowercase()}" else "Call ${call.name.lowercase()}"
            }.collect { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Must tear down the Telecom connection too, not just SIP. When SIP
            // never connected, SipManager.hangup() is a no-op and the call stayed
            // on screen with no way to clear it.
            ACTION_HANG_UP -> {
                ActiveCall.hangUp()
                stopSelf()
            }
            ACTION_STOP -> stopSelf()
        }
        // NOT sticky: this service exists only for the duration of a call. With
        // START_STICKY Android recreated it after every stop, resurrecting the
        // ongoing notification indefinitely.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Deliberately NOT SipManager.stop().
        //
        // This service exists to show the ongoing-call notification, but it also
        // used to own the SIP core's lifetime. Once ending a call stopped the
        // service (needed, or the notification became permanent), that also tore
        // down the core and dropped the registration — so the first call killed
        // SIP and every later call failed with "registration STOPPED".
        //
        // The core is process-scoped and idempotent to start, so leaving it up
        // keeps the phone registered between calls, which is what a softphone
        // must do to receive them. Teardown is explicit, via SipManager.stop().
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangupIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ForegroundCallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_assistant_tile)
            .setContentTitle("ASTRA Dialer")
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hang up", hangupIntent).build())
            .build()
    }

    companion object {
        const val ACTION_HANG_UP = "com.prenoma.assistantdialer.HANG_UP"
        const val ACTION_STOP = "com.prenoma.assistantdialer.STOP"
        private const val CHANNEL_ID = "assistant_calls"
        private const val NOTIFICATION_ID = 700
    }
}
