package tv.telegram.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /**
     * Cache-cleanup progress broadcaster. D-031:
     *   - null   = idle (no cleanup ever ran, or last cleanup was reset)
     *   - 0..1   = in progress (fraction of files deleted)
     *   - 1f     = finished (UI should call [resetCacheClearProgress] to
     *              dismiss its progress indicator and put the screen back
     *              to the idle state)
     *
     * Surged into SharedFlow territory would be misleading — we only
     * have one current value at a time and need collectAsStateWithLifecycle
     * semantics, so StateFlow is the right shape.
     */
    private val _cacheClearProgress = MutableStateFlow<Float?>(null)
    val cacheClearProgress: StateFlow<Float?> = _cacheClearProgress.asStateFlow()

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
     * Configure an outbound proxy for TDLib's MTProto connections.
     *
     * CRITICAL: TDLib does NOT honor Android's HTTP proxy settings
     * (Settings → WiFi → Manual proxy, or
     * `adb shell settings put global http_proxy ...`). It uses native
     * sockets and only respects proxies configured via
     * [TdApi.AddProxy]. For users behind a firewall (China, Iran, etc.)
     * this MUST be called or TDLib tries to reach Telegram DCs directly
     * and hangs forever at "Connecting to Telegram…".
     *
     * From inside the Android emulator, the host machine's loopback is
     * reachable as `10.0.2.2` (NOT `127.0.0.1`, which is the emulator
     * itself). On a real device, use the host's actual LAN IP.
     *
     * Must be called AFTER [startWithPaths] — the client must exist
     * for the proxy to apply. Idempotent; no-op if [host] is blank
     * or [port] is 0.
     *
     * @param host     Proxy server hostname or IP. Empty = no proxy.
     * @param port     Proxy server port. 0 = no proxy.
     * @param type     "socks5" (default) or "http".
     * @param username Optional auth username. Empty for no auth.
     * @param password Optional auth password. Empty for no auth.
     */
    fun enableProxy(
        host: String,
        port: Int,
        type: String = "socks5",
        username: String = "",
        password: String = "",
    ) {
        if (host.isBlank() || port <= 0) {
            Log.i(TAG, "Proxy not configured (host='$host' port=$port); direct connection")
            return
        }
        val proxyType: TdApi.ProxyType = when (type.lowercase()) {
            "http"       -> TdApi.ProxyTypeHttp(username, password, /*httpOnly*/ false)
            "socks5", "" -> TdApi.ProxyTypeSocks5(username, password)
            else -> {
                Log.w(TAG, "Unknown PROXY_TYPE='$type'; falling back to SOCKS5")
                TdApi.ProxyTypeSocks5(username, password)
            }
        }
        Log.i(TAG, "Enabling $type proxy → $host:$port")
        // Positional args: TdApi.java is compiled without -parameters,
        // so Kotlin can't resolve the parameter names (sees p0/p1/p2/p3).
        send(TdApi.AddProxy(host, port, true, proxyType))
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
            // Fire-and-forget: TDLib's result callback is still wired up
            // internally; for queries like RequestQrCodeAuthentication,
            // failure surfaces as the same auth state staying in
            // WaitPhoneNumber (no UpdateAuthorizationState transition),
            // which we can detect by our pendingQrRequest guard never
            // clearing. Log here so logcat shows what we asked for.
            Log.d(TAG, "send(fire-and-forget) ${query.javaClass.simpleName}")
            c.send(query, null)
        } else {
            c.send(query, { obj ->
                if (obj is TdApi.Error) {
                    Log.w(TAG, "send() ${query.javaClass.simpleName} → TdApi.Error ${obj.code}: ${obj.message}")
                }
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
     * Real sign-out: stop TDLib, wipe DB, restart fresh.
     *
     * D-031: only the database directory is wiped (auth keys, secret
     * chat keys, drafts, contacts cache — everything that ties the local
     * install to the account). The file cache directory is left intact;
     * TDLib re-validates cached files on next login by file_id and
     * re-downloads stale ones. Cache eviction is a separate, explicit
     * user action — see [clearCache].
     *
     * The wipe happens here (NOT in [startWithPaths]) so a normal app
     * launch preserves the existing login session — see D-029.
     *
     * @return true if the existing client was stopped + restarted, false
     *         if there was no client and we only wiped DB.
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
            Log.w(TAG, "realSignOut() called but no TDLib client; just wiping DB")
            wipeDatabase(databaseDirectory)
            return false
        }
        Log.i(TAG, "Real sign-out: stop + wipe DB + restart")
        stop()
        wipeDatabase(databaseDirectory)
        startWithPaths(context, apiId, apiHash, databaseDirectory, filesDirectory)
        return true
    }

    /**
     * Wipe only the TDLib database directory. The file cache is left
     * intact — see D-031.
     */
    private fun wipeDatabase(databaseDirectory: String) {
        runCatching {
            val dbDir = File(databaseDirectory)
            if (dbDir.exists()) {
                val deleted = dbDir.deleteRecursively()
                Log.i(TAG, "Wiped TDLib database: $deleted (path=$databaseDirectory)")
            }
        }.onFailure { Log.w(TAG, "Failed to wipe TDLib DB", it) }
    }

    /**
     * Clear TDLib's file cache directory. Used by Settings → "清理缓存",
     * NOT by [realSignOut]. Runs async on Dispatchers.IO and reports
     * progress via [cacheClearProgress] (null = idle, 0..1 = in progress).
     *
     * The caller MUST have already stopped TDLib via [stop] (or [realSignOut])
     * before invoking this — otherwise TDLib may still be writing into the
     * directory mid-delete and we'll race against it.
     *
     * Walks the tree and deletes files one at a time (not deleteRecursively)
     * so we can report meaningful progress to a TV UI that may otherwise
     * sit on a frozen spinner for 30s+ on a fully populated cache.
     *
     * @return the launched Job. Cancel to abort the cleanup.
     */
    fun clearCache(filesDirectory: String): Job = scope.launch(Dispatchers.IO) {
        _cacheClearProgress.value = 0f
        try {
            val dir = File(filesDirectory)
            if (!dir.exists()) {
                Log.i(TAG, "clearCache: dir does not exist ($filesDirectory), nothing to do")
                _cacheClearProgress.value = 1f
                return@launch
            }
            // Collect first, then delete — mutating the tree while walking
            // it is a classic ConcurrentModificationException hazard.
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            val total = files.size
            if (total == 0) {
                Log.i(TAG, "clearCache: empty cache ($filesDirectory)")
                _cacheClearProgress.value = 1f
                return@launch
            }
            Log.i(TAG, "clearCache: deleting $total files from $filesDirectory")
            files.forEachIndexed { i, f ->
                runCatching { f.delete() }.onFailure {
                    Log.w(TAG, "clearCache: failed to delete ${f.absolutePath}", it)
                }
                // Throttle progress updates — every 200 files, or on the last
                // one. 200 is empirically small enough that the UI feels
                // responsive but large enough not to flood the main
                // dispatcher with StateFlow emissions.
                if (i % 200 == 0 || i == total - 1) {
                    _cacheClearProgress.value = (i + 1).toFloat() / total
                }
            }
            // The empty subdirectories themselves are harmless and TDLib
            // will recreate them on next login, but delete them anyway for
            // tidiness.
            runCatching { dir.deleteRecursively() }
            Log.i(TAG, "clearCache: done")
        } catch (e: Throwable) {
            Log.w(TAG, "clearCache: failed", e)
        } finally {
            // Always end in the "done" state so the UI can dismiss its
            // progress indicator — even on partial failure, we want to
            // surface that cleanup ran (rather than staying stuck at 0
            // forever).
            _cacheClearProgress.value = 1f
        }
    }

    /**
     * Walk the cache directory and sum file sizes. Synchronous; intended
     * to be called from a background dispatcher by the UI layer (e.g. when
     * the Settings screen mounts). Returns 0 if the directory doesn't exist.
     */
    fun cacheSize(filesDirectory: String): Long {
        val dir = File(filesDirectory)
        if (!dir.exists()) return 0L
        return runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .onFailure { Log.w(TAG, "cacheSize: walk failed", it) }
            .getOrDefault(0L)
    }

    /**
     * Reset [cacheClearProgress] back to null (idle). Call after the UI
     * has acknowledged the "done" state and dismissed its progress UI.
     */
    fun resetCacheClearProgress() {
        _cacheClearProgress.value = null
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