package ca.dynamicsolutions.sonarbridge

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Android owns the app locale on API 33+; older versions share one preference with services. */
object AppLanguage {
    internal const val PREFERENCE = "ui_language"

    /** Native labels stay recognizable even before the user can read the current language. */
    val choices = linkedMapOf(
        "en-US" to "English (United States)",
        "en-CA" to "English (Canada)",
        "es-ES" to "Español (España)",
        "fr-FR" to "Français (France)",
        "fr-CA" to "Français (Canada)",
        "de-DE" to "Deutsch (Deutschland)",
        "zh-CN" to "简体中文（中国）",
        "pt-BR" to "Português (Brasil)",
        "ja-JP" to "日本語（日本）",
    )

    /** Accept supported regional aliases, but never map Traditional Chinese to Simplified. */
    fun canonicalTag(tag: String?): String? {
        if (tag.isNullOrBlank() || !tag.matches(Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})*"))) {
            return null
        }
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val script = locale.script
        return when (locale.language) {
            "en", "es", "fr", "de", "pt" -> {
                if (script.isNotEmpty() && script != "Latn") return null
                when (locale.language) {
                    "en" -> if (locale.country == "CA") "en-CA" else "en-US"
                    "fr" -> if (locale.country == "CA") "fr-CA" else "fr-FR"
                    "es" -> "es-ES"
                    "de" -> "de-DE"
                    else -> "pt-BR"
                }
            }
            "ja" -> if (script.isEmpty() || script == "Jpan") "ja-JP" else null
            "zh" -> when {
                script == "Hans" -> "zh-CN"
                script.isNotEmpty() -> null
                locale.country in setOf("TW", "HK", "MO") -> null
                else -> "zh-CN"
            }
            else -> null
        }
    }

    /** Resolve in device preference order; unsupported lists always have English wording. */
    fun resolve(tags: List<String>): String = tags.firstNotNullOfOrNull(::canonicalTag) ?: "en-US"

    /** Initialize before any Activity or Service. Migration never replaces a platform choice. */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val stored = prefs.all[PREFERENCE] as? String
        val tag = canonicalTag(stored)
        if (Build.VERSION.SDK_INT >= 33) {
            if (prefs.contains(PREFERENCE)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                if (manager.applicationLocales.isEmpty && tag != null) {
                    manager.applicationLocales = LocaleList.forLanguageTags(tag)
                }
                // Only clear after the framework setter returned successfully.
                prefs.edit().remove(PREFERENCE).apply()
            }
        } else {
            if (prefs.contains(PREFERENCE) && (stored == null || stored != tag)) {
                val edit = prefs.edit()
                if (tag == null) edit.remove(PREFERENCE) else edit.putString(PREFERENCE, tag)
                edit.apply()
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag ?: ""))
        }
    }

    /** Return the actual platform choice, including choices made outside the app. Null follows device. */
    fun selectedTag(context: Context): String? = if (Build.VERSION.SDK_INT >= 33) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) null else canonicalTag(locales[0].toLanguageTag())
    } else {
        canonicalTag(context.getSharedPreferences("cfg", Context.MODE_PRIVATE).all[PREFERENCE] as? String)
    }

    /**
     * A preference can change without changing the screen's effective locales. Android and
     * AppCompat overlay the app choice on device locales and remove duplicates; only a
     * different resulting list needs recreation and temporary draft-save suppression.
     */
    fun changesConfiguration(context: Context, tag: String?): Boolean {
        val manager = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)
        } else {
            null
        }
        val systemLocales = manager?.systemLocales
            ?: context.applicationContext.resources.configuration.locales
        val deviceLocales = (0 until systemLocales.size()).map { systemLocales[it] }
        fun overlay(locales: List<Locale>): LocaleList =
            LocaleList(*(locales + deviceLocales).distinct().toTypedArray())

        // On API 33+, compare the platform's lists rather than our wrapped Activity context.
        val currentLocales = if (manager != null) {
            val applicationLocales = manager.applicationLocales
            overlay((0 until applicationLocales.size()).map { applicationLocales[it] })
        } else {
            context.resources.configuration.locales
        }
        val selected = canonicalTag(tag)?.let(Locale::forLanguageTag)
        return overlay(listOfNotNull(selected)) != currentLocales
    }

    /** Persist one choice and let Android/AppCompat recreate activities; never touch the bridge. */
    fun select(context: Context, tag: String?) {
        val canonical = canonicalTag(tag)
        if (Build.VERSION.SDK_INT < 33) {
            val edit = context.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            if (canonical == null) edit.remove(PREFERENCE) else edit.putString(PREFERENCE, canonical)
            edit.apply()
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(canonical ?: ""))
    }

    /** The phone's regional locale is independent of the application's selected UI language. */
    fun regionalLocale(context: Context? = null): Locale {
        val locales = if (Build.VERSION.SDK_INT >= 33 && context != null) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        return if (locales.isEmpty) Locale.US else locales[0]
    }

    /** Localized resources for cold services too; AppCompat <=32 only wraps Activity contexts. */
    fun context(base: Context): Context {
        val selected = selectedTag(base)
        val tags = if (selected != null) listOf(selected) else {
            val locales = if (Build.VERSION.SDK_INT >= 33) {
                base.getSystemService(LocaleManager::class.java).systemLocales
            } else {
                Resources.getSystem().configuration.locales
            }
            (0 until locales.size()).map { locales[it].toLanguageTag() }
        }
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(resolve(tags)))
        return base.createConfigurationContext(configuration)
    }
}
