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

/**
 * FileDownloadState — what we know about a remote file.
 *
 *   - Remote    : we know the file_id but haven't asked TDLib yet
 *   - Pending   : asked, waiting for file object
 *   - Local     : downloaded, we have a local file.path
 *   - Failed    : download or getFile error
 */
sealed class FileDownloadState {
    data object Remote : FileDownloadState()
    data class Pending(val expectedSize: Int = 0) : FileDownloadState()
    data class Local(val path: String) : FileDownloadState()
    data class Failed(val reason: String) : FileDownloadState()
}

/**
 * TdFileRepository — drives file downloads (chat photos, media thumbs,
 * full media files).
 *
 * TDLib flow:
 *   getFile(fileId)            → file object (or fileEmpty)
 *   file object .local.path    → already downloaded
 *   file object .local.is_downloading_completed = false
 *                              → use downloadFile(fileId, priority, offset, limit, synchronous)
 *                                with synchronous = false; TDLib will send updateFile updates
 *   When updateFile .local.is_downloading_completed = true → mark Local
 *
 * v1.0.0 (D-029): migrated from JSON RPC to typed [TdApi] objects.
 */
class TdFileRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _states = MutableStateFlow<Map<Int, FileDownloadState>>(emptyMap())
    val states: StateFlow<Map<Int, FileDownloadState>> = _states.asStateFlow()

    // Outstanding downloads: fileId -> deferred that completes when is_downloading_completed=true
    private val pendingDownloads = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatch(obj) }
        }
    }

    private fun dispatch(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateFile -> handleUpdateFile(obj.file)
            else -> { /* ignore */ }
        }
    }

    private fun handleUpdateFile(file: TdApi.File) {
        val local = file.local ?: return
        val fileId = file.id
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            val d = pendingDownloads.remove(fileId)
            d?.complete(local.path)
            _states.value = _states.value + (fileId to FileDownloadState.Local(local.path))
        }
    }

    /**
     * Trigger download of a TDLib file and await completion.
     * Returns local path, or null on timeout / failure.
     */
    suspend fun ensureLocal(fileId: Int, priority: Int = 32, timeoutMs: Long = 60_000L): String? {
        val current = _states.value[fileId]
        if (current is FileDownloadState.Local) return current.path

        _states.value = _states.value + (fileId to FileDownloadState.Pending())

        // getFile first to materialize the File object on TDLib's side
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

        // Kick off the download
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
        return path
    }

    fun stateFor(fileId: Int): FileDownloadState? = _states.value[fileId]

    companion object {
        private const val TAG = "TdFileRepo"
    }
}