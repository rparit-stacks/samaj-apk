package com.rps.samajapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SamajFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "samaj_general"
        const val CHANNEL_EMERGENCY_ID = "samaj_emergency"
        const val CHANNEL_NAME = "Samaj Notifications"
        const val CHANNEL_EMERGENCY_NAME = "Emergency Alerts"

        /** Subscribe this device to the standard Samaj FCM topics. */
        fun subscribeToTopics() {
            FirebaseMessaging.getInstance().subscribeToTopic("general")
            FirebaseMessaging.getInstance().subscribeToTopic("emergency")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save locally so the web app can read it via window.SamajNative.getFcmToken()
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(MainActivity.KEY_FCM_TOKEN, token).apply()
        // Re-subscribe whenever the token rotates
        subscribeToTopics()
        // The web app will POST this token to /api/v1/device-tokens after login
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return               // nothing to show

        val deepLink = message.data["url"] ?: message.data["link"]
        val type = message.data["type"] ?: ""
        val isEmergency = type == "ALERT"

        showNotification(title, body, deepLink, isEmergency)
    }

    private fun showNotification(title: String, body: String, deepLink: String?, emergency: Boolean = false) {
        val channelId = if (emergency) CHANNEL_EMERGENCY_ID else CHANNEL_ID
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            deepLink?.let { putExtra(MainActivity.EXTRA_DEEP_LINK, it) }
        }

        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_ONE_SHOT

        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent, piFlags
        )

        val soundUri = if (emergency)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(soundUri)
            .setPriority(if (emergency) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .apply { if (emergency) setCategory(NotificationCompat.CATEGORY_ALARM) }
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Community updates from Suryavanshi Samaj"
                    enableLights(true)
                    enableVibration(true)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_EMERGENCY_ID, CHANNEL_EMERGENCY_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Emergency alerts — always delivered at highest priority"
                    enableLights(true)
                    enableVibration(true)
                    setBypassDnd(true)
                }
            )
        }
    }
}
