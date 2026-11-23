package dev.hiorcraft.openssh.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

enum class TransferDirection { UPLOAD, DOWNLOAD }

sealed interface TransferState {
    data object Queued : TransferState
    data object Running : TransferState
    data object Done : TransferState
    data object Cancelled : TransferState
    data class Failed(val message: String) : TransferState
}

data class Transfer(
    val id: Long,
    val direction: TransferDirection,
    val displayName: String,
    val source: String,
    val target: String,
    val state: TransferState = TransferState.Queued,
    val progress: TransferProgress? = null,
) {
    val isFinished: Boolean get() = state is TransferState.Done || state is TransferState.Cancelled || state is TransferState.Failed
}

/**
 * Warteschlange für Uploads und Downloads mit begrenzter Parallelität.
 * Eignet sich direkt als Ziel für Drag & Drop: [enqueueUploads] nimmt die gezogenen Dateien
 * und Ordner entgegen, [enqueueDownloads] die ausgewählten Server-Pfade.
 */
class TransferQueue(
    private val scope: CoroutineScope,
    private val browser: SftpBrowser,
    parallelism: Int = 2,
    private val onFinished: (Transfer) -> Unit = {},
) {
    private val ids = AtomicLong()
    private val semaphore = Semaphore(parallelism)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    fun enqueueUploads(locals: List<Path>, remoteDir: String): List<Transfer> = locals.map { local ->
        start(Transfer(ids.incrementAndGet(), TransferDirection.UPLOAD, local.name, local.toString(), remoteDir)) { t ->
            browser.upload(local, remoteDir) { p -> progress(t.id, p) }
        }
    }

    fun enqueueDownloads(remotePaths: List<String>, localDir: Path): List<Transfer> = remotePaths.map { remote ->
        start(Transfer(ids.incrementAndGet(), TransferDirection.DOWNLOAD, RemotePaths.name(remote), remote, localDir.toString())) { t ->
            browser.download(remote, localDir) { p -> progress(t.id, p) }
        }
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
    }

    fun cancelAll() = jobs.values.forEach { it.cancel() }

    fun clearFinished() = _transfers.update { list -> list.filterNot { it.isFinished } }

    private fun start(transfer: Transfer, work: suspend (Transfer) -> Any): Transfer {
        _transfers.update { it + transfer }
        val job = scope.launch {
            try {
                semaphore.withPermit {
                    setState(transfer.id, TransferState.Running)
                    work(transfer)
                }
                setState(transfer.id, TransferState.Done)
            } catch (e: CancellationException) {
                setState(transfer.id, TransferState.Cancelled)
                throw e
            } catch (e: Exception) {
                setState(transfer.id, TransferState.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                jobs.remove(transfer.id)
                _transfers.value.firstOrNull { it.id == transfer.id }?.let(onFinished)
            }
        }
        jobs[transfer.id] = job
        return transfer
    }

    private fun setState(id: Long, state: TransferState) =
        _transfers.update { list -> list.map { if (it.id == id) it.copy(state = state) else it } }

    private fun progress(id: Long, p: TransferProgress) =
        _transfers.update { list -> list.map { if (it.id == id) it.copy(progress = p) else it } }
}
