package com.moboserver.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.moboserver.app.MainActivity
import com.moboserver.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Minecraft server alive while the app is in
 * the background. It also holds a partial wake lock and a high performance
 * Wi-Fi lock so the phone does not suspend the network while friends play.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var finishJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ServerController.init(this)
        createChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerController.stop()
            return START_NOT_STICKY
        }

        // A new start cancels a scheduled shutdown from the previous session.
        finishJob?.cancel()
        finishJob = null

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ServerController.state.value),
            foregroundType(),
        )
        ServerController.start()
        observe()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            ServerController.state.collect { state ->
                updateNotification(state)
                if (state.status == ServerStatus.STOPPED || state.status == ServerStatus.ERROR) {
                    finishSoon()
                }
            }
        }
    }

    /**
     * Stops the service shortly after the server is no longer running. The
     * decision is taken again after the delay: a start that happens in the
     * meantime (Stopped → Starting) must keep the service, its locks and the
     * notification alive.
     */
    private fun finishSoon() {
        if (finishJob?.isActive == true) return
        finishJob = scope.launch {
            delay(1200)
            val status = ServerController.state.value.status
            if (status == ServerStatus.STARTING || status == ServerStatus.RUNNING ||
                status == ServerStatus.STOPPING
            ) {
                return@launch
            }
            if (ServerController.isBusy) return@launch
            releaseLocks()
            ServiceCompat.stopForeground(this@ServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(state: RuntimeState) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            // notifications were disabled by the user, the service still runs
        } catch (e: Exception) {
            // never crash because of a notification
        }
    }

    private fun buildNotification(state: RuntimeState): Notification {
        val config = Store.config.value
        val title = when (state.status) {
            ServerStatus.STARTING -> "Iniciando servidor..."
            ServerStatus.RUNNING -> "Servidor online"
            ServerStatus.STOPPING -> "Desligando servidor..."
            ServerStatus.ERROR -> "Servidor parado com erro"
            ServerStatus.STOPPED -> "Servidor desligado"
        }
        val text = when {
            state.status == ServerStatus.RUNNING -> {
                val port = state.info?.port ?: config.port
                val max = state.info?.maxPlayers ?: config.maxPlayers
                "${state.players.size}/$max jogadores • porta $port"
            }
            state.error != null -> state.error
            else -> "Toque para abrir o MoboServer"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setOngoing(state.status != ServerStatus.STOPPED && state.status != ServerStatus.ERROR)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.status == ServerStatus.RUNNING || state.status == ServerStatus.STARTING) {
            builder.addAction(0, "Parar servidor", stopIntent())
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, ServerService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servidor do Minecraft",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mostra o estado do servidor e o número de jogadores online"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoboServer::server")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MoboServer::wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            // Multicast is what makes the server show up in the LAN tab of the
            // Minecraft clients on the same Wi-Fi.
            multicastLock = wifi?.createMulticastLock("MoboServer::lan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    companion object {
        const val CHANNEL_ID = "moboserver_status"
        const val NOTIFICATION_ID = 4242
        const val ACTION_STOP = "com.moboserver.app.action.STOP_SERVER"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerService::class.java),
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                ServerController.stop()
            }
        }
    }
}
