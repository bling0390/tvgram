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
import org.json.JSONObject
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
 *                              → use downloadFile(fileId, priority, offset, limit, sync)
 *                                with sync = false; TDLib will send updateFile updates
 *   When updateFile .local.is_downloading_completed = true → mark Local
 *
 * For MVP we:
 *   - getFile (sync mode) — blocks until TDLib has a stable file record
 *   - downloadFile with priority 32 (high) to actually fetch the bytes
 *   - Wait for updateFile (within 60s) showing is_downloading_completed = true
 */
class TdFileRepository(
    private val client: TdClient = TdClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val _states = MutableStateFlow<Map<Int, FileDownloadState>>(emptyMap())
    val states: StateFlow<Map<Int, FileDownloadState>> = _states.asStateFlow()

    // pending file objects from sendAndAwait on getFile
    private val pendingFileObjects = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    // outstanding downloads: fileId -> deferred that completes when is_downloading_completed=true
    private val pendingDownloads = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    init {
        scope.launch {
            client.updates.collect { obj -> dispatch(obj) }
        }
    }

    private fun dispatch(obj: JSONObject) {
        val type = obj.optString("@type")
        val extra = obj.optString("@extra", "")
        if (extra.startsWith("__req_")) {
            val d = pendingFileObjects.remove(extra)
            if (d != null) {
                d.complete(obj)
                return
            }
        }
        when (type) {
            "updateFile" -> handleUpdateFile(obj)
        }
    }

    private fun handleUpdateFile(obj: JSONObject) {
        val file = obj.optJSONObject("file") ?: return
        val fileId = file.optInt("id")
        val local = file.optJSONObject("local")
        val completed = local?.optBoolean("is_downloading_completed", false) ?: false
        val path = local?.optString("path", "").orEmpty()
        if (completed && path.isNotEmpty()) {
            val d = pendingDownloads.remove(fileId)
            d?.complete(path)
            _states.value = _states.value + (fileId to FileDownloadState.Local(path))
        }
    }

    private suspend fun sendAndAwait(query: JSONObject, timeoutMs: Long = 5_000L): JSONObject? {
        val extra = "__req_${System.nanoTime()}_${query.optString("@type")}"
        query.put("@extra", extra)
        val deferred = CompletableDeferred<JSONObject>()
        pendingFileObjects[extra] = deferred
        client.send(query)
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
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
        val fileObj = sendAndAwait(JSONObject().apply {
            put("@type", "getFile")
            put("file_id", fileId)
        }, timeoutMs = 5_000L)
        if (fileObj == null || fileObj.optString("@type") != "file") {
            Log.w(TAG, "ensureLocal($fileId): getFile returned ${fileObj?.optString("@type") ?: "null"}")
            _states.value = _states.value + (fileId to FileDownloadState.Failed("getFile failed"))
            return null
        }
        val local = fileObj.optJSONObject("local")
        if (local != null && local.optBoolean("is_downloading_completed", false)) {
            val path = local.optString("path")
            _states.value = _states.value + (fileId to FileDownloadState.Local(path))
            return path
        }

        // Kick off the download
        val deferred = CompletableDeferred<String>()
        pendingDownloads[fileId] = deferred
        client.send(JSONObject().apply {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", priority)
            put("offset", 0)
            put("limit", 0)
            put("synchronous", false)
        })

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
