package br.com.lotobot.tunnel

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.lotobot.tunnel.domain.*
import br.com.lotobot.tunnel.infrastructure.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun keystoreRoundTripDeletionHistoryBoundsTotalsAndProcessRecovery() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("storage-test-$name", mode)
        }
        context.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear().commit()
        val vault = Vault(context)
        val key = "disposable-storage-test-value".toByteArray(); val password = "test-only".toByteArray()
        vault.write("test", key, password)
        val firstCiphertext = context.getSharedPreferences("vault", 0).getString("key-test", null)
        vault.write("test", key, password)
        assertNotEquals(firstCiphertext, context.getSharedPreferences("vault", 0).getString("key-test", null))
        Vault(context).read("test").use { assertArrayEquals(key, it.privateKey); assertArrayEquals(password, it.passphrase) }
        vault.delete("test"); assertThrows(IllegalStateException::class.java) { vault.read("test") }
        assertThrows(IllegalArgumentException::class.java) { vault.validateAndWrite("invalid", key, null) }
        key.fill(0); password.fill(0)
        val challenge = HostChallenge("test.invalid", 22, "ssh-ed25519", "SHA256:disposable-test", "disposable-test-key")
        val trust = TrustStore(vault)
        assertFalse(trust.verify(challenge)); assertEquals(challenge, trust.pending.value)
        trust.answer(true); assertTrue(TrustStore(vault).verify(challenge))
        val changed = assertThrows(TunnelFailure::class.java) { runBlocking { TrustStore(vault).verify(challenge.copy(key = "different-test-key")) } }
        assertFalse(changed.retryable)
        val profiles = Profiles(context, vault)
        val profile = Profile(id = "storage-test", host = "test.invalid", mode = NetworkMode.MOBILE, remotePort = 1081)
        profiles.save(profile); profiles.resumeOnBoot = true
        val recoveredProfiles = Profiles(context, vault)
        assertEquals(profile, recoveredProfiles.selected.value); assertTrue(recoveredProfiles.resumeOnBoot)
        recoveredProfiles.delete(profile.id); assertFalse(recoveredProfiles.profiles.value.any { it.id == profile.id })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val h = History(vault, scope)
            val wifi = h.begin(NetworkKind.WIFI, "test.invalid", 80); h.bytes(wifi, 11, 12); h.end(wifi)
            val mobile = h.begin(NetworkKind.MOBILE, "test.invalid", 443); h.bytes(mobile, 13, 14); h.end(mobile)
            repeat(1005) { val id = h.begin(NetworkKind.WIFI, "test.invalid", 80); h.end(id) }
            val active = h.begin(NetworkKind.WIFI, "active.invalid", 443); h.connected(active)
            repeat(505) { h.event("Test event $it") }
            withTimeout(10000) { while (h.state.value.connections.size != 1000 || h.state.value.events.size != 500 || vault.get("history") == null) delay(100) }
            assertEquals(Traffic(11,12), h.state.value.wifi); assertEquals(Traffic(13,14), h.state.value.mobile)
            val restored = History(Vault(context), scope)
            val recovered = restored.state.value.connections.first { it.id == active }
            assertEquals(ConnectionState.ERROR, recovered.state); assertNotNull(recovered.endedAt)
            assertEquals("Processo anterior encerrado.", recovered.failure)
            h.clear(); assertEquals(listOf(active), h.state.value.connections.map { it.id }); assertTrue(h.state.value.events.isEmpty())
        } finally { scope.cancel(); context.getSharedPreferences("vault", 0).edit().clear().commit() }
    }
}
