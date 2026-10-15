package br.com.lotobot.tunnel

import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTest {
    private class Lease(override val id: String, override val kind: NetworkKind) : NetworkLease {
        override val label = kind.label
        override fun connect(host: String, port: Int, timeoutMillis: Int): java.net.Socket = error("Not used")
        override fun publicIpv6() = "2606:4700:4700::1111"
    }
    private class Networks : NetworkAccess {
        override val networks = MutableStateFlow<List<NetworkLease>>(listOf(Lease("w", NetworkKind.WIFI)))
        override fun close() {}
    }
    private class Transport : SshTransport {
        var holdRecovery = false; var recoveries = 0; var recoveryFailure: Exception? = null
        var opens = 0; var active = 0; var maxActive = 0; var failure: Exception? = null; var hold = false
        override suspend fun recoverAndOpen(profile: Profile, network: NetworkLease): SshConnection {
            recoveries++; if (holdRecovery) awaitCancellation(); recoveryFailure?.let { throw it }; failure = null
            return open(profile, network)
        }
        override suspend fun open(profile: Profile, network: NetworkLease): SshConnection {
            opens++; failure?.let { throw it }; if (hold) awaitCancellation()
            active++; maxActive = maxOf(maxActive, active)
            return object : SshConnection { var closed = false; override val alive get() = !closed
                override fun close() { if (!closed) { closed = true; active-- } } }
        }
    }
    private fun TestScope.coordinator(ssh: Transport, net: Networks, profiles: MemoryProfiles = MemoryProfiles()) = TunnelCoordinator(backgroundScope, ssh, net, profiles, MemoryHistory(), object : Timing {
        override fun now() = testScheduler.currentTime
        override suspend fun waitMillis(millis: Long) = delay(millis)
    })
    @Test fun recoveryRequiresForwardingFailureAndRunsOnlyOnce() = runTest {
        val ssh = Transport(); val c = coordinator(ssh, Networks())
        c.recover(); runCurrent(); assertEquals(0, ssh.recoveries)
        ssh.failure = TunnelFailure("forward denied", false, forwardingRejected = true)
        c.connect(); runCurrent(); assertTrue(c.state.value.forwardingRejected)
        repeat(3) { c.recover() }; runCurrent()
        assertEquals(1, ssh.recoveries); assertEquals(TunnelStatus.CONNECTED, c.state.value.status)
        assertFalse(c.state.value.forwardingRejected)
        c.disconnect(); runCurrent()
    }
    @Test fun disconnectCancelsRecoveryWithoutReconnecting() = runTest {
        val ssh = Transport().apply {
            failure = TunnelFailure("forward denied", false, forwardingRejected = true)
            holdRecovery = true
        }
        val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); c.recover(); runCurrent()
        c.disconnect(); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertEquals(1, ssh.recoveries); assertEquals(1, ssh.opens)
        assertEquals(TunnelStatus.DISCONNECTED, c.state.value.status)
    }
    @Test fun recoveryFailureIsVisibleAndDoesNotRepeatCommand() = runTest {
        val ssh = Transport().apply {
            failure = TunnelFailure("forward denied", false, forwardingRejected = true)
            recoveryFailure = TunnelFailure("sudo refused", false)
        }
        val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); c.recover(); runCurrent()
        advanceTimeBy(120000); runCurrent()
        assertEquals(1, ssh.recoveries); assertEquals("sudo refused", c.state.value.lastFailure)
        assertEquals(TunnelStatus.ERROR, c.state.value.status)
    }
    @Test fun repeatedConnectAndManualDisconnectDoNotDuplicateOrRestart() = runTest {
        val ssh = Transport(); val net = Networks(); val c = coordinator(ssh, net)
        repeat(5) { c.connect() }; runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status); assertEquals(1, ssh.opens)
        c.disconnect(); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertEquals(0, ssh.active); assertEquals(1, ssh.opens); assertEquals(TunnelStatus.DISCONNECTED, c.state.value.status)
    }
    @Test fun reconnectCancelledDuringBackoff() = runTest {
        val ssh = Transport().apply { failure = TunnelFailure("offline") }; val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); assertEquals(TunnelStatus.RECONNECTING, c.state.value.status)
        c.disconnect(); runCurrent(); advanceTimeBy(120000); runCurrent(); assertEquals(1, ssh.opens)
    }
    @Test fun networkChangeClosesOldSessionAndWaitsForPermittedNetwork() = runTest {
        val ssh = Transport(); val net = Networks(); val c = coordinator(ssh, net)
        c.connect(); runCurrent(); assertEquals(1, ssh.active)
        net.networks.value = listOf(Lease("m", NetworkKind.MOBILE)); advanceTimeBy(1001); runCurrent()
        assertEquals(TunnelStatus.WAITING, c.state.value.status); assertEquals(0, ssh.active)
        net.networks.value = listOf(Lease("w2", NetworkKind.WIFI)); advanceTimeBy(1001); runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status); assertEquals("w2", c.state.value.networkId); assertEquals(1, ssh.maxActive)
    }
    @Test fun profileChangeCancelsHandshakeBeforeStartingNewSession() = runTest {
        val ssh = Transport().apply { hold = true }; val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); assertEquals(TunnelStatus.CONNECTING, c.state.value.status)
        ssh.hold = false; c.changeProfile(Profile(host = "::1", keyImported = true, remotePort = 1081)); runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status); assertEquals(1, ssh.maxActive); assertEquals(2, ssh.opens)
    }
    @Test fun explicitRetryCancelsPendingHandshakeAndCreatesExactlyOneSession() = runTest {
        val ssh = Transport().apply { hold = true }; val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); assertEquals(1, ssh.opens)
        ssh.hold = false; c.retry(); runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status); assertEquals(2, ssh.opens); assertEquals(1, ssh.maxActive)
        c.disconnect(); runCurrent(); assertEquals(0, ssh.active)
    }
    @Test fun permanentErrorsDoNotRetry() = runTest {
        val ssh = Transport().apply { failure = TunnelFailure("authentication", false) }; val c = coordinator(ssh, Networks())
        c.connect(); runCurrent(); advanceTimeBy(120000); runCurrent(); assertEquals(TunnelStatus.ERROR, c.state.value.status); assertEquals(1, ssh.opens)
    }
    @Test fun forwardingFailureDoesNotCancelIndependentIpv6Diagnostic() = runTest {
        val ssh = Transport().apply { failure = TunnelFailure("Porta 1080 ocupada", false) }
        val c = coordinator(ssh, Networks())
        c.connect(); runCurrent()
        assertEquals(TunnelStatus.ERROR, c.state.value.status)
        val snapshot = withContext(Dispatchers.Default) {
            withTimeout(5000) { c.state.first { it.ipv6.address != null && !it.ipv6.stale } }
        }
        assertEquals("2606:4700:4700::1111", snapshot.ipv6.address)
        assertEquals("Porta 1080 ocupada", snapshot.lastFailure)
        ssh.failure = null
        c.connect(); runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status)
        assertNull(c.state.value.lastFailure)
        c.disconnect(); runCurrent()
    }
    @Test fun mobileModeSelectsMobileEvenWhenWifiIsAvailable() = runTest {
        val ssh = Transport(); val net = Networks()
        net.networks.value = listOf(Lease("w", NetworkKind.WIFI), Lease("m", NetworkKind.MOBILE))
        val c = coordinator(ssh, net)
        c.changeProfile(Profile(host = "::1", keyImported = true, mode = NetworkMode.MOBILE)); runCurrent()
        c.connect(); runCurrent()
        assertEquals(TunnelStatus.CONNECTED, c.state.value.status)
        assertEquals(NetworkKind.MOBILE, c.state.value.network)
        assertEquals("m", c.state.value.networkId)
        c.disconnect(); runCurrent()
    }
}
