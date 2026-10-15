package br.com.lotobot.tunnel.application

import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.net.Socket

interface NetworkLease {
    val id: String
    val kind: NetworkKind
    val label: String
    fun connect(host: String, port: Int, timeoutMillis: Int): Socket
    fun publicIpv6(): String
}
interface NetworkAccess : Closeable { val networks: StateFlow<List<NetworkLease>> }
interface ProfileRepository {
    val profiles: StateFlow<List<Profile>>
    val selected: StateFlow<Profile>
    fun save(profile: Profile)
    fun select(id: String)
    fun delete(id: String)
    var resumeOnBoot: Boolean
}
data class Credential(val privateKey: ByteArray, val passphrase: ByteArray?) : Closeable {
    override fun close() { privateKey.fill(0); passphrase?.fill(0) }
}
interface CredentialStore {
    fun read(id: String): Credential
    fun write(id: String, key: ByteArray, passphrase: ByteArray?)
    fun validateAndWrite(id: String, key: ByteArray, passphrase: ByteArray?)
    fun delete(id: String)
}
interface HostTrust {
    val pending: StateFlow<HostChallenge?>
    suspend fun verify(challenge: HostChallenge): Boolean
    fun answer(accept: Boolean)
}
interface SshConnection : Closeable { val alive: Boolean }
interface SshTransport {
    suspend fun open(profile: Profile, network: NetworkLease): SshConnection
}
interface ConnectionHistory {
    val state: StateFlow<HistorySnapshot>
    fun event(message: String)
    fun begin(network: NetworkKind, destination: String, port: Int): String
    fun connected(id: String)
    fun bytes(id: String, sent: Long, received: Long)
    fun end(id: String, failure: String? = null)
    fun resetSessionTotals()
    fun clear()
}
interface TunnelControl {
    val state: StateFlow<TunnelSnapshot>
    fun connect()
    fun disconnect()
    fun retry()
    fun changeProfile(profile: Profile)
    fun refreshIpv6()
}
interface Timing {
    fun now(): Long
    suspend fun waitMillis(millis: Long)
}
