package tv.telegram.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File

/**
 * TdClient — drives TDLib via the JNI bindings in libtdjni.so.
 *
 * Architecture (D-029, replaces D-027):
 *   - libtdjni.so is the TDLib JNI library (bundled in libtd/ module's
 *     src/main/libs/<abi>/). [Client.create] loads it via the standard
 *     JNI mechanism (Android extracts from APK) and spawns a worker
 *     thread that handles all TDLib I/O.
 *   - We treat updates (sent via the updateHandler) and direct responses
 *     (sent via per-query resultHandlers) separately. Updates are
 *     broadcast on the [updates] SharedFlow; direct responses are routed
 *     to the calling site via [send]'s [onResult] callback or [execute]'s
 *     suspend return.
 *
 * Replacement history:
 *   - v0.9.0 (D-027): ProcessBuilder(libtdjson.so) — did not work on
 *     Android because the artifact was ET_DYN (shared object), not ET_EXEC.
 *   - v1.0.0 (D-029): JNI via libtdjni.so — typed [TdApi.Function] / [TdApi.Object].
 *
 * Lifecycle:
 *   1. [TgTvApp.onCreate] → [startWithPaths] — preserves login state.
 *   2. [MainViewModel.realSignOut] → [realSignOut] — wipes state + restart.
 *   3. [Client.close] is called in [stop] to release the native thread.
 */
object TdClient {

    private const val TAG = "TdClient"

    @Volatile private var started = false

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    private var client: Client? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start the TDLib client. Idempotent.
     *
     * Steps:
     *   1. Create the TDLib [Client] (loads libtdjni.so, spawns worker thread)
     *   2. Send setTdlibParameters with the on-disk db / files dirs
     *
     * The directories are NOT wiped here — we want login state to persist
     * across app restarts. [realSignOut] does the wipe.
     */
    fun startWithPaths(
        context: Context,
        apiId: Int,
        apiHash: String,
        databaseDirectory: String,
        filesDirectory: String,
    ) {
        if (started) {
            Log.w(TAG, "startWithPaths() called twice; ignoring")
            return
        }
        started = true

        // Ensure dirs exist (no destructive wipe — see D-029).
        runCatching {
            File(databaseDirectory).mkdirs()
            File(filesDirectory).mkdirs()
        }.onFailure { Log.w(TAG, "Failed to ensure TDLib dirs", it) }

        // Spawn the TDLib client. Native lib is loaded by Client.<clinit>
        // via NativeClient's static System.loadLibrary("tdjni").
        val c = Client.create(
            { obj -> dispatchUpdate(obj) },   // updateHandler — broadcasts everything
            { e -> Log.w("TDLib-update", "update handler threw", e) },
            { e -> Log.w("TDLib-default", "default handler threw", e) },
        )
        client = c
        Log.i(TAG, "TDLib client created; sending setTdlibParameters")

        // Build setTdlibParameters.
        // Use message_database for chat history so search / pagination works.
        // Use `this.field = param` to disambiguate from the same-named receiver field.
        val params = TdApi.TdlibParameters().apply {
            this.apiId = apiId
            this.apiHash = apiHash
            systemLanguageCode = "en"
            deviceModel = "Tvgram TV"
            systemVersion = "Android TV"
            applicationVersion = "0.9.0"
            this.databaseDirectory = databaseDirectory
            this.filesDirectory = filesDirectory
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            enableStorageOptimizer = true
            ignoreFileNames = false
        }
        send(TdApi.SetTdlibParameters(params))
    }

    /**
     * Send a query with optional direct-response handler.
     *
     * @param query    TDLib function call (any TdApi.Function subclass)
     * @param onResult Optional handler for the direct response. Most callers
     *                 leave this null and listen on [updates] instead.
     */
    fun send(query: TdApi.Function, onResult: ((TdApi.Object) -> Unit)? = null) {
        val c = client
        if (c == null) {
            Log.w(TAG, "send() before start(); dropping ${query.javaClass.simpleName}")
            return
        }
        if (onResult == null) {
            c.send(query, null)
        } else {
            c.send(query, { obj ->
                try { onResult(obj) } catch (t: Throwable) {
                    Log.w(TAG, "send() onResult threw", t)
                }
            }, { e -> Log.w(TAG, "send() exception", e) })
        }
    }

