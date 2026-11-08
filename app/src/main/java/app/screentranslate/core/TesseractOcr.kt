package app.screentranslate.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tesseract OCR for scripts ML Kit can't read (Thai, Cyrillic, Arabic, Greek, …). Only used when
 * the user picks one of these as the source language. Each language's data (1–4 MB) downloads
 * once on first use and is kept in app storage.
 */
object TesseractOcr {
    /** ML Kit translate language -> Tesseract language data name. */
    private val CODES = mapOf(
        "th" to "tha", "ru" to "rus", "uk" to "ukr", "bg" to "bul", "be" to "bel", "mk" to "mkd",
        "el" to "ell", "ar" to "ara", "fa" to "fas", "ur" to "urd", "he" to "heb", "ka" to "kat",
        "ta" to "tam", "te" to "tel", "kn" to "kan", "gu" to "guj", "bn" to "ben",
    )
    private const val DATA_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/%s.traineddata"
    private const val MIN_CONFIDENCE = 40f

    private val lock = Mutex()
    private var api: TessBaseAPI? = null
    private var apiLangs: String? = null

    fun handles(lang: String?): Boolean = lang != null && lang in CODES

    suspend fun recognize(
        context: Context,
        lang: String,
        bitmap: Bitmap,
        scale: Float,
        exclude: List<Rect>,
        onStatus: (String) -> Unit,
    ): List<SourceText> = lock.withLock {
        withContext(Dispatchers.IO) {
            // Read the language plus English, since most screens mix in some English UI.
            val langs = "${CODES.getValue(lang)}+eng"
            val root = File(context.filesDir, "tesseract")
            for (code in langs.split('+')) ensureData(File(root, "tessdata"), code) {
                onStatus("Getting ${languageName(lang)} text reader (one time)…")
            }
            val tess = api?.takeIf { apiLangs == langs } ?: run {
                api?.recycle()
                TessBaseAPI().also {
                    check(it.init(root.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY)) { "Couldn't start the ${languageName(lang)} text reader" }
                    it.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                    api = it
                    apiLangs = langs
                }
            }
            onStatus("Reading screen…")
            tess.setImage(bitmap)
            val out = ArrayList<SourceText>()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            val it = tess.resultIterator
            try {
                it.begin()
                do {
                    val text = it.getUTF8Text(level)?.trim().orEmpty()
                    val box = it.getBoundingRect(level) ?: continue
                    if (text.isEmpty() || text.none { c -> c.isLetter() } || it.confidence(level) < MIN_CONFIDENCE) continue
                    val r = Rect((box.left * scale).toInt(), (box.top * scale).toInt(), (box.right * scale).toInt(), (box.bottom * scale).toInt())
                    if (exclude.none { e -> Rect.intersects(e, r) }) out += SourceText(r, text, 1)
                } while (it.next(level))
            } finally {
                it.delete()
                tess.clear()
            }
            out
        }
    }

    private fun ensureData(dir: File, code: String, onDownload: () -> Unit) {
        val file = File(dir, "$code.traineddata")
        if (file.length() > 0) return
        onDownload()
        dir.mkdirs()
        val tmp = File(dir, "$code.part")
        val conn = URL(DATA_URL.format(code)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            check(conn.responseCode == 200) { "Couldn't download the text reader (HTTP ${conn.responseCode})" }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(file)) { "Couldn't save the text reader" }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }
}
