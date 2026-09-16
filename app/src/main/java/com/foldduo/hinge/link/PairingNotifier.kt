package com.foldduo.hinge.link

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.foldduo.hinge.AngleRuntime
import com.foldduo.hinge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets the user type the wireless-debugging pairing code straight into a
 * notification. Android's "Pair device with pairing code" dialog lives inside
 * Settings, and on many builds it is dismissed as soon as Settings leaves the
 * foreground, so switching back to this app to type the code closes it.
 * Inline reply from the shade avoids that dance entirely.
 */
object PairingNotifier {
    const val CHANNEL_ID = "pairing"
    const val KEY_CODE = "pairing_code"
    const val ACTION_SUBMIT_CODE = "com.foldduo.hinge.SUBMIT_PAIRING_CODE"
    private const val NOTIFICATION_ID = 1001
    private const val TAG = "ZFoldDuoEngine"

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    fun showCodePrompt(context: Context, port: Int) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel(context.getString(R.string.pairing_notification_input_hint))
            .build()
        val submit = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_SUBMIT_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            null,
            context.getString(R.string.pairing_notification_action),
            submit,
        ).addRemoteInput(remoteInput).build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.pairing_notification_title))
            .setContentText(context.getString(R.string.pairing_notification_text, port))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.pairing_notification_text, port)))
            .addAction(action)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showResult(context: Context, success: Boolean, detail: String?) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val text = if (success) {
            context.getString(R.string.pairing_notification_success)
        } else {
            context.getString(R.string.pairing_notification_failure, detail ?: "")
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.pairing_notification_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(if (success) 4_000L else 15_000L)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismissPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.pairing_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    internal fun submitCode(context: Context, code: String, done: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = AngleRuntime.pair(code)
            showResult(context, result.isSuccess, result.exceptionOrNull()?.message)
            if (result.isFailure) Log.w(TAG, "pairing from notification failed", result.exceptionOrNull())
            done()
        }
    }
}

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotifier.ACTION_SUBMIT_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotifier.KEY_CODE)
            ?.toString()
            ?.filter(Char::isDigit)
            .orEmpty()
        val pending = goAsync()
        PairingNotifier.submitCode(context.applicationContext, code) { pending.finish() }
    }
}
