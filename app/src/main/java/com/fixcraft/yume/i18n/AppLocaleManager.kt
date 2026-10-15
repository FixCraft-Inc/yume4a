package com.fixcraft.yume.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.ResolvedAppLanguage
import java.util.Locale

object AppLocaleManager {
    fun wrap(base: Context): Context {
        val locale = resolveLocale(base, AppSettingsStore.readLanguage(base)) ?: return base
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    fun current(base: Context): Context = wrap(base)

    fun resolveLanguage(base: Context, language: AppLanguage = AppSettingsStore.readLanguage(base)): ResolvedAppLanguage {
        return language.resolve(resolveSystemLocale(base), communityLanguageTags)
    }

    fun resolveLocale(base: Context, language: AppLanguage = AppSettingsStore.readLanguage(base)): Locale? {
        return when (val resolved = resolveLanguage(base, language)) {
            ResolvedAppLanguage.English -> Locale.forLanguageTag(ResolvedAppLanguage.English.localeTag)
            ResolvedAppLanguage.Russian -> Locale.forLanguageTag(ResolvedAppLanguage.Russian.localeTag)
            ResolvedAppLanguage.Japanese -> Locale.forLanguageTag(ResolvedAppLanguage.Japanese.localeTag)
            ResolvedAppLanguage.Ukrainian -> Locale.forLanguageTag(ResolvedAppLanguage.Ukrainian.localeTag)
            ResolvedAppLanguage.ChineseSimplified -> Locale.forLanguageTag(ResolvedAppLanguage.ChineseSimplified.localeTag)
            is ResolvedAppLanguage.AutoTranslated -> Locale.forLanguageTag(resolved.localeTag)
            is ResolvedAppLanguage.Community -> Locale.forLanguageTag(resolved.localeTag)
        }
    }

    fun resolveSystemLocale(base: Context): Locale {
        return runCatching {
            base.resources.configuration.locales[0]
        }.getOrNull() ?: Locale.getDefault()
    }

    fun getString(base: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        return current(base).resources.getString(resId, *formatArgs)
    }
}
