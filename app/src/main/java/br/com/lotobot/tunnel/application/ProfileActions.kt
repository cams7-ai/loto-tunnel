package br.com.lotobot.tunnel.application

/** Keeps private key data out of presentation state and clears temporary buffers. */
class ProfileActions(private val credentials: CredentialStore) {
    fun importKey(id: String, read: () -> ByteArray, password: String) {
        val bytes = read()
        val pass = password.toByteArray().takeIf { it.isNotEmpty() }
        try { credentials.validateAndWrite(id, bytes, pass) }
        finally { bytes.fill(0); pass?.fill(0) }
    }
}
