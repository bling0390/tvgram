package tv.telegram

import android.app.Application
import android.util.Log
import java.io.File
import tv.telegram.td.TdClient

/**
 * Application class.
 *
 * Bootstraps TDLib on startup (D-002, D-027). Single-user; login state
 * persists in filesDir/tdlib (D-019).
 */
class TgTvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TgTvApp started; BuildConfig.TG_API_ID=${BuildConfig.TG_API_ID}")

        val apiId = BuildConfig.TG_API_ID
        if (apiId == 0) {
            Log.e(TAG, "TG_API_ID missing or invalid in BuildConfig; TDLib will not start")
            return
        }
        val apiHash = BuildConfig.TG_API_HASH
        if (apiHash.isBlank()) {
            Log.e(TAG, "TG_API_HASH missing in BuildConfig; TDLib will not start")
            return
        }

        val tdlibDir = File(filesDir, "tdlib").apply { mkdirs() }
        val tdlibFilesDir = File(filesDir, "tdlib-files").apply { mkdirs() }

        TdClient.startWithPaths(
            context = this,
            apiId = apiId,
            apiHash = apiHash,
            databaseDirectory = tdlibDir.absolutePath,
            filesDirectory    = tdlibFilesDir.absolutePath,
        )
    }

    companion object {
        private const val TAG = "TvgramApp"
    }
}
