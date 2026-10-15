package br.com.lotobot.tunnel.presentation

import android.Manifest
import android.content.*
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.lotobot.tunnel.di.TunnelApp
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as TunnelApp).graph
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3949AB), background = Color(0xFFF5F7FA), surface = Color.White,
                onSurface = Color(0xFF17213D), onBackground = Color(0xFF17213D), onSurfaceVariant = Color(0xFF667085), outline = Color(0xFFDDE3EE), outlineVariant = Color(0xFFDDE3EE),
                secondaryContainer = Color(0xFFE8ECFC), onSecondaryContainer = Color(0xFF3949AB),
                surfaceContainer = Color.White, surfaceContainerLow = Color.White, surfaceContainerHigh = Color.White,
                surfaceContainerHighest = Color(0xFFF5F7FA)),
                typography = Typography(headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.Bold),
                    titleLarge = Typography().titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))) {
                val vm: TunnelViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TunnelViewModel(
                        graph.control, graph.profiles, graph.vault, graph.trust, graph.history,
                        { uri -> contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = stream.readNBytes(256 * 1024 + 1)
                            require(bytes.size <= 256 * 1024) { "Arquivo de chave excede 256 KB." }; bytes
                        } ?: error("Não foi possível abrir o arquivo.") }) as T
                })
                App(vm) { label, value ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
                    Toast.makeText(this, "$label copiado.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
@Composable private fun App(vm: TunnelViewModel, copy: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val history by vm.logs.collectAsStateWithLifecycle()
    val challenge by vm.hostChallenge.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableIntStateOf(0) }
    val screenScroll = rememberScrollState()
    LaunchedEffect(destination) { screenScroll.scrollTo(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Scaffold(topBar = { Surface { Text("LotoBot Tunnel", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) } },
        bottomBar = {
            NavigationBar {
                listOf("Conexão" to Icons.Outlined.Home, "Logs" to Icons.Outlined.List, "Ajustes" to Icons.Outlined.Settings).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = destination == i, onClick = { destination = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(screenScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (destination) {
                0 -> ConnectionScreen(state.copy(profile = profile), history, { permission.launch(Manifest.permission.POST_NOTIFICATIONS); vm.connect() }, vm::disconnect, vm::refresh, copy)
                1 -> LogsScreen(history, vm::clear, copy)
                2 -> SettingsScreen(vm, profile, state)
            }
        }
    }
    message?.let { text -> AlertDialog(onDismissRequest = vm::dismissMessage, title = { Text("LotoBot Tunnel") }, text = { Text(text) }, confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } }) }
    challenge?.let { c -> AlertDialog(onDismissRequest = { vm.answerHost(false) }, title = { Text("Confirmar identidade SSH") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Confira esta impressão digital por um canal confiável antes de aceitar."); Text("${c.host} • Porta ${c.port}"); Text(c.algorithm); Text(c.fingerprint, fontFamily = FontFamily.Monospace) } },
        confirmButton = { TextButton(onClick = { vm.answerHost(true) }) { Text("Confiar e conectar") } }, dismissButton = { TextButton(onClick = { vm.answerHost(false) }) { Text("Cancelar") } }) }
}
@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
@Composable private fun Notice(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) Color(0xFFFDECEC) else Color(0xFFFFF4DE)) {
        Text(text, color = if (error) Color(0xFFC62828) else Color(0xFFB85C00), modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}
@Composable private fun ConnectionScreen(s: TunnelSnapshot, h: HistorySnapshot, connect: () -> Unit, disconnect: () -> Unit, refresh: () -> Unit, copy: (String, String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val active = s.status !in setOf(TunnelStatus.DISCONNECTED, TunnelStatus.ERROR)
    val success = s.status == TunnelStatus.CONNECTED
    Surface(color = if (success) Color(0xFFE3F6EF) else if (s.status == TunnelStatus.ERROR) Color(0xFFFDECEC) else Color(0xFFFFF4DE), shape = RoundedCornerShape(16.dp)) {
        Text(s.status.label, style = MaterialTheme.typography.titleLarge, color = if (success) Color(0xFF00866A) else Color(0xFFB85C00), modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
    Panel(s.profile.name) { Text(s.profile.user); Text(s.profile.host.ifBlank { "Configure o host SSH em Ajustes." }, fontFamily = FontFamily.Monospace); Text("Porta SSH ${s.profile.port} • SOCKS5 127.0.0.1:${s.profile.remotePort}") }
    Panel("Rede") { Text(s.networkLabel ?: "Nenhuma rede em uso pelo túnel"); Text("Modo: ${s.profile.mode.label}") }
    Panel("IPv6 público") {
        Text(s.ipv6.address ?: "Indisponível", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(s.ipv6.networkLabel ?: "Selecione uma rede em Ajustes.")
        if (s.ipv6.stale && s.ipv6.address != null) Notice("Endereço desatualizado; atualize antes de validar.")
        s.ipv6.error?.let { Notice(it) }
        Text("Última consulta: ${s.ipv6.updatedAt?.let(::date) ?: "—"}")
        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { s.ipv6.address?.let { copy("IPv6", it) } }, enabled = s.ipv6.address != null && !s.ipv6.stale, modifier = Modifier.fillMaxWidth()) { Text("Copiar IPv6") }
        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("Atualizar IPv6") }
    }
    Panel("Métricas da sessão") {
        Text("Duração: ${s.connectedAt?.let { duration(now - it) } ?: "—"}")
        Text("Conexões SOCKS5 ativas: ${h.connections.count { it.endedAt == null }}")
        Text("Total enviados: ${bytes(h.wifi.sent + h.mobile.sent)} • Recebidos: ${bytes(h.wifi.received + h.mobile.received)}")
        Text("Wi-Fi: ↑ ${bytes(h.wifi.sent)}  ↓ ${bytes(h.wifi.received)}")
        Text("Dados móveis: ↑ ${bytes(h.mobile.sent)}  ↓ ${bytes(h.mobile.received)}")
    }
    s.lastFailure?.let { Notice("Última falha: $it", true) }
    if (s.status == TunnelStatus.WAITING) Notice("O aplicativo tentará conectar quando uma rede permitida com acesso ao servidor estiver disponível.")
    Button(onClick = if (active) disconnect else connect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (s.status in setOf(TunnelStatus.WAITING, TunnelStatus.RECONNECTING)) "Cancelar reconexão" else if (active) "Desconectar" else "Conectar")
    }
}
@Composable private fun ConfigField(label: String, value: String, change: (String) -> Unit, password: Boolean = false, numeric: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text))
}
@Composable private fun SettingsScreen(vm: TunnelViewModel, profile: Profile, s: TunnelSnapshot) {
    val profiles by vm.allProfiles.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf("Perfil SSH") }
    var name by remember(profile) { mutableStateOf(profile.name) }
    var host by remember(profile) { mutableStateOf(profile.host) }
    var user by remember(profile) { mutableStateOf(profile.user) }
    var port by remember(profile) { mutableStateOf(profile.port.toString()) }
    var remote by remember(profile) { mutableStateOf(profile.remotePort.toString()) }
    var timeout by remember(profile) { mutableStateOf(profile.timeoutSeconds.toString()) }
    var keepalive by remember(profile) { mutableStateOf(profile.keepaliveSeconds.toString()) }
    var missed by remember(profile) { mutableStateOf(profile.missedKeepalives.toString()) }
    var password by remember(profile.id) { mutableStateOf("") }
    var mode by remember(profile) { mutableStateOf(profile.mode) }
    var resume by remember { mutableStateOf(vm.resumeOnBoot()) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    fun draft() = profile.copy(name = name.trim(), host = host.trim(), user = user.trim(), port = port.toIntOrNull() ?: 0, remotePort = remote.toIntOrNull() ?: 0,
        timeoutSeconds = timeout.toIntOrNull() ?: 0, keepaliveSeconds = keepalive.toIntOrNull() ?: 0, missedKeepalives = missed.toIntOrNull() ?: 0, mode = mode)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importKey(it, draft(), password); password = "" } }
    Select("Configuração", listOf("Perfil SSH", "Rede"), editor) { editor = it }
    if (editor == "Perfil SSH") {
        Panel("Perfil SSH") {
            Select("Perfil selecionado", profiles.map { it.name + " • " + it.id.take(8) }, profile.name + " • " + profile.id.take(8)) { label -> vm.select(profiles.first { it.name + " • " + it.id.take(8) == label }) }
            TextButton(onClick = { vm.select(Profile(id = UUID.randomUUID().toString(), name = "Novo perfil")) }) { Text("Novo perfil") }
            ConfigField("Nome do perfil", name, { name = it })
            ConfigField("Host SSH", host, { host = it })
            ConfigField("Porta SSH", port, { port = it }, numeric = true)
            ConfigField("Usuário SSH", user, { user = it })
            Text(if (profile.keyImported) "Chave privada importada" else "Nenhuma chave importada")
            ConfigField("Senha da chave (antes de importar)", password, { password = it }, password = true)
            OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Importar chave privada") }
            Text("Escuta remota: 127.0.0.1 (loopback)", fontFamily = FontFamily.Monospace)
            ConfigField("Porta SOCKS5 remota", remote, { remote = it }, numeric = true)
            ConfigField("Timeout SSH (segundos)", timeout, { timeout = it }, numeric = true)
            ConfigField("Intervalo keepalive (segundos)", keepalive, { keepalive = it }, numeric = true)
            ConfigField("Limite de respostas ausentes", missed, { missed = it }, numeric = true)
            Button(onClick = { vm.save(draft()) }, modifier = Modifier.fillMaxWidth()) { Text("Salvar perfil") }
            TextButton(onClick = { deleteConfirmation = true }) { Text("Excluir perfil e credenciais") }
        }
    } else {
        Panel("Rede") {
            NetworkMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().selectableNetwork { mode = m }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == m, onClick = { mode = m }); Text(m.label, modifier = Modifier.weight(1f))
                }
            }
            Text("Rede do túnel: ${s.networkLabel ?: "nenhuma"}")
            Notice("Trocar de rede interrompe conexões ativas e reconecta o túnel.")
            Notice("Dados móveis consomem a franquia do plano. O modo automático prefere Wi-Fi e retorna após 15 segundos de estabilidade.")
            Button(onClick = { vm.changeMode(mode) }, modifier = Modifier.fillMaxWidth()) { Text("Salvar configuração") }
        }
    }
    Panel("Segundo plano") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = resume, onCheckedChange = { resume = it; vm.resumeOnBoot(it) }); Text("Lembrar de retomar após reiniciar", modifier = Modifier.weight(1f))
        }
        Text("Após reiniciar, a notificação abre o aplicativo para você conectar. O Android e o fabricante podem interromper a sessão.")
    }
    if (deleteConfirmation) AlertDialog(onDismissRequest = { deleteConfirmation = false }, title = { Text("Excluir perfil?") }, text = { Text("O túnel será desconectado e as credenciais deste perfil serão removidas.") },
        confirmButton = { TextButton(onClick = { vm.delete(profile); deleteConfirmation = false }) { Text("Excluir") } }, dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("Cancelar") } })
}
private fun Modifier.selectableNetwork(click: () -> Unit) = this.clickable(onClick = click)
@Composable private fun Select(label: String, options: List<String>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected", modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ArrowDropDown, "Abrir opções") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false }) }
        }
    }
}
@Composable private fun LogsScreen(history: HistorySnapshot, clear: () -> Unit, copy: (String, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(history) }
    LaunchedEffect(history, paused) { if (!paused) frozen = history }
    val shown = if (paused) frozen else history
    var destination by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Todos") }
    var network by rememberSaveable { mutableStateOf("Todas") }
    var period by rememberSaveable { mutableStateOf("Todo o histórico") }
    var confirmClear by remember { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    val limit = when (period) { "Última hora" -> System.currentTimeMillis() - 3600000; "Últimas 24 horas" -> System.currentTimeMillis() - 86400000; else -> 0 }
    val rows = shown.connections.filter { it.destination.contains(destination, true) && (status == "Todos" || it.state.label == status) && (network == "Todas" || it.network.label == network) && it.startedAt >= limit }
    val events = shown.events.filter { it.at >= limit && it.message.contains(destination, true) }
    Row {
        TextButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) { Text(if (tab == 0) "● Uso do SOCKS5" else "Uso do SOCKS5") }
        TextButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text(if (tab == 1) "● Túnel SSH" else "Túnel SSH") }
    }
    Panel("${shown.connections.count { it.endedAt == null }} conexões ativas") { Text(if (paused) "Visualização pausada; o registro continua." else "Atualização em tempo real") }
    OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { filtersExpanded = !filtersExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (filtersExpanded) "Ocultar filtros" else "Mostrar filtros") }
    if (filtersExpanded) Panel("Filtros") {
        ConfigField(if (tab == 0) "Destino" else "Texto do evento", destination, { destination = it })
        if (tab == 0) {
            Select("Estado", listOf("Todos") + ConnectionState.entries.map { it.label }, status) { status = it }
            Select("Rede", listOf("Todas") + NetworkKind.entries.map { it.label }, network) { network = it }
        }
        Select("Período", listOf("Todo o histórico", "Última hora", "Últimas 24 horas"), period) { period = it }
    }

    if (tab == 0) {
        if (rows.isEmpty()) Panel("Nenhum registro") { Text("As conexões solicitadas na EC2 aparecerão aqui.") }
        rows.forEach { u -> Panel("${u.destination}:${u.port}") {
            Text("#${u.id.take(8)} • ${u.state.label}${if (u.endedAt == null) " • Em andamento" else ""}")
            Text("${u.network.label} • ${date(u.startedAt)}")
            Text("Fim: ${u.endedAt?.let(::date) ?: "—"} • Duração: ${duration((u.endedAt ?: System.currentTimeMillis()) - u.startedAt)}")
            Text("Enviados: ${bytes(u.sent)} • Recebidos: ${bytes(u.received)}")
            u.failure?.let { Notice(it, true) }
        } }
    } else {
        if (events.isEmpty()) Panel("Nenhum evento SSH") { Text("Eventos do túnel aparecerão aqui.") }
        events.forEach { e -> Panel(date(e.at)) { Text(e.message) } }
    }
    OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { if (!paused) frozen = history; paused = !paused }, modifier = Modifier.fillMaxWidth()) { Text(if (paused) "Retomar atualização" else "Pausar atualização") }
    OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), onClick = { copy("Registros", if (tab == 0) rows.joinToString("\n", transform = ::usageText) else events.joinToString("\n") { "${date(it.at)} | ${it.message}" }) }, modifier = Modifier.fillMaxWidth()) { Text("Copiar registros filtrados") }
    TextButton(onClick = { confirmClear = true }) { Text("Limpar histórico") }
    Text("Enviados: EC2 → destino. Recebidos: destino → EC2. Os totais não incluem o tráfego adicional do SSH e não medem toda a franquia.", style = MaterialTheme.typography.bodySmall)
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Limpar histórico?") }, text = { Text("Registros encerrados e eventos serão removidos. Conexões ativas serão preservadas.") },
        confirmButton = { TextButton(onClick = { clear(); confirmClear = false }) { Text("Limpar") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
}
private fun date(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.forLanguageTag("pt-BR")).format(Date(at))
private fun duration(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) }
private fun bytes(count: Long): String = when { count >= 1048576 -> "%.1f MB".format(count / 1048576.0); count >= 1024 -> "%.1f KB".format(count / 1024.0); else -> "$count B" }
private fun usageText(u: Usage) = "${date(u.startedAt)} | #${u.id} | ${u.network.label} | ${u.destination}:${u.port} | ${u.state.label} | Enviados: ${u.sent} B | Recebidos: ${u.received} B | Fim: ${u.endedAt?.let(::date) ?: "em andamento"} | Duração: ${duration((u.endedAt ?: System.currentTimeMillis()) - u.startedAt)}${u.failure?.let { " | $it" } ?: ""}"
