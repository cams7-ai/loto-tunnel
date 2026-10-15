package br.com.lotobot.tunnel.service

import android.app.*
import android.content.*
import android.os.*
import br.com.lotobot.tunnel.R
import br.com.lotobot.tunnel.application.TunnelCoordinator
import br.com.lotobot.tunnel.di.TunnelApp
import br.com.lotobot.tunnel.infrastructure.AndroidNetworks
import br.com.lotobot.tunnel.presentation.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TunnelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var coordinator: TunnelCoordinator
    private lateinit var networks: AndroidNetworks
    private var wake: PowerManager.WakeLock? = null
    private val graph get() = (application as TunnelApp).graph
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tunnel", "Túnel SSH", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("Desconectado"))
        networks = AndroidNetworks(this)
        coordinator = TunnelCoordinator(scope, graph.ssh, networks, graph.profiles, graph.history, graph.timing)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lotobot:tunnel").apply { setReferenceCounted(false) }
        scope.launch { coordinator.state.collectLatest { state ->
            graph.control.snapshot.value = state
            val active = state.status in setOf(br.com.lotobot.tunnel.domain.TunnelStatus.CONNECTING, br.com.lotobot.tunnel.domain.TunnelStatus.CONNECTED, br.com.lotobot.tunnel.domain.TunnelStatus.RECONNECTING)
            if (active && wake?.isHeld != true) wake?.acquire(10 * 60 * 1000L)
            if (!active && wake?.isHeld == true) wake?.release()
            manager.notify(1, notification(state.status.label))
        } }
        scope.launch { while (isActive) { delay(5 * 60 * 1000L); if (wake?.isHeld == true) wake?.acquire(10 * 60 * 1000L) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CONNECT -> coordinator.connect()
            RETRY -> coordinator.retry()
            RECOVER -> coordinator.recover()
            DISCONNECT -> scope.launch { coordinator.disconnectAndAwait(); stopSelf(startId) }
            REFRESH -> coordinator.refreshIpv6()
            CHANGE -> coordinator.changeProfile(graph.profiles.selected.value)
        }
        // No silent restart as a supposedly live session after process death.
        return START_NOT_STICKY
    }
    private fun notification(state: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tunnel").setSmallIcon(R.drawable.ic_tunnel).setContentTitle("LotoBot Tunnel")
            .setContentText(state).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desconectar", stop).build()).build()
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        scope.cancel(); networks.close()
        if (wake?.isHeld == true) wake?.release()
        graph.control.snapshot.value = graph.control.snapshot.value.copy(status = br.com.lotobot.tunnel.domain.TunnelStatus.DISCONNECTED, connectedAt = null, ipv6 = graph.control.snapshot.value.ipv6.copy(stale = true))
        super.onDestroy()
    }
    companion object {
        const val RECOVER = "tunnel.recover"; const val RETRY = "tunnel.retry"; const val CONNECT = "tunnel.connect"; const val DISCONNECT = "tunnel.disconnect"
        const val REFRESH = "tunnel.refresh"; const val CHANGE = "tunnel.change"
    }
}
/** Boot only offers a user action. Starting an FGS automatically is version/OEM restricted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as TunnelApp).graph
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !graph.profiles.resumeOnBoot) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("resume", "Retomar após reiniciar", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(2, Notification.Builder(context, "resume").setSmallIcon(R.drawable.ic_tunnel).setContentTitle("Retomar LotoBot Tunnel")
            .setContentText("Abra o aplicativo e toque em Conectar.").setContentIntent(open).setAutoCancel(true).build()) } catch (_: SecurityException) { }
    }
}
