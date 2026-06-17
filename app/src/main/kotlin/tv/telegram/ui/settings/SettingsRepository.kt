package tv.telegram.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * ThemeMode — currently only Dark. Light is a v0.8.1+ addition (we don't
 * have a Light color scheme designed yet).
 */
enum class ThemeMode { Dark }

/**
 * Language — UI display language. v0.8.0 ships only English; this enum
 * exists so we can add more languages without an API break.
 */
enum class Language { English }

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
}
