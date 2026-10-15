package br.com.lotobot.tunnel.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.lotobot.tunnel.application.*
import br.com.lotobot.tunnel.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Android file-picker details stay at the presentation boundary; bytes never enter UI state. */
class TunnelViewModel(
    private val control: TunnelControl, private val profiles: ProfileRepository,
    private val credentials: CredentialStore, private val trust: HostTrust,
    private val history: ConnectionHistory, private val readDocument: (Uri) -> ByteArray
) : ViewModel() {
    val state = control.state
    val profile = profiles.selected
    val allProfiles = profiles.profiles
    val hostChallenge = trust.pending
    val logs = history.state
    private val messageFlow = MutableStateFlow<String?>(null)
    val message = messageFlow.asStateFlow()
    fun connect() = safely { control.connect() }
    fun recover() = safely { control.recover() }
    fun disconnect() = safely { control.disconnect() }
    fun refresh() = safely { control.refreshIpv6() }
    fun save(profile: Profile) = safely {
        val error = profile.copy(keyImported = true).validate()
        require(error == null) { error ?: "Perfil inválido." }
        control.changeProfile(profile); messageFlow.value = "Perfil salvo."
    }
    fun changeMode(mode: NetworkMode) = safely { control.changeProfile(profiles.selected.value.copy(mode = mode)); messageFlow.value = "Modo de rede salvo." }
    fun select(profile: Profile) = safely { control.changeProfile(profile) }
    fun delete(profile: Profile) = safely { control.disconnect(); profiles.delete(profile.id); control.changeProfile(profiles.selected.value) }
    fun importKey(uri: Uri, profile: Profile, password: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProfileActions(credentials).importKey(profile.id, { readDocument(uri) }, password)
                }
                control.changeProfile(profile.copy(keyImported = true))
                messageFlow.value = "Chave importada e protegida pelo Android Keystore."
            } catch (e: Exception) { messageFlow.value = e.message ?: "Não foi possível importar a chave." }
        }
    }
    fun answerHost(accept: Boolean) = safely { trust.answer(accept); if (accept) control.retry() }
    fun clear() = safely { history.clear() }
    fun resumeOnBoot() = profiles.resumeOnBoot
    fun resumeOnBoot(value: Boolean) { profiles.resumeOnBoot = value }
    fun dismissMessage() { messageFlow.value = null }
    private fun safely(block: () -> Unit) { try { block() } catch (e: Exception) { messageFlow.value = e.message ?: "Não foi possível realizar a ação." } }
}
