package tv.telegram.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * ThemeMode — Dark / Light / System.
 * v0.9.0: System delegates to the OS's day/night setting.
 */
enum class ThemeMode { Dark, Light, System }

/**
 * Language — UI display language. v0.9.0 ships three locales:
 * English, Simplified Chinese, Traditional Chinese.
 */
enum class Language { English, SimplifiedChinese, TraditionalChinese }

/**
 * SettingsRepository — SharedPreferences-backed storage for user preferences.
 *
 * Why SharedPreferences instead of DataStore: prefs is dead simple for two
 * enums and avoids pulling in androidx.datastore as a dependency for v0.8.0.
 * If we add more settings in v0.9+ we'll migrate to DataStore.
 */
object SettingsRepository {
    private const val PREFS_NAME = "tvgram_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Read both settings; defaults if unset. Called once at MainViewModel init. */
    fun hydrate(ctx: Context): Pair<ThemeMode, Language> {
        val p = prefs(ctx)
        val theme = runCatching { ThemeMode.valueOf(p.getString(KEY_THEME, ThemeMode.Dark.name)!!) }
            .getOrDefault(ThemeMode.Dark)
        val lang = runCatching { Language.valueOf(p.getString(KEY_LANGUAGE, Language.English.name)!!) }
            .getOrDefault(Language.English)
        return theme to lang
    }

    fun setTheme(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setLanguage(ctx: Context, lang: Language) {
        prefs(ctx).edit().putString(KEY_LANGUAGE, lang.name).apply()
    }

    /**
     * Apply [lang] to the running process. Idempotent.
     * On Android 13+ this triggers a configuration change and the
     * resources are re-resolved from the matching values-* folder.
     * On older versions AppCompatDelegate keeps the locale for new
     * resources and an Activity recreate is required; the caller
     * (SettingsScreen) is responsible for the recreate.
     */
    fun applyLocale(ctx: Context, lang: Language) {
        val tag = lang.toBcp47()
        val list = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }
}

/** Convert a Language enum value to a BCP-47 language tag. */
fun Language.toBcp47(): String = when (this) {
    Language.English -> "en"
    Language.SimplifiedChinese -> "zh-Hans"
    Language.TraditionalChinese -> "zh-Hant"
}
