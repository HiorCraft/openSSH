package dev.hiorcraft.openssh.app.state

import dev.hiorcraft.openssh.ssh.hostkeys.HostKeyCheck
import dev.hiorcraft.openssh.ssh.hostkeys.HostKeyPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HostKeyRequest(val check: HostKeyCheck, internal val answer: CompletableDeferred<Boolean>) {
    fun accept() = answer.complete(true)
    fun reject() = answer.complete(false)
}

/**
 * Bringt die blockierende Host-Key-Rückfrage von sshj (Transport-Thread) in die Compose-UI.
 * Mehrere gleichzeitige Verbindungen fragen nacheinander.
 */
class HostKeyBridge : HostKeyPrompt {
    private val lock = Any()
    private val _pending = MutableStateFlow<HostKeyRequest?>(null)
    val pending: StateFlow<HostKeyRequest?> = _pending.asStateFlow()

    override fun confirm(check: HostKeyCheck): Boolean = synchronized(lock) {
        val request = HostKeyRequest(check, CompletableDeferred())
        _pending.value = request
        try {
            runBlocking { request.answer.await() }
        } finally {
            _pending.value = null
        }
    }
}

data class SecretAnswer(val value: String, val remember: Boolean)

class SecretRequest(
    val title: String,
    val message: String,
    val label: String,
    val offerRemember: Boolean,
    internal val answer: CompletableDeferred<SecretAnswer?>,
) {
    fun submit(value: String, remember: Boolean) = answer.complete(SecretAnswer(value, remember))
    fun cancel() = answer.complete(null)
}

/** Fragt Passwörter/Passphrasen in der UI ab, wenn sie nicht im Tresor liegen. */
class SecretPromptBridge {
    private val mutex = Mutex()
    private val _pending = MutableStateFlow<SecretRequest?>(null)
    val pending: StateFlow<SecretRequest?> = _pending.asStateFlow()

    suspend fun ask(title: String, message: String, label: String = "Passwort", offerRemember: Boolean = true): SecretAnswer? =
        mutex.withLock {
            val request = SecretRequest(title, message, label, offerRemember, CompletableDeferred())
            _pending.value = request
            try {
                request.answer.await()
            } finally {
                _pending.value = null
            }
        }
}
