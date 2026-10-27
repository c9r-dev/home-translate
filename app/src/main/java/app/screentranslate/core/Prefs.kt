package app.screentranslate.core

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** What the OCR step looks for. Each non-Latin recognizer also reads Latin text. */
enum class Script(val label: String) {
    LATIN("Latin (English, French, Spanish, …)"),
    CHINESE("Chinese"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    DEVANAGARI("Devanagari (Hindi, Marathi, …)"),
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var targetLang: String
        get() = sp.getString("target", null) ?: defaultTarget()
        set(v) = sp.edit().putString("target", v).apply()

    var script: Script
        get() = runCatching { Script.valueOf(sp.getString("script", null)!!) }.getOrDefault(Script.LATIN)
        set(v) = sp.edit().putString("script", v.name).apply()

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
