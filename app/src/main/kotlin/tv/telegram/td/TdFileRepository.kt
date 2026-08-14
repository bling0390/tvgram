package tv.telegram.td

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.td.libcore.telegram.TdApi
import java.util.concurrent.ConcurrentHashMap

sealed class FileDownloadState {
    data object Remote : FileDownloadState()
    data class Pending(val expectedSize: Int = 0) : FileDownloadState()
    data class Local(val path: String) : FileDownloadState()
    data class Failed(val reason: String) : FileDownloadState()
}

class TdFileRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _states = MutableStateFlow<Map<Int, FileDownloadState>>(emptyMap())
    val states: StateFlow<Map<Int, FileDownloadState>> = _states.asStateFlow()

    private val pendingDownloads = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val previewDownloads = ConcurrentHashMap<Int, PreviewRequest>()

    private data class PreviewRequest(
        val deferred: CompletableDeferred<String>,
        val limitBytes: Int,
    )

    init {
        scope.launch {
            client.updates.collect { obj -> dispatch(obj) }
        }
    }

    private fun dispatch(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateFile -> handleUpdateFile(obj.file)
            else -> {  }
        }
    }

    private fun handleUpdateFile(file: TdApi.File) {
        val local = file.local ?: return
        val fileId = file.id
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            val d = pendingDownloads.remove(fileId)
            d?.complete(local.path)
            val pr = previewDownloads.remove(fileId)
            pr?.deferred?.complete(local.path)
            _states.value = _states.value + (fileId to FileDownloadState.Local(local.path))
        } else {
            // Preview download: complete as soon as we have the requested prefix.
            val pr = previewDownloads[fileId]
            if (pr != null && local.path.isNotEmpty() && local.downloadedSize >= pr.limitBytes) {
                previewDownloads.remove(fileId)
                pr.deferred.complete(local.path)
            }
        }
    }

    suspend fun ensureLocal(fileId: Int, priority: Int = 32, timeoutMs: Long = 60_000L): String? {
        val current = _states.value[fileId]
        if (current is FileDownloadState.Local) return current.path

        _states.value = _states.value + (fileId to FileDownloadState.Pending())

        return try {
            val fileObj = client.execute(TdApi.GetFile(fileId), timeoutMs = 5_000L)
            if (fileObj !is TdApi.File) {
                Log.w(TAG, "ensureLocal($fileId): getFile returned ${fileObj?.javaClass?.simpleName ?: "null"}")
                _states.value = _states.value + (fileId to FileDownloadState.Failed("getFile failed"))
                return null
            }
            val local = fileObj.local
            if (local != null && local.isDownloadingCompleted) {
                _states.value = _states.value + (fileId to FileDownloadState.Local(local.path))
                return local.path
            }

            val deferred = CompletableDeferred<String>()
            pendingDownloads[fileId] = deferred
            client.send(TdApi.DownloadFile(fileId, priority, 0, 0, false))

            val path = withTimeoutOrNull(timeoutMs) {
                try { deferred.await() } catch (_: Throwable) { null }
            }
            if (path == null) {
                pendingDownloads.remove(fileId)
                _states.value = _states.value + (fileId to FileDownloadState.Failed("download timeout"))
            }
            path
        } catch (e: Throwable) {
            // Never let a file fetch crash the app — callers fall back to placeholder.
            Log.w(TAG, "ensureLocal($fileId) threw", e)
            pendingDownloads.remove(fileId)
            _states.value = _states.value + (fileId to FileDownloadState.Failed("exception: ${e.javaClass.simpleName}"))
            null
        }
    }

    /**
     * Download only the first [limitBytes] of a file — enough to start a
     * muted hover-preview without waiting for the full video. Never marks
     * the file as fully Local (a later ensureLocal re-downloads the rest).
     */
    suspend fun ensurePreview(
        fileId: Int,
        limitBytes: Int = 2 * 1024 * 1024,
        priority: Int = 32,
        timeoutMs: Long = 20_000L,
    ): String? {
        val current = _states.value[fileId]
        if (current is FileDownloadState.Local) return current.path

        // Already have enough bytes on disk? Use them.
        val fileObj = client.execute(TdApi.GetFile(fileId), timeoutMs = 5_000L)
        if (fileObj is TdApi.File) {
            val local = fileObj.local
            if (local.path.isNotEmpty() &&
                (local.isDownloadingCompleted || local.downloadedSize >= limitBytes)
            ) {
                return local.path
            }
        }

        val deferred = CompletableDeferred<String>()
        previewDownloads[fileId] = PreviewRequest(deferred, limitBytes)
        client.send(TdApi.DownloadFile(fileId, priority, 0, limitBytes, false))

        val path = withTimeoutOrNull(timeoutMs) {
            try { deferred.await() } catch (_: Throwable) { null }
        }
        if (path == null) {
            previewDownloads.remove(fileId)
        }
        return path
    }

    fun stateFor(fileId: Int): FileDownloadState? = _states.value[fileId]

    companion object {
        private const val TAG = "TdFileRepo"
    }
}