    /**
     * Suspend until we get a direct response to [query] (or [timeoutMs] elapses).
     * Used for one-shot lookups (e.g. getMe, getChat).
     */
    suspend fun execute(query: TdApi.Function, timeoutMs: Long = 10_000L): TdApi.Object? {
        val c = client ?: run {
            Log.w(TAG, "execute() before start(); dropping ${query.javaClass.simpleName}")
            return null
        }
        val deferred = CompletableDeferred<TdApi.Object>()
        c.send(query, { obj -> deferred.complete(obj) }, { e ->
            Log.w(TAG, "execute() exception", e)
            deferred.completeExceptionally(e)
        })
        return withTimeoutOrNull(timeoutMs) {
            try { deferred.await() } catch (_: Throwable) { null }
        }
    }

    /**
     * Stop the TDLib client.
     *
     * Sends a `close` query, then calls [Client.close] to release native
     * resources (it blocks until the worker thread exits). After this
     * call, the process MUST be re-started via [startWithPaths] (or
     * [realSignOut] to also wipe state).
     */
    fun stop() {
        val c = client
        if (c == null) {
            Log.w(TAG, "stop() called but no TDLib client running")
            started = false
            return
        }
        Log.i(TAG, "Stopping TDLib client")
        runCatching { c.send(TdApi.Close(), null) }
        runCatching { c.close() }
        client = null
        started = false
        Log.i(TAG, "TDLib client stopped")
    }

    /**
     * Real sign-out: stop TDLib, wipe persistent state, restart fresh.
     *
     * The wipe happens here (NOT in [startWithPaths]) so a normal app
     * launch preserves the existing login session — see D-029.
     *
     * @return true if the existing client was stopped + restarted, false
     *         if there was no client and we only wiped state.
     */
    fun realSignOut(
        context: Context,
        apiId: Int,
        apiHash: String,
        databaseDirectory: String,
        filesDirectory: String,
    ): Boolean {
        val c = client
        if (c == null) {
            Log.w(TAG, "realSignOut() called but no TDLib client; just wiping state")
            wipeState(databaseDirectory, filesDirectory)
            return false
        }
        Log.i(TAG, "Real sign-out: stop + wipe + restart")
        stop()
        wipeState(databaseDirectory, filesDirectory)
        startWithPaths(context, apiId, apiHash, databaseDirectory, filesDirectory)
        return true
    }

    private fun wipeState(databaseDirectory: String, filesDirectory: String) {
        runCatching {
            val dbDir = File(databaseDirectory)
            if (dbDir.exists()) {
                val deleted = dbDir.deleteRecursively()
                Log.i(TAG, "Wiped TDLib database: $deleted (path=$databaseDirectory)")
            }
            val filesDir = File(filesDirectory)
            if (filesDir.exists()) {
                val deleted = filesDir.deleteRecursively()
                Log.i(TAG, "Wiped TDLib files: $deleted (path=$filesDirectory)")
            }
        }.onFailure { Log.w(TAG, "Failed to wipe TDLib state", it) }
    }

    /**
     * Updates arrive here via the updateHandler. Broadcast on the SharedFlow
     * for ViewModels to collect.
     */
    private fun dispatchUpdate(obj: TdApi.Object) {
        val emitted = _updates.tryEmit(obj)
        if (!emitted) {
            Log.w(TAG, "Update flow buffer overflow, dropped: ${obj.javaClass.simpleName}")
        }
    }

    /** Internal: exposes scope for repos that want a SupervisorJob lifecycle. */
    internal val ioScope: CoroutineScope get() = scope
}