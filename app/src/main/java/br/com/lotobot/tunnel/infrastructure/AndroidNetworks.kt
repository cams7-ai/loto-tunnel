package br.com.lotobot.tunnel.infrastructure

import android.content.Context
import android.net.*
import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.util.concurrent.*
import javax.net.ssl.HttpsURLConnection

class AndroidNetworks(context: Context) : NetworkAccess {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow<List<NetworkLease>>(emptyList())
    override val networks = mutable.asStateFlow()
    private val seen = linkedMapOf<Network, NetworkCapabilities>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { synchronized(seen) { seen[network] = capabilities; publish() } }
        override fun onLost(network: Network) { synchronized(seen) { seen.remove(network); publish() } }
    }
    // Keep cellular available for explicit cellular mode and automatic fallback. No process-wide binding.
    private val mobileCallback = object : ConnectivityManager.NetworkCallback() {}
    init {
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        manager.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), mobileCallback)
        manager.allNetworks.forEach { n -> manager.getNetworkCapabilities(n)?.let { seen[n] = it } }
        publish()
    }
    private fun publish() {
        mutable.value = seen.mapNotNull { (n, c) ->
            val kind = when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
                else -> null
            }
            if (kind == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) null else Lease(n, kind)
        }
    }
    override fun close() { runCatching { manager.unregisterNetworkCallback(callback) }; runCatching { manager.unregisterNetworkCallback(mobileCallback) }; mutable.value = emptyList() }
    class Lease(private val network: Network, override val kind: NetworkKind) : NetworkLease {
        override val id = network.networkHandle.toString()
        override val label = kind.label
        override fun connect(host: String, port: Int, timeoutMillis: Int): Socket {
            val start = System.nanoTime()
            val resolve = dns.submit< Array<InetAddress> > { network.getAllByName(host.removeSurrounding("[", "]")) }
            val addresses = try { resolve.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { resolve.cancel(true); throw SocketTimeoutException("Tempo limite de DNS na rede selecionada.") }
                catch (e: ExecutionException) { throw (e.cause as? Exception ?: e) }
                catch (e: InterruptedException) { resolve.cancel(true); throw e }
            var failure: Exception = NoRouteToHostException("Nenhuma rota na rede selecionada.")
            for (address in addresses) {
                val remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).toInt()
                if (remaining <= 0) throw SocketTimeoutException("Tempo limite na rede selecionada.")
                val socket = network.socketFactory.createSocket()
                try { socket.connect(InetSocketAddress(address, port), remaining); return socket }
                catch (e: Exception) { socket.close(); failure = e }
            }
            throw failure
        }
        override fun publicIpv6(): String {
            val connection = network.openConnection(URL("https://api6.ipify.org")) as HttpsURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            try {
                require(connection.responseCode == 200)
                val result = connection.inputStream.use { input -> input.readNBytes(128).toString(Charsets.US_ASCII).trim() }
                return Ipv6.validate(result)
            } finally { connection.disconnect() }
        }
        companion object { private val dns = Executors.newFixedThreadPool(4) { r -> Thread(r, "network-dns").apply { isDaemon = true } } }
    }
}
