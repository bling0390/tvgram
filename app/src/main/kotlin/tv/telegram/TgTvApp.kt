package tv.telegram

import android.app.Application
import android.util.Log
import java.io.File
import tv.telegram.td.TdClient

/**
 * Application class.
 *
 * Bootstraps TDLib on startup (D-002, D-029). Single-user; login state
 * persists in filesDir/tdlib and filesDir/tdlib-files. On normal app
 * launches the database is NOT wiped — only [MainViewModel.realSignOut]
 * (via [TdClient.realSignOut]) deletes it (D-029 replaces the v0.9.0
 * behavior where every launch wiped state and forced a re-login).
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

        // Wire up outbound proxy (TDLib ignores Android HTTP proxy).
        // No-op when PROXY_HOST is blank in local.properties. Required
        // for users behind a firewall (China, Iran, etc.).
        // See TdClient.enableProxy docs for the 10.0.2.2 emulator quirk.
        TdClient.enableProxy(
            host     = BuildConfig.PROXY_HOST,
            port     = BuildConfig.PROXY_PORT,
            type     = BuildConfig.PROXY_TYPE,
            username = BuildConfig.PROXY_USER,
            password = BuildConfig.PROXY_PASS,
        )
    }

    companion object {
        private const val TAG = "TvgramApp"
    }
}
