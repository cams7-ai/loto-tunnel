package br.com.lotobot.tunnel.infrastructure

import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/** The remote listener is a SOCKS5 endpoint, interpreted on this device. No shell/PTY. */
class JschTransport(private val credentials: CredentialStore, private val trust: HostTrust,
                    private val history: ConnectionHistory) : SshTransport {
    override suspend fun open(profile: Profile, network: NetworkLease): SshConnection = suspendCancellableCoroutine { continuation ->
        val owner = OwnedSession()
        continuation.invokeOnCancellation { owner.close() }
        workers.execute {
            var challengePending = false
            try {
                val jsch = JSch()
                credentials.read(profile.id).use { secret ->
                    try { jsch.addIdentity(profile.id, secret.privateKey, null, secret.passphrase) }
                    catch (_: Exception) { throw TunnelFailure("Chave privada incompatível ou senha incorreta. Importe PEM/OpenSSH RSA, ECDSA ou Ed25519 suportada pelo JSch.", false) }
                }
                jsch.setHostKeyRepository(object : HostKeyRepository {
                    override fun check(host: String, key: ByteArray): Int {
                        val algorithm = HostKey(host, key).type
                        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                        val challenge = HostChallenge(profile.host, profile.port, algorithm, fingerprint, Base64.getEncoder().encodeToString(key))
                        val accepted = runBlocking { trust.verify(challenge) }
                        challengePending = !accepted
                        return if (accepted) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
                    }
                    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
                    override fun remove(host: String?, type: String?) {}
                    override fun remove(host: String?, type: String?, key: ByteArray?) {}
                    override fun getKnownHostsRepositoryID() = "Android Keystore"
                    override fun getHostKey(): Array<HostKey> = emptyArray()
                    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
                })
                val session = jsch.getSession(profile.user, profile.host.removeSurrounding("[", "]"), profile.port)
                owner.session = session
                if (owner.closed) throw CancellationException()
                session.setConfig("StrictHostKeyChecking", "yes")
                session.setConfig("PreferredAuthentications", "publickey")
                session.setSocketFactory(object : SocketFactory {
                    override fun createSocket(host: String, port: Int): Socket = network.connect(host, port, profile.timeoutSeconds * 1000).also { owner.register(it) }
                    override fun getInputStream(socket: Socket) = socket.getInputStream()
                    override fun getOutputStream(socket: Socket) = socket.getOutputStream()
                })
                session.setServerAliveInterval(profile.keepaliveSeconds * 1000)
                session.setServerAliveCountMax((profile.missedKeepalives - 1).coerceAtLeast(0))
                val deadline = deadlines.schedule({ owner.close() }, profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                try { session.connect(profile.timeoutSeconds * 1000) } finally { deadline.cancel(false) }
                if (owner.closed) { session.disconnect(); throw TunnelFailure("Tempo limite na conexão/negociação inicial SSH.") }
                // want_reply=true; JSch bounds the global request wait to approximately 10 s.
                session.setPortForwardingR("127.0.0.1", profile.remotePort, SocksDaemon::class.java.name,
                    arrayOf(Socks5({ host, port -> network.connect(host, port, profile.timeoutSeconds * 1000) },
                        history, network.kind, owner::register, owner::unregister), owner))
                jsch.removeAllIdentity()
                continuation.resume(owner) { _, connection, _ -> connection.close() }
            } catch (error: Exception) {
                owner.close()
                if (continuation.isActive) {
                    val internal = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<TunnelFailure>().firstOrNull()
                    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                    val routeFailure = causes.any { it is NoRouteToHostException || (it is SocketException && (it.message.orEmpty().contains("ENETUNREACH") || it.message.orEmpty().contains("Network is unreachable", true))) }
                    val message = error.message.orEmpty()
                    val translated = when {
                        challengePending -> TunnelFailure("Confirme a impressão digital do servidor e conecte novamente.", false)
                        internal != null -> internal
                        routeFailure -> TunnelFailure("Sem rota ${if (profile.host.contains(':')) "IPv6 " else ""}até o servidor SSH na rede selecionada.")
                        causes.any { it is UnknownHostException } -> TunnelFailure("Falha de DNS do host SSH na rede selecionada.")
                        causes.any { it is ConnectException } -> TunnelFailure("Conexão TCP recusada pelo servidor SSH.")
                        causes.any { it is SocketTimeoutException } -> TunnelFailure("Tempo limite ao alcançar ou negociar com o servidor SSH na rede selecionada.")
                        message.contains("Auth fail", true) -> TunnelFailure("Autenticação recusada. Confira usuário, chave e senha.", false)
                        message.contains("remote port forwarding", true) -> TunnelFailure("Encaminhamento remoto recusado: porta ocupada, sem permissão ou sem resposta do servidor.", false)
                        else -> TunnelFailure("Falha de SSH/rota na rede selecionada: ${error.javaClass.simpleName}.")
                    }
                    continuation.resumeWithException(translated)
                }
            }
        }
    }
    companion object { private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ssh-deadline").apply { isDaemon = true } }; private val workers = Executors.newCachedThreadPool { r -> Thread(r, "ssh-open").apply { isDaemon = true } } }
}
class OwnedSession : SshConnection {
    @Volatile var session: Session? = null
    @Volatile var closed = false
    private val resources = ConcurrentHashMap.newKeySet<Closeable>()
    override val alive get() = !closed && session?.isConnected == true
    @Synchronized fun register(resource: Closeable) { if (closed) resource.close() else resources.add(resource) }
    fun unregister(resource: Closeable) { resources.remove(resource) }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        resources.forEach { runCatching { it.close() } }; resources.clear()
        session?.disconnect()
    }
}
/** JSch instantiates this per incoming forwarded-tcpip channel. Keep the public no-arg constructor. */
class SocksDaemon : ForwardedTCPIPDaemon {
    private lateinit var channel: ChannelForwardedTCPIP
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var socks: Socks5
    private lateinit var owner: OwnedSession
    private lateinit var resource: Closeable
    override fun setChannel(channel: ChannelForwardedTCPIP, input: InputStream, output: OutputStream) {
        this.channel = channel; this.input = input; this.output = channel.outputStream
        resource = Closeable { channel.disconnect(); runCatching { input.close() }; runCatching { this.output.close() }; runCatching { output.close() } }
    }
    override fun setArg(args: Array<out Any>) { socks = args[0] as Socks5; owner = args[1] as OwnedSession; owner.register(resource) }
    override fun run() { try { socks.serve(input, output) { resource.close() } } finally { owner.unregister(resource) } }
}
