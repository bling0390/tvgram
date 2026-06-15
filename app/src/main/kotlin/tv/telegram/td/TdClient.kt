package tv.telegram.td

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/**
 * TdClient — drives TDLib via the JSON interface (`libtdjson.so`).
 *
 * Architecture (D-027):
 *   - libtdjson.so is the TDLib JSON interface (bundled in jniLibs/<abi>/).
 *   - We extract it from the APK at first run to filesDir (Android can't
 *     dlopen a .so inside a ZIP easily on all devices).
 *   - We spawn the TDLib JSON client as a child process: `libtdjson.so` reads
 *     JSON requests from stdin, writes JSON responses to stdout. We communicate
 *     with it by writing one JSON object per line to stdin, and reading
 *     one JSON object per line from stdout.
 *   - All TDLib events (updates) are JSON objects on stdout, just like responses.
 *     We treat both the same way: parse → re-broadcast via [updates].
 *
 * Threading:
 *   - The child process runs in its own native thread.
 *   - We have a single background thread that reads stdout and pumps updates
 *     into a SharedFlow for ViewModels to collect on Main.
 *   - All writes to stdin happen on whatever thread calls [send] / [sendAsync]
 *     (TDLib JSON client is single-threaded; concurrent writes are safe because
 *     the OutputStream write is atomic for small payloads, and we serialize via
 *     a synchronized block).
 */
object TdClient {

    private const val TAG = "TdClient"

    @Volatile private var started = false

    private val _updates = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<JSONObject> = _updates.asSharedFlow()

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nextQueryId = AtomicLong(1L)

    private lateinit var stdin: java.io.OutputStream

    /**
     * Start the TDLib JSON client. Idempotent.
     *
     * Steps:
     *   1. Extract libtdjson.so from the APK into filesDir
     *   2. Spawn it as a child process
     *   3. Start the stdout reader
     *   4. Send setTdlibParameters
     */
    fun startWithPaths(
        context: android.content.Context,
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

        val soFile = extractNativeLib(context)
        Log.i(TAG, "TDLib native: ${soFile.absolutePath} (${soFile.length()} B)")

        // Make the .so executable
        soFile.setExecutable(true, false)

        val builder = ProcessBuilder(soFile.absolutePath)
            .redirectErrorStream(false)
        val p = builder.start()
        process = p
        stdin = p.outputStream

        // stderr reader — log native errors
        scope.launch {
            BufferedReader(InputStreamReader(p.errorStream)).useLines { lines ->
                lines.forEach { line ->
                    Log.w("TDLib-stderr", line)
                }
            }
        }

        // stdout reader — main event pump
        scope.launch {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        try {
                            val obj = JSONObject(line)
                            val emitted = _updates.tryEmit(obj)
                            if (!emitted) {
                                Log.w(TAG, "Update flow buffer overflow, dropped: ${obj.optString("@type")}")
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to parse TDLib line: ${line.take(200)}", e)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "stdout reader crashed", e)
            }
            Log.w(TAG, "TDLib process exited (code=${p.exitValue()})")
        }

        // Send setTdlibParameters
        sendSetTdlibParameters(apiId, apiHash, databaseDirectory, filesDirectory)
    }

    private fun sendSetTdlibParameters(
        apiId: Int, apiHash: String,
        databaseDirectory: String, filesDirectory: String,
    ) {
        val params = JSONObject().apply {
            put("api_id", apiId)
            put("api_hash", apiHash)
            put("database_directory", databaseDirectory)
            put("files_directory", filesDirectory)
            put("use_file_database", true)
            put("use_chat_info_database", true)
            put("use_message_database", true)
            put("use_secret_chats", false)
            put("system_language_code", "en")
            put("device_model", "Sony Bravia (Android TV)")
            put("system_version", "Android TV")
            put("application_version", "0.1.0")
        }
        send(JSONObject().apply {
            put("@type", "setTdlibParameters")
            put("parameters", params)
        })
        Log.i(TAG, "Sent setTdlibParameters; waiting for updateAuthorizationState")
    }

    /**
     * Send a fire-and-forget query (e.g. setTdlibParameters, getChats).
     * Use this when you don't need a direct response — responses come
     * via the [updates] flow tagged with `@extra` you set.
     */
    fun send(query: JSONObject) {
        if (!started) {
            Log.w(TAG, "send() before start(); dropping ${query.optString("@type")}")
            return
        }
        val line = query.toString() + "\n"
        // TDLib JSON client is single-threaded; we serialize writes here.
        synchronized(this::class) {
            try {
                stdin.write(line.toByteArray(Charsets.UTF_8))
                stdin.flush()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to write to TDLib stdin", e)
            }
        }
    }

    /**
     * Send a request that expects a single direct response. Coroutine-based.
     * Matches responses by the `@extra` field we set.
     *
     * Most ViewModels won't need this — they collect [updates] and dispatch
     * by `@type`. Use execute() for one-shot lookups like getChat(id).
     */
    suspend fun execute(query: JSONObject, timeoutMs: Long = 10_000L): JSONObject? {
        val extra = "__req_${nextQueryId.getAndIncrement()}__"
        query.put("@extra", extra)

        // Subscribe before sending
        val resultDeferred = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        val job = scope.launch {
            updates.collect { obj ->
                if (obj.optString("@extra") == extra) {
                    resultDeferred.complete(obj)
                }
            }
        }
        try {
            send(query)
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                resultDeferred.await()
            }
        } finally {
            job.cancel()
        }
    }

    /**
     * Resolve the on-disk path of libtdjson.so. Since we ship .so in
     * jniLibs/<abi>/, Android extracts it to nativeLibraryDir automatically.
     * We just need to find it; no manual extract needed (modern Android, 7+).
     */
    private fun extractNativeLib(context: android.content.Context): File {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
            ?: error("Device reports no supported ABIs")
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val nativeLibDir = appInfo.nativeLibraryDir
            ?: error("Device exposes no nativeLibraryDir")
        val so = File(nativeLibDir, "libtdjson.so")
        if (!so.exists()) {
            error("libtdjson.so not present in nativeLibraryDir=$nativeLibDir (ABI=$abi)")
        }
        if (!so.canExecute()) {
            Log.w(TAG, "libtdjson.so not executable; setting +x")
            so.setExecutable(true, false)
        }
        return so
    }
}
