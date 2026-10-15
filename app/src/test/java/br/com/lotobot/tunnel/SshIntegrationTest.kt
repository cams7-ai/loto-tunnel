package br.com.lotobot.tunnel

import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import br.com.lotobot.tunnel.infrastructure.JschTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.*
import java.net.*
import java.util.concurrent.*

/** Opt-in real OpenSSH test; uses disposable keys and loopback, never an EC2 credential. */
class SshIntegrationTest {
    @Test fun realRemoteForwardNegotiationSocksTrafficPortCollisionAndCleanup() = runBlocking {
        val keyPath = System.getenv("LOTO_TEST_SSH_KEY")
        assumeTrue("Run scripts/test-ssh-local.sh to enable real SSH integration", !keyPath.isNullOrBlank())
        val credentials = object : CredentialStore {
            override fun read(id: String) = Credential(File(keyPath!!).readBytes(), null)
            override fun write(id: String, key: ByteArray, passphrase: ByteArray?) {}
            override fun validateAndWrite(id: String, key: ByteArray, passphrase: ByteArray?) {}
            override fun delete(id: String) {}
        }
        var trusted = false
        val trust = object : HostTrust {
            override val pending = MutableStateFlow<HostChallenge?>(null)
            override suspend fun verify(challenge: HostChallenge): Boolean { if (!trusted) pending.value = challenge; return trusted }
            override fun answer(accept: Boolean) { trusted = accept; pending.value = null }
        }
        val openedDestinations = CopyOnWriteArrayList<String>()
        val network = object : NetworkLease {
            override val id = "local-test"; override val kind = NetworkKind.WIFI; override val label = "Loopback JVM"
            override fun connect(host: String, port: Int, timeoutMillis: Int): Socket {
                openedDestinations.add("$host:$port")
                return Socket().apply { connect(InetSocketAddress(host, port), timeoutMillis) }
            }
            override fun publicIpv6(): String = error("Direct IP lookup not part of SOCKS test")
        }
        val h = MemoryHistory(); val transport = JschTransport(credentials, trust, h)
        val profile = Profile(host = "127.0.0.1", port = 22222, remotePort = System.getenv("LOTO_TEST_SOCKS_PORT")?.toIntOrNull() ?: 21081, user = System.getProperty("user.name") ?: "tester", keyImported = true)
        try { transport.open(profile, network); fail("First host must require confirmation") }
        catch (e: TunnelFailure) { assertFalse(e.retryable); assertNotNull(trust.pending.value) }
        trust.answer(true)
        transport.open(profile, network).use { ssh ->
            assertTrue(ssh.alive)
            try { transport.open(profile, network); fail("Occupied remote listener must fail") }
            catch (e: TunnelFailure) { assertFalse(e.retryable); assertTrue(e.message!!.contains("Encaminhamento")) }
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { target ->
                val echo = CompletableFuture.runAsync { target.accept().use { s ->
                    val payload = s.getInputStream().readNBytes(4)
                    s.getOutputStream().write(payload.reversedArray()); s.getOutputStream().flush()
                    s.getInputStream().read() // wait for client EOF before closing
                } }
                Socket("127.0.0.1", profile.remotePort).use { client ->
                    client.soTimeout = 5000
                    val input = DataInputStream(client.getInputStream()); val output = client.getOutputStream()
                    byteArrayOf(5,1,0).forEach { output.write(it.toInt()); output.flush() }
                    assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                    val host = "localhost".toByteArray()
                    val request = byteArrayOf(5,1,0,3,host.size.toByte()) + host + byteArrayOf((target.localPort ushr 8).toByte(), target.localPort.toByte())
                    request.forEach { output.write(it.toInt()); output.flush() }
                    assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte()); input.readUnsignedByte()
                    val type = input.readUnsignedByte(); input.readNBytes(if (type == 4) 16 else 4); input.readUnsignedShort()
                    output.write(byteArrayOf(1,2,3,4)); output.flush()
                    assertArrayEquals(byteArrayOf(4,3,2,1), input.readNBytes(4))
                    client.shutdownOutput(); assertEquals(-1, input.read())
                }
                echo.get(5, TimeUnit.SECONDS)
            }
            withTimeout(5000) { while (h.state.value.connections.any { it.endedAt == null }) delay(20) }
            val row = h.state.value.connections.single()
            assertEquals("localhost", row.destination); assertEquals(4L, row.sent); assertEquals(4L, row.received)
            assertTrue(openedDestinations.any { it.startsWith("localhost:") })
            // A new in-progress request must be closed together with the SSH session.
            Socket("127.0.0.1", profile.remotePort).use { client ->
                client.soTimeout = 5000; client.getOutputStream().write(byteArrayOf(5,1,0)); client.getOutputStream().flush()
                assertEquals(5, client.getInputStream().read()); assertEquals(0, client.getInputStream().read())
                ssh.close()
                assertEquals(-1, client.getInputStream().read())
            }
        }
        transport.open(profile, network).use { assertTrue(it.alive) } // listener released
    }
}
