package ai.edgez.edgez

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

private const val CONNECTION_SERVICE_CHANNEL_ID = "edgez_connection"
private const val CONNECTION_SERVICE_NOTIFICATION_ID = 1001
private const val ACTION_START_CONNECTION_SERVICE = "ai.edgez.edgez.action.START_CONNECTION_SERVICE"
private const val ACTION_STOP_CONNECTION_SERVICE = "ai.edgez.edgez.action.STOP_CONNECTION_SERVICE"

class BleForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CONNECTION_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startInForeground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                CONNECTION_SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(CONNECTION_SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CONNECTION_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("EdgeZ connection active")
            .setContentText("Keeping the mesh connection alive in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CONNECTION_SERVICE_CHANNEL_ID,
            "EdgeZ connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps EdgeZ connected in the background"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
                .setAction(ACTION_START_CONNECTION_SERVICE)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
                .setAction(ACTION_STOP_CONNECTION_SERVICE)
            context.startService(intent)
        }
    }
}
