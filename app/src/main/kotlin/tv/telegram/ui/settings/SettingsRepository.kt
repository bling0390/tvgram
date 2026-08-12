package tv.telegram.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class ThemeMode { Dark, Light, System }

enum class Language { English, SimplifiedChinese, TraditionalChinese }

object SettingsRepository {
    private const val PREFS_NAME = "tvgram_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun applyLocale(ctx: Context, lang: Language) {
        val tag = lang.toBcp47()
        val list = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }
}

fun Language.toBcp47(): String = when (this) {
    Language.English -> "en"
    Language.SimplifiedChinese -> "zh-Hans"
    Language.TraditionalChinese -> "zh-Hant"
}
