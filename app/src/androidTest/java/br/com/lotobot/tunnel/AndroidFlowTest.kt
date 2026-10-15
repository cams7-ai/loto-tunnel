package br.com.lotobot.tunnel

import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import br.com.lotobot.tunnel.di.TunnelApp
import br.com.lotobot.tunnel.domain.*
import br.com.lotobot.tunnel.presentation.MainActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import br.com.lotobot.tunnel.infrastructure.Ipv6

/** Opt-in emulator integration against scripts/test-android-local.sh, no fabricated session. */
@RunWith(AndroidJUnit4::class)
class AndroidFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as TunnelApp
    private val graph get() = app.graph
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        try { withTimeout(30000) { while (!condition()) delay(100) } }
        catch (e: TimeoutCancellationException) {
            screenshot("debug-timeout.png")
            throw AssertionError("Timeout: state=${graph.control.state.value}, pending=${graph.trust.pending.value != null}, events=${graph.history.state.value.events.take(3)}", e)
        }
    }
    private fun click(text: String) { val item = device.wait(Until.findObject(By.text(text)), 10000); assertNotNull("Missing UI: $text", item); item.click(); device.waitForIdle() }
    private fun screenshot(name: String) {
        device.waitForIdle(); Thread.sleep(300)
        val directory = File(app.getExternalFilesDir(null), "capturas").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        device.executeShellCommand("mkdir -p /sdcard/Download/loto-capturas")
        device.executeShellCommand("cp ${File(directory, name).absolutePath} /sdcard/Download/loto-capturas/$name")
    }
    @Test fun realAndroidSshSocksAndScreens() {
        Assume.assumeTrue(instrumentation.context.assets.list("")!!.contains("ssh-test.pem"))
        device.executeShellCommand("rm -rf /sdcard/Download/loto-capturas")
        val key = instrumentation.context.assets.open("ssh-test.pem").use { it.readBytes() }
        val profile = Profile(id = "emulator-test", name = "OpenSSH local • teste", host = "10.0.2.2", port = 22222,
            user = InstrumentationRegistry.getArguments().getString("localSshUser") ?: "tester", remotePort = InstrumentationRegistry.getArguments().getString("localSocksPort")?.toIntOrNull() ?: 21081, keyImported = true)
        try { graph.vault.validateAndWrite(profile.id, key, null) } finally { key.fill(0) }
        graph.profiles.save(profile)
        device.executeShellCommand("pm grant br.com.lotobot.tunnel android.permission.POST_NOTIFICATIONS")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
        graph.control.connect()
        waitFor { graph.trust.pending.value != null || graph.control.state.value.status == TunnelStatus.CONNECTED || graph.control.state.value.status == TunnelStatus.ERROR }
        if (graph.trust.pending.value != null) { click("Confiar e conectar") }
        waitFor { graph.control.state.value.status == TunnelStatus.CONNECTED }
        assertEquals(graph.control.state.value.lastFailure, TunnelStatus.CONNECTED, graph.control.state.value.status)
        device.sleep(); Thread.sleep(2000); assertEquals(TunnelStatus.CONNECTED, graph.control.state.value.status); device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.waitForIdle()
        screenshot("01-conexao.png")
        Socket("10.0.2.2", profile.remotePort).use { proxy ->
            proxy.soTimeout = 10000
            val input = DataInputStream(proxy.getInputStream()); val out = proxy.getOutputStream()
            out.write(byteArrayOf(5,1,0)); out.flush(); assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
            val host = "10.0.2.2".toByteArray()
            out.write(byteArrayOf(5,1,0,3,host.size.toByte()) + host + byteArrayOf((22223 ushr 8).toByte(), 22223.toByte())); out.flush()
            assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte()); input.readUnsignedByte()
            input.readNBytes(if (input.readUnsignedByte() == 4) 16 else 4); input.readUnsignedShort()
            out.write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray()); out.flush(); proxy.shutdownOutput()
            val response = input.readBytes().toString(Charsets.US_ASCII); assertTrue(response.contains("LOTO_ANDROID_TEST"))
        }
        // Compare the real public IPv6 seen directly and through the remote SOCKS listener.
        val direct = graph.control.state.value.ipv6.address
        if (direct != null) {
            Socket("10.0.2.2", profile.remotePort).use { proxy ->
                proxy.soTimeout = 15000
                val input = DataInputStream(proxy.getInputStream()); val out = proxy.getOutputStream()
                out.write(byteArrayOf(5,1,0)); out.flush(); assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                val host = "api6.ipify.org".toByteArray()
                out.write(byteArrayOf(5,1,0,3,host.size.toByte()) + host + byteArrayOf(1, -69)); out.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte()); input.readUnsignedByte()
                input.readNBytes(if (input.readUnsignedByte() == 4) 16 else 4); input.readUnsignedShort()
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(proxy, "api6.ipify.org", 443, true) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls.getOutputStream().write("GET / HTTP/1.0\r\nHost: api6.ipify.org\r\nConnection: close\r\n\r\n".toByteArray()); tls.getOutputStream().flush()
                    val response = tls.getInputStream().readBytes().toString(Charsets.US_ASCII)
                    assertTrue(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"))
                    val remoteIpv6 = response.substringAfter("\r\n\r\n").trim()
                    assertEquals("Direct and SOCKS IPv6 must match", Ipv6.normalize(direct), Ipv6.normalize(remoteIpv6))
                    val file = File(app.getExternalFilesDir(null), "ipv6-validation.txt")
                    file.writeText("Android emulator Wi-Fi, local OpenSSH remote listener\nDirect: $direct\nSOCKS: $remoteIpv6\nNormalized equality: PASS\n")
                    device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/loto-capturas/ipv6-validation.txt")
                }
            }
        }
        if (direct != null) {
            var copyAction = device.findObject(By.text("Copiar IPv6"))
            repeat(8) {
                if (copyAction == null || copyAction!!.visibleBounds.bottom > device.displayHeight * 8 / 10) {
                    device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 20)
                    Thread.sleep(200); copyAction = device.findObject(By.text("Copiar IPv6"))
                }
            }
            assertNotNull(copyAction); copyAction!!.click(); device.waitForIdle()
            instrumentation.runOnMainSync {
                assertEquals(direct, app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            }
            val previousQuery = graph.control.state.value.ipv6.updatedAt ?: 0
            click("Atualizar IPv6")
            waitFor { (graph.control.state.value.ipv6.updatedAt ?: 0) > previousQuery && !graph.control.state.value.ipv6.stale }
        }
        waitFor { graph.history.state.value.connections.any { it.destination == "10.0.2.2" && it.endedAt != null } }
        val usage = graph.history.state.value.connections.first { it.destination == "10.0.2.2" }
        assertTrue(usage.sent > 0 && usage.received > 0); assertEquals(NetworkKind.WIFI, usage.network)
        click("Logs"); screenshot("04-uso-socks5.png")
        click("Túnel SSH"); screenshot("05-eventos-ssh.png")
        click("Ajustes"); screenshot("02-perfil-ssh.png")
        click("Configuração: Perfil SSH"); click("Rede"); screenshot("03-rede.png")
        // Recreating the activity does not own/disconnect the SSH session.
        device.pressHome(); Thread.sleep(500); app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); device.waitForIdle()
        assertEquals(TunnelStatus.CONNECTED, graph.control.state.value.status)
        click("Conexão")
        device.executeShellCommand("svc wifi disable")
        waitFor { graph.control.state.value.status == TunnelStatus.WAITING }
        screenshot("06-aguardando-rede.png")
        device.executeShellCommand("wm size 720x1280")
        device.executeShellCommand("wm density 320")
        device.executeShellCommand("settings put system font_scale 1.5")
        Thread.sleep(1500); screenshot("07-tela-pequena-fonte-ampliada.png")
        // Essential action is reached by vertical scrolling at enlarged font size.
        var cancel = device.findObject(By.text("Cancelar reconexão"))
        repeat(15) {
            if (cancel == null) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 8 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 30)
                Thread.sleep(200)
                cancel = device.findObject(By.text("Cancelar reconexão"))
            }
        }
        screenshot("08-acao-fonte-ampliada.png")
        assertNotNull("Cancel must be reachable with large fonts", cancel)
        cancel?.click(); waitFor { graph.control.state.value.status == TunnelStatus.DISCONNECTED }
    }
    @After fun cleanup() {
        graph.control.disconnect()
        device.executeShellCommand("svc wifi enable")
        device.executeShellCommand("wm size reset"); device.executeShellCommand("wm density reset")
        device.executeShellCommand("settings put system font_scale 1.0")
    }
}
