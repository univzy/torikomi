package univzy.torikomi.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREF = "torikomi_locale"
    private const val KEY = "lang_tag" // "" = follow system

    fun getPersistedTag(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun setLanguageTag(context: Context, tag: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Wrap a base context with the persisted locale. Call from Activity.attachBaseContext. */
    fun wrap(base: Context): Context {
        val tag = getPersistedTag(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
