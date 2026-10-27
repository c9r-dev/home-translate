package app.screentranslate.core

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** What the OCR step looks for. Each non-Latin recognizer also reads Latin text. */
enum class Script(val label: String) {
    CHINESE("Chinese + Latin (default)"),
    LATIN("Latin only (English, French, Spanish, …)"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    DEVANAGARI("Devanagari (Hindi, Marathi, …)"),
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var targetLang: String
        get() = sp.getString("target", null) ?: defaultTarget()
        set(v) = sp.edit().putString("target", v).apply()

    // New key so existing installs move off the old Latin-only default, which skipped Chinese text.
    var script: Script
        get() = runCatching { Script.valueOf(sp.getString("script2", null)!!) }.getOrDefault(Script.CHINESE)
        set(v) = sp.edit().putString("script2", v.name).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()

    private fun defaultTarget(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH
}

fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase() }

fun allLanguages(): List<String> =
    TranslateLanguage.getAllLanguages().sortedBy { languageName(it) }
