package br.com.lotobot.tunnel.domain

enum class NetworkMode(val label: String) { WIFI("Somente Wi-Fi"), MOBILE("Somente dados móveis"), AUTO("Automático") }
enum class NetworkKind(val label: String) { WIFI("Wi-Fi"), MOBILE("Dados móveis") }
enum class TunnelStatus(val label: String) {
    DISCONNECTED("Desconectado"), CONNECTING("Conectando"), CONNECTED("Conectado"), WAITING("Aguardando rede"), RECONNECTING("Reconectando"), ERROR("Erro")
}
data class Profile(
    val id: String = "principal", val name: String = "EC2 LotoBot", val host: String = "",
    val port: Int = 22, val user: String = "ubuntu", val remotePort: Int = 1080,
    val mode: NetworkMode = NetworkMode.WIFI, val keyImported: Boolean = false,
    val timeoutSeconds: Int = 15, val keepaliveSeconds: Int = 30, val missedKeepalives: Int = 3
) {
    fun validate(): String? = when {
        name.isBlank() -> "Informe o nome do perfil."
        host.isBlank() || host.any { it.isWhitespace() } || host.contains('/') -> "Informe um IPv6, IPv4 ou domínio, sem protocolo."
        port !in 1..65535 || remotePort !in 1..65535 -> "As portas devem estar entre 1 e 65535."
        user.isBlank() || user.any { it.isWhitespace() } -> "Informe um usuário SSH válido."
        !keyImported -> "Importe uma chave privada."
        timeoutSeconds !in 1..120 || keepaliveSeconds !in 5..300 || missedKeepalives !in 1..10 -> "Confira os limites de timeout e keepalive."
        else -> null
    }
}
data class PublicIpv6(val address: String? = null, val networkId: String? = null, val networkLabel: String? = null, val updatedAt: Long? = null, val error: String? = null, val stale: Boolean = true)
data class TunnelSnapshot(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED, val profile: Profile = Profile(),
    val networkId: String? = null, val network: NetworkKind? = null, val networkLabel: String? = null,
    val connectedAt: Long? = null, val attempt: Int = 0, val lastFailure: String? = null,
    val ipv6: PublicIpv6 = PublicIpv6(), val forwardingRejected: Boolean = false
)
enum class ConnectionState(val label: String) { CONNECTING("Conectando"), CONNECTED("Conectado"), CLOSED("Encerrado"), ERROR("Erro") }
data class Usage(
    val id: String, val startedAt: Long, val network: NetworkKind, val destination: String, val port: Int,
    val state: ConnectionState = ConnectionState.CONNECTING, val sent: Long = 0, val received: Long = 0,
    val endedAt: Long? = null, val failure: String? = null
)
data class SshEvent(val at: Long, val message: String)
data class Traffic(val sent: Long = 0, val received: Long = 0)
data class HistorySnapshot(val connections: List<Usage> = emptyList(), val events: List<SshEvent> = emptyList(), val wifi: Traffic = Traffic(), val mobile: Traffic = Traffic())
data class HostChallenge(val host: String, val port: Int, val algorithm: String, val fingerprint: String, val key: String)
class TunnelFailure(message: String, val retryable: Boolean = true, val forwardingRejected: Boolean = false) : Exception(message)
object ReconnectPolicy { fun delayMillis(attempt: Int): Long = (1000L shl attempt.coerceIn(0, 6)).coerceAtMost(60_000L) }
object NetworkPolicy {
    fun choose(mode: NetworkMode, candidates: List<Pair<String, NetworkKind>>, current: String?, wifiStable: Boolean): String? {
        val wifi = candidates.firstOrNull { it.second == NetworkKind.WIFI }?.first
        val mobile = candidates.firstOrNull { it.second == NetworkKind.MOBILE }?.first
        return when (mode) {
            NetworkMode.WIFI -> wifi
            NetworkMode.MOBILE -> mobile
            NetworkMode.AUTO -> if (current == mobile && !wifiStable) mobile ?: wifi else wifi ?: mobile
        }
    }
}
