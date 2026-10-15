package br.com.lotobot.tunnel.infrastructure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("loto-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("loto-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, bytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val blob = cipher.iv + cipher.doFinal(bytes)
        check(prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Falha ao salvar dados privados." }
    }
    @Synchronized fun get(name: String): ByteArray? {
        val encoded = prefs.getString(name, null) ?: return null
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))) }.doFinal(blob.copyOfRange(12, blob.size))
    }
    override fun validateAndWrite(id: String, key: ByteArray, passphrase: ByteArray?) {
        val checker = com.jcraft.jsch.JSch()
        try {
            checker.addIdentity("validation", key, null, passphrase)
            val identity = checker.identityRepository.identities.firstOrNull()
            require(identity != null && !identity.isEncrypted) { "Informe a senha da chave antes de importar." }
            write(id, key, passphrase)
        } catch (e: Exception) { throw IllegalArgumentException("Chave incompatível ou senha incorreta. Use PEM/OpenSSH RSA, ECDSA ou Ed25519 suportada.") }
        finally { checker.removeAllIdentity() }
    }
    override fun write(id: String, key: ByteArray, passphrase: ByteArray?) {
        put("key-$id", key)
        if (passphrase != null && passphrase.isNotEmpty()) put("pass-$id", passphrase) else prefs.edit().remove("pass-$id").commit()
    }
    override fun read(id: String) = Credential(get("key-$id") ?: error("Importe novamente a chave privada."), get("pass-$id"))
    override fun delete(id: String) { prefs.edit().remove("key-$id").remove("pass-$id").commit() }
}
class Profiles(context: Context, private val credentials: CredentialStore) : ProfileRepository {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private fun decode(o: JSONObject) = Profile(o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("port", 22), o.optString("user", "ubuntu"), o.optInt("remotePort", 1080), NetworkMode.valueOf(o.optString("mode", "WIFI")), o.optBoolean("keyImported"), o.optInt("timeout", 15), o.optInt("keepalive", 30), o.optInt("missed", 3))
    private val list = MutableStateFlow(runCatching {
        val array = JSONArray(prefs.getString("list", "[]"))
        (0 until array.length()).map { decode(array.getJSONObject(it)) }.ifEmpty { listOf(Profile()) }
    }.getOrDefault(listOf(Profile())))
    private val chosen = MutableStateFlow(list.value.find { it.id == prefs.getString("selected", "principal") } ?: list.value.first())
    override val profiles = list.asStateFlow()
    override val selected = chosen.asStateFlow()
    override var resumeOnBoot: Boolean
        get() = prefs.getBoolean("resume", false)
        set(value) { prefs.edit().putBoolean("resume", value).apply() }
    @Synchronized override fun save(profile: Profile) {
        val items = list.value.filterNot { it.id == profile.id } + profile
        persist(items, profile.id); list.value = items; chosen.value = profile
    }
    @Synchronized override fun select(id: String) { list.value.find { it.id == id }?.let { prefs.edit().putString("selected", id).commit(); chosen.value = it } }
    @Synchronized override fun delete(id: String) {
        credentials.delete(id)
        val items = list.value.filterNot { it.id == id }.ifEmpty { listOf(Profile(id = UUID.randomUUID().toString())) }
        persist(items, items.first().id); list.value = items; chosen.value = items.first()
    }
    private fun persist(items: List<Profile>, selected: String) {
        val array = JSONArray()
        items.forEach { p -> array.put(JSONObject().put("id", p.id).put("name", p.name).put("host", p.host).put("port", p.port).put("user", p.user).put("remotePort", p.remotePort).put("mode", p.mode.name).put("keyImported", p.keyImported).put("timeout", p.timeoutSeconds).put("keepalive", p.keepaliveSeconds).put("missed", p.missedKeepalives)) }
        check(prefs.edit().putString("list", array.toString()).putString("selected", selected).commit()) { "Falha ao salvar perfil." }
    }
}
class TrustStore(private val vault: Vault) : HostTrust {
    private val challenge = MutableStateFlow<HostChallenge?>(null)
    override val pending = challenge.asStateFlow()
    private fun name(c: HostChallenge) = "host-${c.host.lowercase()}-${c.port}"
    override suspend fun verify(challenge: HostChallenge): Boolean {
        val saved = vault.get(name(challenge))?.toString(Charsets.UTF_8)
        if (saved == challenge.key) return true
        if (saved != null) throw TunnelFailure("A chave pública do servidor mudou. Confira a identidade por um canal confiável; a conexão foi bloqueada.", false)
        this.challenge.value = challenge
        return false
    }
    override fun answer(accept: Boolean) {
        val pending = challenge.value ?: return
        if (accept) vault.put(name(pending), pending.key.toByteArray())
        challenge.value = null
    }
}
/** Metadata only. Writers never propagate storage errors to the tunnel. */
class History(private val vault: Vault, scope: CoroutineScope) : ConnectionHistory {
    private val connections = linkedMapOf<String, Usage>()
    private val events = ArrayDeque<SshEvent>()
    private var wifi = Traffic(); private var mobile = Traffic()
    private val mutable = MutableStateFlow(HistorySnapshot())
    override val state = mutable.asStateFlow()
    @Volatile private var dirty = false
    init {
        runCatching {
            val saved = vault.get("history")?.toString(Charsets.UTF_8) ?: return@runCatching
            val root = JSONObject(saved)
            val rows = root.getJSONArray("connections")
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val original = ConnectionState.valueOf(o.getString("state"))
                val active = original == ConnectionState.CONNECTED || original == ConnectionState.CONNECTING
                val row = Usage(o.getString("id"), o.getLong("start"), NetworkKind.valueOf(o.getString("network")), o.getString("host"), o.getInt("port"), if (active) ConnectionState.ERROR else original, o.getLong("sent"), o.getLong("received"), if (active) System.currentTimeMillis() else o.optLong("end").takeIf { it > 0 }, if (active) "Processo anterior encerrado." else o.optString("failure").takeIf { it.isNotBlank() })
                connections[row.id] = row
            }
            val logs = root.getJSONArray("events")
            for (i in 0 until logs.length()) { val o = logs.getJSONObject(i); events.addLast(SshEvent(o.getLong("at"), o.getString("message"))) }
            publish()
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                val data = synchronized(this@History) {
                    if (!dirty) null else { dirty = false; publish(); encode() }
                }
                if (data != null) runCatching { vault.put("history", data.toByteArray()) }
            }
        }
    }
    @Synchronized override fun event(message: String) {
        events.addLast(SshEvent(System.currentTimeMillis(), message)); while (events.size > 500) events.removeFirst(); dirty = true
    }
    @Synchronized override fun begin(network: NetworkKind, destination: String, port: Int): String {
        val id = UUID.randomUUID().toString()
        connections[id] = Usage(id, System.currentTimeMillis(), network, destination, port)
        trim(); dirty = true; return id
    }
    @Synchronized override fun connected(id: String) { connections[id]?.let { connections[id] = it.copy(state = ConnectionState.CONNECTED) }; dirty = true }
    @Synchronized override fun bytes(id: String, sent: Long, received: Long) {
        connections[id]?.let {
            connections[id] = it.copy(sent = it.sent + sent, received = it.received + received)
            if (it.network == NetworkKind.WIFI) wifi = Traffic(wifi.sent + sent, wifi.received + received) else mobile = Traffic(mobile.sent + sent, mobile.received + received)
        }; dirty = true
    }
    @Synchronized override fun end(id: String, failure: String?) {
        connections[id]?.let { connections[id] = it.copy(state = if (failure == null) ConnectionState.CLOSED else ConnectionState.ERROR, endedAt = System.currentTimeMillis(), failure = failure) }; trim(); dirty = true
    }
    private fun trim() { while (connections.size > 1000) { val first = connections.values.firstOrNull { it.endedAt != null } ?: break; connections.remove(first.id) } }
    @Synchronized override fun resetSessionTotals() { wifi = Traffic(); mobile = Traffic(); dirty = true }
    @Synchronized override fun clear() { connections.entries.removeAll { it.value.endedAt != null }; events.clear(); dirty = true; publish() }
    private fun publish() { mutable.value = HistorySnapshot(connections.values.toList().asReversed(), events.toList().asReversed(), wifi, mobile) }
    private fun encode(): String {
        val rows = JSONArray(); connections.values.forEach { u -> rows.put(JSONObject().put("id", u.id).put("start", u.startedAt).put("network", u.network.name).put("host", u.destination).put("port", u.port).put("state", u.state.name).put("sent", u.sent).put("received", u.received).put("end", u.endedAt ?: 0).put("failure", u.failure ?: "")) }
        val logs = JSONArray(); events.forEach { logs.put(JSONObject().put("at", it.at).put("message", it.message)) }
        return JSONObject().put("connections", rows).put("events", logs).toString()
    }
}
