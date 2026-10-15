package br.com.lotobot.tunnel.infrastructure

import br.com.lotobot.tunnel.application.ConnectionHistory
import br.com.lotobot.tunnel.domain.NetworkKind
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** No Android or SSH dependency. Processes a single RFC 1928 stream. */
class Socks5(
    private val open: (String, Int) -> Socket,
    private val history: ConnectionHistory,
    private val kind: NetworkKind,
    private val register: (Closeable) -> Unit = {},
    private val unregister: (Closeable) -> Unit = {}
) {
    fun serve(input: InputStream, output: OutputStream, closeChannel: () -> Unit) {
        var socket: Socket? = null
        var id: String? = null
        var connected = false
        val deadline = timers.schedule({ closeChannel() }, 15, TimeUnit.SECONDS)
        try {
            val data = DataInputStream(input)
            if (data.readUnsignedByte() != 5) return
            val count = data.readUnsignedByte()
            val methods = ByteArray(count); data.readFully(methods)
            if (count == 0 || methods.none { it == 0.toByte() }) {
                output.write(byteArrayOf(5, -1)); output.flush(); return
            }
            output.write(byteArrayOf(5, 0)); output.flush()
            val version = data.readUnsignedByte()
            val command = data.readUnsignedByte()
            val reserved = data.readUnsignedByte()
            val type = data.readUnsignedByte()
            if (version != 5 || reserved != 0) { reply(output, 1); return }
            if (command != 1) { reply(output, 7); return }
            val host = when (type) {
                1 -> ByteArray(4).also { data.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                4 -> ByteArray(16).also { data.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress!! }
                3 -> {
                    val size = data.readUnsignedByte()
                    if (size == 0) { reply(output, 8); return }
                    ByteArray(size).also { data.readFully(it) }.toString(Charsets.US_ASCII)
                }
                else -> { reply(output, 8); return }
            }
            val port = data.readUnsignedShort()
            if (port == 0) { reply(output, 1); return }
            id = history.begin(kind, host, port)
            socket = open(host, port)
            register(socket)
            reply(output, 0, socket.localAddress.address, socket.localPort)
            connected = true; deadline.cancel(false)
            history.connected(id)
            val destination = socket
            val record = id
            val failed = AtomicBoolean(false)
            var transferFailure: String? = null
            val up = CompletableFuture.runAsync({
                try {
                    copy(input, destination.getOutputStream()) { history.bytes(record, it, 0) }
                    destination.shutdownOutput()
                } catch (_: Exception) { failed.set(true); destination.close(); closeChannel() }
            }, uploads)
            try {
                copy(destination.getInputStream(), output) { history.bytes(record, 0, it) }
                output.close() // SSH EOF; preserve client→destination until its EOF.
                up.get()
                if (failed.get()) transferFailure = "Transferência interrompida."
            } catch (_: Exception) {
                transferFailure = "Transferência interrompida."
                destination.close(); closeChannel()
                runCatching { up.get(2, TimeUnit.SECONDS) }
            }
            history.end(id, transferFailure)
            id = null
        } catch (failure: Exception) {
            if (!connected) runCatching { reply(output, when (failure) {
                is UnknownHostException -> 4
                is NoRouteToHostException -> 3
                is ConnectException -> 5
                is SocketTimeoutException -> 6
                else -> 1
            }) }
            id?.let { history.end(it, when (failure) {
                is UnknownHostException -> "Falha de DNS na rede selecionada."
                is ConnectException -> "Conexão recusada pelo destino."
                is SocketTimeoutException -> "Tempo limite ao conectar ao destino."
                else -> "Conexão encerrada ou rede indisponível."
            }) }
        } finally {
            deadline.cancel(false)
            socket?.let { runCatching { it.close() }; unregister(it) }
            closeChannel()
        }
    }
    private fun copy(input: InputStream, output: OutputStream, count: (Long) -> Unit) {
        val buffer = ByteArray(32768)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            output.write(buffer, 0, size); output.flush(); count(size.toLong())
        }
    }
    private fun reply(out: OutputStream, status: Int, address: ByteArray = ByteArray(4), port: Int = 0) {
        out.write(byteArrayOf(5, status.toByte(), 0, if (address.size == 16) 4 else 1))
        out.write(address); out.write(port ushr 8); out.write(port and 255); out.flush()
    }
    companion object {
        private val uploads = Executors.newCachedThreadPool { r -> Thread(r, "socks-upload").apply { isDaemon = true } }
        private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "socks-deadline").apply { isDaemon = true } }
    }
}
