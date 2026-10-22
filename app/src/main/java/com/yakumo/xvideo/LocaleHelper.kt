package com.yakumo.xvideo

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    const val PREFS = "xvideo_settings"
    /** null/missing = follow system; "zh" / "en" = fixed override after user tapped toggle */
    const val KEY_LANG = "app_language_override"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved override, or null if still following system */
    fun getOverride(ctx: Context): String? {
        val sp = prefs(ctx)
        var v = sp.getString(KEY_LANG, null)
        // migrate from v1.4 key "app_language" (system|zh|en)
        if (v.isNullOrBlank()) {
            val legacy = sp.getString("app_language", null)
            when (legacy) {
                "zh", "en" -> {
                    sp.edit().putString(KEY_LANG, legacy).remove("app_language").apply()
                    v = legacy
                }
                else -> if (legacy != null) sp.edit().remove("app_language").apply()
            }
        }
        return when (v) {
            "zh", "en" -> v
            else -> null
        }
    }

    fun setOverride(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(KEY_LANG, tag).apply()
    }

    fun wrap(ctx: Context): Context {
        val locale = when (getOverride(ctx)) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> systemLocale(ctx)
        }
        return updateResources(ctx, locale)
    }

    fun systemLocale(ctx: Context): Locale {
        val cfg = ctx.resources.configuration
        val loc = if (Build.VERSION.SDK_INT >= 24) cfg.locales[0] else @Suppress("DEPRECATION") cfg.locale
        return if (loc.language.startsWith("zh", ignoreCase = true)) Locale.SIMPLIFIED_CHINESE
        else Locale.ENGLISH
    }

    fun effectiveIsChinese(ctx: Context): Boolean {
        return when (getOverride(ctx)) {
            "zh" -> true
            "en" -> false
            else -> systemLocale(ctx).language.startsWith("zh")
        }
    }

    /** Toggle: Chinese UI → switch to en; English UI → switch to zh. Persists override. */
    fun toggle(ctx: Context): String {
        val next = if (effectiveIsChinese(ctx)) "en" else "zh"
        setOverride(ctx, next)
        return next
    }

    private fun updateResources(ctx: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }
}
