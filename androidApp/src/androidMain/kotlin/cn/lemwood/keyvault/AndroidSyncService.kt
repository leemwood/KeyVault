package cn.lemwood.keyvault

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.lemwood.keyvault.sync.IncrementalStore
import cn.lemwood.keyvault.sync.PairingDevice
import cn.lemwood.keyvault.sync.SyncWindowControl
import cn.lemwood.keyvault.sync.SyncWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val ANDROID_SYNC_WINDOW_MS = 30 * 60 * 1000L
private const val CHANNEL = "keyvault_lan_sync"
private const val NOTIFICATION = 2041
private const val STOP = "cn.lemwood.keyvault.STOP_SYNC"

class AndroidSyncWindow internal constructor(private val context: Context) : SyncWindowControl {
    internal class Session(val device: PairingDevice, val store: IncrementalStore)
    internal var session: Session? = null
        private set
    private val status = MutableStateFlow(SyncWindowState.STOPPED)
    override val state = status.asStateFlow()

    override fun start(device: PairingDevice, store: IncrementalStore) {
        if (status.value == SyncWindowState.RUNNING || status.value == SyncWindowState.STARTING) return
        status.value = SyncWindowState.STARTING
        session = Session(device, store)
        try {
            ContextCompat.startForegroundService(context, Intent(context, AndroidSyncService::class.java))
        } catch (_: RuntimeException) {
            update(SyncWindowState.UNAVAILABLE)
        }
    }

    override fun stop() {
        val service = Intent(context, AndroidSyncService::class.java)
        context.stopService(service)
        update(SyncWindowState.STOPPED)
    }

    internal fun update(state: SyncWindowState, expected: Session? = session) {
        if (session !== expected) return
        status.value = state
        if (state == SyncWindowState.STOPPED || state == SyncWindowState.UNAVAILABLE) session = null
    }
}

class AndroidSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var activeSession: AndroidSyncWindow.Session? = null
    private val app get() = application as KeyVaultApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running?.isActive == true) return START_NOT_STICKY
        val session = app.syncWindow.session ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        activeSession = session
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), type)
        } catch (_: RuntimeException) {
            app.syncWindow.update(SyncWindowState.UNAVAILABLE, session)
            stopSelf()
            return START_NOT_STICKY
        }
        running = scope.launch {
            try {
                withTimeoutOrNull(ANDROID_SYNC_WINDOW_MS) {
                    session.device.runSyncWindow(session.store) { app.syncWindow.update(SyncWindowState.RUNNING, session) }
                }
                app.syncWindow.update(SyncWindowState.STOPPED, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                app.syncWindow.update(SyncWindowState.UNAVAILABLE, session)
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        activeSession?.let { app.syncWindow.update(SyncWindowState.STOPPED, it) }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "局域网同步", NotificationManager.IMPORTANCE_LOW),
        )
        val showApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AndroidSyncService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("KeyVault 局域网同步")
            .setContentText("同步窗口开启后最多运行 30 分钟")
            .setContentIntent(showApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止同步", stop).build())
            .build()
    }
}
