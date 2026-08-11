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
 * D-029: replaces D-027's ProcessBuilder(libtdjson.so) (which was ET_DYN,
 * not ET_EXEC). The [Client.create] loads libtdjni.so, spawns a worker
 * thread, and we surface two channels: broadcast updates on [updates],
 * direct responses via [send] / [execute].
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

    /** D-031. null = idle, 0..1 = in progress, 1f = finished. */
    private val _cacheClearProgress = MutableStateFlow<Float?>(null)
    val cacheClearProgress: StateFlow<Float?> = _cacheClearProgress.asStateFlow()

    private var client: Client? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start the TDLib client. Idempotent. Dirs are NOT wiped — login
     * state persists across app restarts; [realSignOut] does the wipe.
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

        runCatching {
            File(databaseDirectory).mkdirs()
            File(filesDirectory).mkdirs()
        }.onFailure { Log.w(TAG, "Failed to ensure TDLib dirs", it) }

        // Native lib is loaded by Client.<clinit> via System.loadLibrary("tdjni").
        val c = Client.create(
            { obj -> dispatchUpdate(obj) },
            { e -> Log.w("TDLib-update", "update handler threw", e) },
            { e -> Log.w("TDLib-default", "default handler threw", e) },
        )
        client = c
        Log.i(TAG, "TDLib client created; sending setTdlibParameters")

        // `this.field = param` disambiguates from the same-named receiver field.
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
     * (Settings → WiFi → Manual proxy, or `adb shell settings put global
     * http_proxy ...`). It uses native sockets and only respects proxies
     * configured via [TdApi.AddProxy]. For users behind a firewall this
     * MUST be called or TDLib reaches DCs directly and hangs.
     *
     * Must be called AFTER [startWithPaths]. No-op if [host] blank or
     * [port] 0. Loopback in emulator = `10.0.2.2` (NOT `127.0.0.1`).
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
     * Send a query with optional direct-response handler. Most callers
     * leave [onResult] null and listen on [updates] instead.
     */
    fun send(query: TdApi.Function, onResult: ((TdApi.Object) -> Unit)? = null) {
        val c = client
        if (c == null) {
            Log.w(TAG, "send() before start(); dropping ${query.javaClass.simpleName}")
            return
        }
        if (onResult == null) {
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

    /** Suspend until we get a direct response to [query] (or [timeoutMs] elapses). */
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
     * Stop the TDLib client. Sends a `close` query, then calls
     * [Client.close] to release native resources (blocks until the
     * worker thread exits). After this, restart via [startWithPaths].
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
     * chat keys, drafts, contacts cache). The file cache directory is
     * left intact — TDLib re-validates cached files on next login by
     * file_id. Cache eviction is explicit user action via [clearCache].
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

    /** Wipe only the TDLib database directory. See D-031 on file cache. */
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
     * NOT by [realSignOut]. Async on Dispatchers.IO with progress via
     * [cacheClearProgress].
     *
     * The caller MUST have stopped TDLib via [stop] before invoking this
     * — otherwise TDLib is still writing into the directory mid-delete
     * and we'll race against it.
     *
     * One-at-a-time deletes (not deleteRecursively) so the TV UI sees
     * real progress instead of a frozen spinner for 30s+ on a full cache.
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
                // Throttle progress — every 200 files. 200 is small enough
                // for UI responsiveness, large enough not to flood the
                // main dispatcher with StateFlow emissions.
                if (i % 200 == 0 || i == total - 1) {
                    _cacheClearProgress.value = (i + 1).toFloat() / total
                }
            }
            runCatching { dir.deleteRecursively() }
            Log.i(TAG, "clearCache: done")
        } catch (e: Throwable) {
            Log.w(TAG, "clearCache: failed", e)
        } finally {
            // Always end in "done" — even on partial failure, surface
            // that cleanup ran (rather than sticking at 0).
            _cacheClearProgress.value = 1f
        }
    }

    /** Sum of file sizes in the cache directory. Returns 0 if dir missing. */
    fun cacheSize(filesDirectory: String): Long {
        val dir = File(filesDirectory)
        if (!dir.exists()) return 0L
        return runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .onFailure { Log.w(TAG, "cacheSize: walk failed", it) }
            .getOrDefault(0L)
    }

    /** Reset [cacheClearProgress] to null (idle). */
    fun resetCacheClearProgress() {
        _cacheClearProgress.value = null
    }

    /** Updates broadcast on the SharedFlow for ViewModels to collect. */
    private fun dispatchUpdate(obj: TdApi.Object) {
        val emitted = _updates.tryEmit(obj)
        if (!emitted) {
            Log.w(TAG, "Update flow buffer overflow, dropped: ${obj.javaClass.simpleName}")
        }
    }

    /** Internal: exposes scope for repos that want a SupervisorJob lifecycle. */
    internal val ioScope: CoroutineScope get() = scope
}