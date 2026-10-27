package app.screentranslate.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** A piece of on-screen text, positioned in screen pixels. */
data class SourceText(val rect: Rect, val text: String, val lines: Int)

/** A translated block ready to draw, in screen pixels. */
class RenderBlock(
    val rect: RectF,
    val original: String,
    val translated: String,
    val lines: Int,
    val bg: Int,
    val fg: Int,
)

/** On-device OCR + language detection + translation, shared by the assistant and live mode. */
object TranslateEngine {
    private val recognizers = HashMap<Script, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val modelLock = Mutex()
    private val cache = LruCache<String, String>(500)
    private val langId by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build()
        )
    }

    private fun recognizer(script: Script): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(script) {
            when (script) {
                Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            }
        }
    }

    /** OCR a screenshot. [scale] converts bitmap pixels to screen pixels. */
    suspend fun recognize(bitmap: Bitmap, scale: Float, script: Script, exclude: List<Rect> = emptyList()): List<SourceText> {
        val result = recognizer(script).process(InputImage.fromBitmap(bitmap, 0)).await()
        val cjk = script == Script.CHINESE || script == Script.JAPANESE
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val r = Rect(
                (box.left * scale).toInt(), (box.top * scale).toInt(),
                (box.right * scale).toInt(), (box.bottom * scale).toInt()
            )
            if (exclude.any { Rect.intersects(it, r) }) return@mapNotNull null
            val text = block.lines.joinToString(if (cjk) "" else " ") { it.text }.trim()
            if (text.isEmpty() || text.none { it.isLetter() }) null
            else SourceText(r, text, block.lines.size.coerceAtLeast(1))
        }
    }

    /**
     * Translate each piece of text into [target]. Text already in the target language, or in a
     * language ML Kit can't translate, is dropped. [bitmap] (screen-sized after [scale]) is used
     * to pick box colours that match the app underneath.
     */
    suspend fun translate(
        context: Context,
        items: List<SourceText>,
        target: String,
        bitmap: Bitmap?,
        scale: Float,
        onStatus: (String) -> Unit,
    ): List<RenderBlock> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        val dominant = identify(items.joinToString("\n") { it.text })
        val sources = items.map { item ->
            async {
                val lang = if (item.text.length < 12) dominant else identify(item.text) ?: dominant
                // Drop region/script subtags (e.g. zh-Hant -> zh); romanised text like zh-Latn can't be translated.
                lang?.takeUnless { it.endsWith("-Latn") }?.let { TranslateLanguage.fromLanguageTag(it.substringBefore('-')) }
            }
        }.awaitAll()

        val wifiOnly = Prefs(context).wifiOnly
        for (src in sources.filterNotNull().toSet()) {
            if (src != target) ensureModel(src, target, wifiOnly, onStatus)
        }

        items.indices.map { i ->
            async {
                val item = items[i]
                val src = sources[i] ?: return@async null
                if (src == target || "$src>$target" !in ready) return@async null
                val out = translateText(src, target, item.text) ?: return@async null
                if (out.equals(item.text, ignoreCase = true)) return@async null
                val bg = bitmap?.let { sampleBackground(it, item.rect, scale) } ?: Color.WHITE
                RenderBlock(RectF(item.rect), item.text, out, item.lines, bg, contrastOn(bg))
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun identify(text: String): String? =
        runCatching { langId.identifyLanguage(text).await() }.getOrNull()?.takeIf { it != "und" }

    private fun translator(src: String, target: String): Translator = synchronized(translators) {
        translators.getOrPut("$src>$target") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build()
            )
        }
    }

    private suspend fun ensureModel(src: String, target: String, wifiOnly: Boolean, onStatus: (String) -> Unit) {
        val key = "$src>$target"
        modelLock.withLock {
            if (key in ready) return
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            onStatus("Getting ${languageName(src)} → ${languageName(target)} language pack…")
            runCatching { translator(src, target).downloadModelIfNeeded(conditions).await() }
                .onSuccess { ready += key }
                .onFailure { onStatus("Couldn't download the ${languageName(src)} language pack. Check your connection.") }
        }
    }

    private suspend fun translateText(src: String, target: String, text: String): String? {
        val key = "$src>$target|$text"
        cache.get(key)?.let { return it }
        return runCatching { translator(src, target).translate(text).await() }.getOrNull()?.also { cache.put(key, it) }
    }

    /** Median colour of a thin ring just outside the text box, i.e. the background behind the text. */
    private fun sampleBackground(bmp: Bitmap, screenRect: Rect, scale: Float): Int {
        val l = (screenRect.left / scale).toInt() - 3
        val t = (screenRect.top / scale).toInt() - 3
        val r = (screenRect.right / scale).toInt() + 3
        val b = (screenRect.bottom / scale).toInt() + 3
        val px = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) px += bmp.getPixel(x, y)
        }
        val stepX = ((r - l) / 24).coerceAtLeast(1)
        val stepY = ((b - t) / 8).coerceAtLeast(1)
        for (x in l..r step stepX) { add(x, t); add(x, b) }
        for (y in t..b step stepY) { add(l, y); add(r, y) }
        if (px.isEmpty()) return Color.WHITE
        fun median(f: (Int) -> Int) = px.map(f).sorted()[px.size / 2]
        return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    }

    private fun contrastOn(bg: Int): Int {
        val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (lum > 140) Color.BLACK else Color.WHITE
    }
}
