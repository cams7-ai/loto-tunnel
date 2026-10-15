package br.com.lotobot.tunnel.di

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import br.com.lotobot.tunnel.infrastructure.*
import br.com.lotobot.tunnel.service.TunnelService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TunnelApp : Application() {
    val graph by lazy { Graph(this) }
}
class Graph(val app: TunnelApp) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val vault = Vault(app)
    val profiles: ProfileRepository = Profiles(app, vault)
    val trust: HostTrust = TrustStore(vault)
    val history: ConnectionHistory = History(vault, processScope)
    val ssh: SshTransport = JschTransport(vault, trust, history)
    val control = ServiceControl(app, profiles)
    val timing = object : Timing {
        override fun now() = System.currentTimeMillis()
        override suspend fun waitMillis(millis: Long) = delay(millis)
    }
}
class ServiceControl(private val app: Application, private val profiles: ProfileRepository) : TunnelControl {
    internal val snapshot = MutableStateFlow(TunnelSnapshot(profile = profiles.selected.value))
    override val state = snapshot.asStateFlow()
    private fun command(action: String) { ContextCompat.startForegroundService(app, Intent(app, TunnelService::class.java).setAction(action)) }
    override fun connect() = command(TunnelService.CONNECT)
    override fun retry() = command(TunnelService.RETRY)
    override fun disconnect() = command(TunnelService.DISCONNECT)
    override fun refreshIpv6() = command(TunnelService.REFRESH)
    override fun changeProfile(profile: Profile) { profiles.save(profile); command(TunnelService.CHANGE) }
}
