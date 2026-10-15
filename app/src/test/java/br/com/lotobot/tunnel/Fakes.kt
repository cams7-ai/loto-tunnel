package br.com.lotobot.tunnel

import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MemoryHistory : ConnectionHistory {
    override val state = MutableStateFlow(HistorySnapshot())
    @Synchronized override fun event(message: String) { state.value = state.value.copy(events = state.value.events + SshEvent(0, message)) }
    @Synchronized override fun begin(network: NetworkKind, destination: String, port: Int): String {
        val id = UUID.randomUUID().toString(); state.value = state.value.copy(connections = state.value.connections + Usage(id, 0, network, destination, port)); return id
    }
    @Synchronized private fun update(id: String, action: (Usage) -> Usage) { state.value = state.value.copy(connections = state.value.connections.map { if (it.id == id) action(it) else it }) }
    override fun connected(id: String) = update(id) { it.copy(state = ConnectionState.CONNECTED) }
    override fun bytes(id: String, sent: Long, received: Long) = update(id) { it.copy(sent = it.sent + sent, received = it.received + received) }
    override fun end(id: String, failure: String?) = update(id) { it.copy(state = if (failure == null) ConnectionState.CLOSED else ConnectionState.ERROR, endedAt = 1, failure = failure) }
    override fun resetSessionTotals() {}
    override fun clear() { state.value = HistorySnapshot() }
}
class MemoryProfiles : ProfileRepository {
    override val selected = MutableStateFlow(Profile(host = "::1", keyImported = true))
    override val profiles = MutableStateFlow(listOf(selected.value))
    override var resumeOnBoot = false
    override fun save(profile: Profile) { selected.value = profile; profiles.value = listOf(profile) }
    override fun select(id: String) {}
    override fun delete(id: String) {}
}
