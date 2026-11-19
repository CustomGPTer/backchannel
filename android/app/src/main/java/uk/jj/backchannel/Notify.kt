package uk.jj.backchannel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import uk.jj.backchannel.ui.MainActivity

object Notify {
    private const val CH_STATUS = "status"
    private const val CH_MESSAGES = "messages"
    const val ID_SERVICE = 1
    private const val ID_MESSAGE = 2
    private const val ID_LOCKED = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Backchannel is running so messages can arrive."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you a message has arrived. Never shows who it is from or what it says."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun service(context: Context, text: String): Notification =
        Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Backchannel is running")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    /** Deliberately says nothing about who or what. */
    fun message(context: Context) {
        if (!allowed(context)) return
        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Backchannel")
            .setContentText("New message")
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_MESSAGE, n)
    }

    fun clearMessage(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_MESSAGE)
        nm.cancel(ID_LOCKED)
    }

    /** Shown after the phone restarts: nothing can arrive until the app is unlocked once. */
    fun locked(context: Context) {
        if (!allowed(context)) return
        val n = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Backchannel is locked")
            .setContentText("Open it and unlock to receive messages.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_LOCKED, n)
    }
}
