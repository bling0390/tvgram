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

object TdClient {

    private const val TAG = "TdClient"

    @Volatile private var started = false

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    private val _cacheClearProgress = MutableStateFlow<Float?>(null)
    val cacheClearProgress: StateFlow<Float?> = _cacheClearProgress.asStateFlow()

    private var client: Client? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

        val c = Client.create(
            { obj -> dispatchUpdate(obj) },
            { e -> Log.w("TDLib-update", "update handler threw", e) },
            { e -> Log.w("TDLib-default", "default handler threw", e) },
        )
        client = c
        Log.i(TAG, "TDLib client created; sending setTdlibParameters")

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
            "http"       -> TdApi.ProxyTypeHttp(username, password,  false)
            "socks5", "" -> TdApi.ProxyTypeSocks5(username, password)
            else -> {
                Log.w(TAG, "Unknown PROXY_TYPE='$type'; falling back to SOCKS5")
                TdApi.ProxyTypeSocks5(username, password)
            }
        }
        Log.i(TAG, "Enabling $type proxy → $host:$port")

        send(TdApi.AddProxy(host, port, true, proxyType))
    }

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

    private fun wipeDatabase(databaseDirectory: String) {
        runCatching {
            val dbDir = File(databaseDirectory)
            if (dbDir.exists()) {
                val deleted = dbDir.deleteRecursively()
                Log.i(TAG, "Wiped TDLib database: $deleted (path=$databaseDirectory)")
            }
        }.onFailure { Log.w(TAG, "Failed to wipe TDLib DB", it) }
    }

    fun clearCache(filesDirectory: String): Job = scope.launch(Dispatchers.IO) {
        _cacheClearProgress.value = 0f
        try {
            val dir = File(filesDirectory)
            if (!dir.exists()) {
                Log.i(TAG, "clearCache: dir does not exist ($filesDirectory), nothing to do")
                _cacheClearProgress.value = 1f
                return@launch
            }

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

                if (i % 200 == 0 || i == total - 1) {
                    _cacheClearProgress.value = (i + 1).toFloat() / total
                }
            }
            runCatching { dir.deleteRecursively() }
            Log.i(TAG, "clearCache: done")
        } catch (e: Throwable) {
            Log.w(TAG, "clearCache: failed", e)
        } finally {

            _cacheClearProgress.value = 1f
        }
    }

    fun cacheSize(filesDirectory: String): Long {
        val dir = File(filesDirectory)
        if (!dir.exists()) return 0L
        return runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .onFailure { Log.w(TAG, "cacheSize: walk failed", it) }
            .getOrDefault(0L)
    }

    fun resetCacheClearProgress() {
        _cacheClearProgress.value = null
    }

    private fun dispatchUpdate(obj: TdApi.Object) {
        val emitted = _updates.tryEmit(obj)
        if (!emitted) {
            Log.w(TAG, "Update flow buffer overflow, dropped: ${obj.javaClass.simpleName}")
        }
    }

    internal val ioScope: CoroutineScope get() = scope
}
