package ai.labradorlabs.dexbrightener

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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the overlay is up, and is the default [OverlayHost].
 * Uses TYPE_APPLICATION_OVERLAY, so its opacity is capped at 0.8 (see [Levels]).
 */
class DimService : Service(), OverlayHost {

    override val hostContext: Context get() = this
    override val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    override val maxAlpha: Float = Levels.UNTRUSTED_MAX_ALPHA
    override val priority: Int = 0
    override val label: String = "오버레이"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DimEngine.init(this)
        createChannel()
        startForegroundCompat(buildNotification())
        DimEngine.registerHost(this)

        scope.launch {
            combine(
                DimEngine.prefs.state,
                DimEngine.displays,
                DimEngine.autoLevel,
            ) { _, _, _ -> Unit }.collect {
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DimEngine.setEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> DimEngine.apply()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DimEngine.unregisterHost(this)
        scope.cancel()
        super.onDestroy()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(): Notification {
        val settings = DimEngine.prefs.current
        val targets = DimEngine.targetDisplays()
        val level = if (settings.mode == DimMode.AUTO) {
            DimEngine.autoLevel.value
        } else {
            targets.firstOrNull()?.let { settings.levelFor(it.key) } ?: settings.defaultLevel
        }
        val modeText = if (settings.mode == DimMode.AUTO) "자동" else "고정"
        val text = when {
            targets.isEmpty() -> "외부 디스플레이 대기 중"
            else -> "$modeText · 밝기 $level% · ${targets.joinToString { it.name }}"
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DimService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "끄기", stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dim_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "ai.labradorlabs.dexbrightener.STOP"

        /** Returns false when Android refused a background foreground-service start. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, DimService::class.java))
            true
        } catch (t: Throwable) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DimService::class.java))
        }
    }
}
