package br.com.lotobot.tunnel

import br.com.lotobot.tunnel.domain.*
import br.com.lotobot.tunnel.infrastructure.*
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.util.concurrent.*

class ProtocolTest {
    @Test fun ipv6NormalizationAndInvalidAddresses() {
        assertEquals(Ipv6.normalize("2606:4700:4700::1111"), Ipv6.normalize("2606:4700:4700:0:0:0:0:1111"))
        listOf("127.0.0.1", "::", "::1", "fe80::1", "fc00::1", "ff02::1", "not-an-address").forEach { value -> assertThrows(IllegalArgumentException::class.java) { Ipv6.normalize(value) } }
    }
    @Test fun networkModesAndHysteresis() {
        val candidates = listOf("w" to NetworkKind.WIFI, "m" to NetworkKind.MOBILE)
        assertEquals("w", NetworkPolicy.choose(NetworkMode.WIFI, candidates, null, false))
        assertEquals("m", NetworkPolicy.choose(NetworkMode.MOBILE, candidates, null, false))
        assertNull(NetworkPolicy.choose(NetworkMode.WIFI, listOf("m" to NetworkKind.MOBILE), null, true))
        assertEquals("m", NetworkPolicy.choose(NetworkMode.AUTO, candidates, "m", false))
        assertEquals("w", NetworkPolicy.choose(NetworkMode.AUTO, candidates, "m", true))
        assertEquals("m", NetworkPolicy.choose(NetworkMode.AUTO, listOf("m" to NetworkKind.MOBILE), null, true))
    }
    @Test fun reconnectIsBoundedAndProfileValidated() {
        assertEquals(listOf(1000L,2000L,4000L,8000L,16000L,32000L,60000L,60000L), (0..7).map(ReconnectPolicy::delayMillis))
        assertNotNull(Profile().validate()); assertNull(Profile(host = "::1", keyImported = true).validate())
        assertNotNull(Profile(host = "::1", keyImported = true, remotePort = 65536).validate())
    }
    private fun request(type: Int, address: ByteArray, command: Int = 1) = byteArrayOf(5,1,0,5,command.toByte(),0,type.toByte()) + (if (type == 3) byteArrayOf(address.size.toByte()) else byteArrayOf()) + address + byteArrayOf(0,80)
    private fun fragmented(bytes: ByteArray) = object : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, offset: Int, length: Int) = super.read(b, offset, length.coerceAtMost(1))
    }
    @Test fun rejectsAuthenticationCommandsAndAddressTypes() {
        val cases = listOf(byteArrayOf(5,1,2) to 255, request(1, byteArrayOf(127,0,0,1), 2) to 7, request(9, byteArrayOf()) to 8)
        cases.forEach { (bytes, expected) ->
            val result = ByteArrayOutputStream()
            Socks5({ _, _ -> error("Should not open destination") }, MemoryHistory(), NetworkKind.WIFI).serve(fragmented(bytes), result) {}
            val reply = result.toByteArray(); assertEquals(expected, reply[if (expected == 255) 1 else 3].toInt() and 255)
        }
    }
    @Test fun domainIpv4Ipv6AndPayloadAccountingWithFragmentedReads() {
        val cases = listOf(3 to "example.test".toByteArray(), 1 to byteArrayOf(127,0,0,1), 4 to InetAddress.getByName("::1").address)
        cases.forEach { (type, address) ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val echo = CompletableFuture.runAsync { server.accept().use { socket ->
                    val request = socket.getInputStream().readBytes(); socket.getOutputStream().write(request.reversedArray()); socket.shutdownOutput()
                } }
                val h = MemoryHistory(); val output = ByteArrayOutputStream(); val payload = "payload-data".toByteArray()
                var requested: String? = null
                Socks5({ host, port -> requested = host; assertEquals(80, port); Socket(InetAddress.getLoopbackAddress(), server.localPort) }, h, NetworkKind.MOBILE)
                    .serve(fragmented(request(type, address) + payload), output) {}
                echo.get(3, TimeUnit.SECONDS)
                val reply = output.toByteArray(); assertEquals(0, reply[3].toInt())
                assertArrayEquals(payload.reversedArray(), reply.copyOfRange(12, reply.size))
                val row = h.state.value.connections.single()
                assertEquals(payload.size.toLong(), row.sent); assertEquals(payload.size.toLong(), row.received)
                assertEquals(ConnectionState.CLOSED, row.state); assertEquals(NetworkKind.MOBILE, row.network)
                if (type == 3) assertEquals("example.test", requested)
            }
        }
    }
    @Test fun failuresReturnCorrectSocksReplies() {
        listOf(UnknownHostException() to 4, NoRouteToHostException() to 3, ConnectException() to 5, SocketTimeoutException() to 6).forEach { (error, code) ->
            val out = ByteArrayOutputStream(); val h = MemoryHistory()
            Socks5({ _, _ -> throw error }, h, NetworkKind.WIFI).serve(fragmented(request(3, "example.test".toByteArray())), out) {}
            assertEquals(code, out.toByteArray()[3].toInt()); assertEquals(ConnectionState.ERROR, h.state.value.connections.single().state)
        }
    }
    @Test fun truncatedHandshakeDoesNotOpenDestination() {
        val out = ByteArrayOutputStream()
        Socks5({ _, _ -> error("Should not open") }, MemoryHistory(), NetworkKind.WIFI).serve(fragmented(byteArrayOf(5,1)), out) {}
        assertTrue(out.toByteArray().isNotEmpty())
    }
}
