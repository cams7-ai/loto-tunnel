package br.com.lotobot.tunnel.application

import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** One actor accepts commands. Only its owned worker may own a live SSH session. */
class TunnelCoordinator(
    private val scope: CoroutineScope, private val ssh: SshTransport,
    private val network: NetworkAccess, private val profiles: ProfileRepository,
    private val history: ConnectionHistory, private val timing: Timing
) {
    private sealed interface Command {
        data object Connect : Command
        data object Recover : Command
        data object Retry : Command
        data class Disconnect(val completed: CompletableDeferred<Unit>? = null) : Command
        data class Change(val profile: Profile) : Command
        data object Refresh : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(TunnelSnapshot(profile = profiles.selected.value))
    val state = mutable.asStateFlow()
    private var worker: Job? = null
    private var ipJob: Job? = null
    private var diagnostic: NetworkLease? = null
    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    Command.Connect -> if (worker?.isActive != true) {
                        val profile = profiles.selected.value
                        val error = profile.validate()
                        if (error != null) mutable.update { it.copy(status = TunnelStatus.ERROR, lastFailure = error, profile = profile) }
                        else {
                            mutable.update { it.copy(lastFailure = null, forwardingRejected = false) }
                            history.resetSessionTotals()
                            worker = launch { runTunnel(profile) }
                        }
                    }
                    Command.Recover -> {
                        if (mutable.value.status == TunnelStatus.ERROR && mutable.value.forwardingRejected) {
                            worker?.cancelAndJoin()
                            val profile = profiles.selected.value
                            // Never recover a different profile from the one that failed.
                            if (profile == mutable.value.profile && profile.validate() == null) {
                                mutable.update { it.copy(status = TunnelStatus.RECONNECTING, lastFailure = null, forwardingRejected = false) }
                                worker = launch { runTunnel(profile, recoverPort = true) }
                            }
                        }
                    }
                    Command.Retry -> {
                        mutable.update { it.copy(lastFailure = null, forwardingRejected = false) }
                        worker?.cancelAndJoin(); worker = null
                        ipJob?.cancelAndJoin()
                        val profile = profiles.selected.value
                        val error = profile.validate()
                        if (error != null) mutable.update { it.copy(status = TunnelStatus.ERROR, lastFailure = error, profile = profile) }
                        else { history.resetSessionTotals(); worker = launch { runTunnel(profile) } }
                    }
                    is Command.Disconnect -> {
                        worker?.cancelAndJoin(); worker = null
                        ipJob?.cancelAndJoin()
                        history.event("Desconexão manual; reconexão cancelada.")
                        mutable.update { it.copy(status = TunnelStatus.DISCONNECTED, connectedAt = null, attempt = 0) }
                        command.completed?.complete(Unit)
                    }
                    is Command.Change -> {
                        val running = worker?.isActive == true
                        worker?.cancelAndJoin(); worker = null
                        ipJob?.cancelAndJoin(); diagnostic = null
                        profiles.save(command.profile)
                        mutable.value = TunnelSnapshot(profile = command.profile)
                        history.event("Perfil ou modo de rede alterado; conexões anteriores encerradas.")
                        if (running) worker = launch { runTunnel(command.profile) }
                    }
                    Command.Refresh -> {
                        val available = diagnostic?.takeIf { lease -> network.networks.value.any { it.id == lease.id } }
                            ?: choose(profiles.selected.value, null, emptyMap(), 0)
                        if (available == null) mutable.update { it.copy(ipv6 = PublicIpv6(error = "Nenhuma rede permitida com acesso à internet.")) }
                        else refresh(available)
                    }
                }
            }
        }
    }
    fun connect() { commands.trySend(Command.Connect) }
    fun recover() { commands.trySend(Command.Recover) }
    fun retry() { commands.trySend(Command.Retry) }
    fun disconnect() { commands.trySend(Command.Disconnect()) }
    suspend fun disconnectAndAwait() {
        val completed = CompletableDeferred<Unit>()
        commands.send(Command.Disconnect(completed))
        completed.await()
    }
    fun changeProfile(profile: Profile) { commands.trySend(Command.Change(profile)) }
    fun refreshIpv6() { commands.trySend(Command.Refresh) }

    private fun choose(profile: Profile, current: NetworkLease?, blocked: Map<String, Long>, wifiSince: Long): NetworkLease? {
        val candidates = network.networks.value.filter { (blocked[it.id] ?: 0) <= timing.now() }
        val id = NetworkPolicy.choose(profile.mode, candidates.map { it.id to it.kind }, current?.id, timing.now() - wifiSince >= 15_000)
        return candidates.find { it.id == id }
    }
    private suspend fun runTunnel(profile: Profile, recoverPort: Boolean = false) {
        var recoveryPending = recoverPort
        val blocked = mutableMapOf<String, Long>()
        var current: NetworkLease? = null
        var wifiSince = timing.now()
        var wifiId: String? = null
        var attempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                val candidateWifi = network.networks.value.find { it.kind == NetworkKind.WIFI }?.id
                if (candidateWifi != wifiId) { wifiId = candidateWifi; wifiSince = timing.now() }
                val selected = choose(profile, current, blocked, wifiSince)
                if (selected == null) {
                    current = null; diagnostic = null
                    mutable.update { it.copy(status = TunnelStatus.WAITING, networkId = null, network = null, networkLabel = null, connectedAt = null, ipv6 = PublicIpv6(error = "Aguardando rede permitida com internet e acesso ao servidor.")) }
                    timing.waitMillis(1000); continue
                }
                current = selected; diagnostic = selected
                mutable.update { it.copy(profile = profile, status = if (attempt == 0) TunnelStatus.CONNECTING else TunnelStatus.RECONNECTING, networkId = selected.id, network = selected.kind, networkLabel = selected.label, connectedAt = null, attempt = attempt) }
                refresh(selected)
                try {
                    history.event("Conectando por ${selected.label}; tentativa ${attempt + 1}.")
                    val recovering = recoveryPending
                    recoveryPending = false // Destructive command runs only once per explicit action.
                    val connection = if (recovering) {
                        history.event("Liberação solicitada pelo usuário para a porta remota ${profile.remotePort}/tcp.")
                        ssh.recoverAndOpen(profile, selected)
                    } else ssh.open(profile, selected)
                    connection.use { session ->
                        mutable.update { it.copy(status = TunnelStatus.CONNECTED, connectedAt = timing.now(), attempt = 0) }
                        history.event("Servidor aceitou 127.0.0.1:${profile.remotePort}. Túnel conectado; saída SOCKS5 ainda requer validação.")
                        attempt = 0
                        while (session.alive) {
                            timing.waitMillis(1000)
                            val newWifi = network.networks.value.find { it.kind == NetworkKind.WIFI }?.id
                            if (newWifi != wifiId) { wifiId = newWifi; wifiSince = timing.now() }
                            val next = choose(profile, selected, blocked, wifiSince)
                            if (next?.id != selected.id) {
                                history.event("Mudança de rede: ${selected.label} → ${next?.label ?: "aguardando rede"}; conexões interrompidas.")
                                break
                            }
                        }
                        if (!session.alive && network.networks.value.any { it.id == selected.id }) throw TunnelFailure("A sessão SSH foi interrompida.")
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    val message = failure.message ?: "Falha no túnel SSH."
                    history.event(message)
                    mutable.update { it.copy(lastFailure = message, connectedAt = null, forwardingRejected = (failure as? TunnelFailure)?.forwardingRejected == true) }
                    if (failure is TunnelFailure && !failure.retryable) {
                        mutable.update { it.copy(status = TunnelStatus.ERROR) }; return
                    }
                    if (profile.mode == NetworkMode.AUTO) blocked[selected.id] = timing.now() + 30_000
                    mutable.update { it.copy(status = TunnelStatus.RECONNECTING, attempt = attempt + 1) }
                    timing.waitMillis(ReconnectPolicy.delayMillis(attempt++))
                }
            }
        } finally {
            // IPv6 is a network diagnostic, independent of SSH authentication/forwarding.
            // Explicit disconnect/profile changes cancel it through the command actor.
            mutable.update { it.copy(connectedAt = null) }
        }
    }
    private fun refresh(lease: NetworkLease) {
        ipJob?.cancel()
        val previous = mutable.value.ipv6
        mutable.update { it.copy(ipv6 = if (previous.networkId == lease.id) previous.copy(stale = true) else PublicIpv6(networkId = lease.id, networkLabel = lease.label)) }
        ipJob = scope.launch {
            try {
                val ip = runInterruptible(Dispatchers.IO) { lease.publicIpv6() }
                ensureActive()
                mutable.update { it.copy(ipv6 = PublicIpv6(ip, lease.id, lease.label, timing.now(), stale = false)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { it.copy(ipv6 = it.ipv6.copy(error = "Não foi possível consultar o IPv6 público nesta rede. O diagnóstico não determina o estado SSH.", stale = true)) }
            }
        }
    }
}
