package app.screentranslate.assist

import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.service.voice.VoiceInteractionSession
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.screentranslate.core.Prefs
import app.screentranslate.core.RenderBlock
import app.screentranslate.core.SourceText
import app.screentranslate.core.TranslateEngine
import app.screentranslate.core.TranslationView
import app.screentranslate.core.allLanguages
import app.screentranslate.core.languageName
import app.screentranslate.live.LiveStartActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What appears when you hold home: the current screen, with every bit of text replaced by its
 * translation, like the old Assistant "Translate" button.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val prefs = Prefs(context)
    private var scope = newScope()
    private var job: Job? = null

    private lateinit var view: TranslationView
    private lateinit var status: TextView
    private lateinit var langButton: Button

    private var shot: Bitmap? = null
    private var structureText: List<SourceText> = emptyList()
    private var gotScreenshotCallback = false
    private var isShowing = false

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    override fun onCreate() {
        super.onCreate()
        window.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        }
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context)
        view = TranslationView(context).apply {
            // Tap the picture to flip between original and translation.
            setOnClickListener { showOriginal = !showOriginal }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0xE0202124.toInt())
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(48) })

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = pill(0xF0202124.toInt())
        }
        langButton = barButton("→ " + languageName(prefs.targetLang)) { pickLanguage(it) }
        bar.addView(langButton)
        bar.addView(barButton("Original") {}.apply {
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> view.showOriginal = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.showOriginal = false; v.performClick() }
                }
                true
            }
        })
        bar.addView(barButton("Copy") { copyAll() })
        bar.addView(barButton("Live") {
            context.startActivity(Intent(context, LiveStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        })
        bar.addView(barButton("✕") { hide() })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(40) })
        return root
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }

    private fun barButton(label: String, onClick: (View) -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener(onClick)
    }

    // Reset in onPrepareShow, not onShow: on some phones the screenshot arrives before onShow,
    // and clearing state there threw it away (works once, then later holds show nothing).
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        scope.cancel()
        scope = newScope()
        shot = null
        structureText = emptyList()
        gotScreenshotCallback = false
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        isShowing = true
        langButton.text = "→ " + languageName(prefs.targetLang)
        val early = shot
        if (early != null) { showShot(early); return } // screenshot arrived before onShow
        view.screenshot = null
        view.dimBackground = false
        view.blocks = emptyList()
        view.showOriginal = false
        setStatus("Reading screen…")
        if (gotScreenshotCallback) { useStructureOrExplain(); return } // a null screenshot came early
        // If no screenshot ever arrives (setting off, or the app blocks screenshots), fall back to text.
        scope.launch {
            delay(1500)
            if (!gotScreenshotCallback) useStructureOrExplain()
        }
    }

    override fun onHide() {
        super.onHide()
        isShowing = false
        scope.cancel()
        // Throw this session away so the next hold-home starts completely fresh.
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure ?: return
        structureText = collectText(structure)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: android.app.assist.AssistContent?) {
        if (structure != null) structureText = collectText(structure)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        gotScreenshotCallback = true
        if (screenshot == null) {
            useStructureOrExplain()
            return
        }
        val soft = if (screenshot.config == Bitmap.Config.HARDWARE) screenshot.copy(Bitmap.Config.ARGB_8888, false) else screenshot
        shot = soft
        if (isShowing) showShot(soft)
    }

    private fun showShot(soft: Bitmap) {
        view.dimBackground = false
        view.showOriginal = false
        view.screenshotScale = screenWidth().toFloat() / soft.width
        view.screenshot = soft
        run()
    }

    private fun screenWidth(): Int {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.width()
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.widthPixels
    }

    private fun useStructureOrExplain() {
        if (shot != null || !isShowing) return
        if (structureText.isNotEmpty()) {
            view.dimBackground = true
            run()
        } else {
            setStatus("Couldn't see the screen. Turn on \"Use screenshot\" in Settings › Apps › Default apps › Digital assistant app.")
        }
    }

    private fun run() {
        job?.cancel()
        view.blocks = emptyList()
        job = scope.launch {
            val target = prefs.targetLang
            val bmp = shot
            val items = if (bmp != null) {
                setStatus("Reading screen…")
                val ocr = try {
                    TranslateEngine.recognize(bmp, view.screenshotScale, prefs.script)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Fall back to the text the app exposes, and say why OCR didn't work.
                    if (structureText.isNotEmpty()) structureText
                    else {
                        setStatus("Couldn't read the screen: ${e.message ?: e.javaClass.simpleName}")
                        return@launch
                    }
                }
                // Some screens OCR to nothing (e.g. blocked capture); use the app's own text if it shared any.
                ocr.ifEmpty { structureText }
            } else structureText
            if (items.isEmpty()) {
                setStatus(
                    if (bmp != null && isBlank(bmp)) "The screenshot came through blank. This app may block screenshots."
                    else "No text found on screen"
                )
                return@launch
            }
            setStatus("Translating…")
            val blocks = TranslateEngine.translate(context, items, target, bmp, view.screenshotScale) { msg ->
                scope.launch { setStatus(msg) }
            }
            view.blocks = blocks
            if (blocks.isEmpty()) setStatus("Nothing to translate: it's already in ${languageName(target)}")
            else setStatus(null)
        }
    }

    /** True if the screenshot is a single flat colour (e.g. black because the app blocks capture). */
    private fun isBlank(bmp: Bitmap): Boolean {
        val first = bmp.getPixel(0, 0)
        val stepX = (bmp.width / 16).coerceAtLeast(1)
        val stepY = (bmp.height / 16).coerceAtLeast(1)
        for (y in 0 until bmp.height step stepY) for (x in 0 until bmp.width step stepX) {
            if (bmp.getPixel(x, y) != first) return false
        }
        return true
    }

    private fun setStatus(msg: String?) {
        status.text = msg
        status.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun pickLanguage(anchor: View) {
        val langs = allLanguages()
        PopupMenu(context, anchor).apply {
            langs.forEachIndexed { i, code -> menu.add(0, i, i, languageName(code)) }
            setOnMenuItemClickListener {
                prefs.targetLang = langs[it.itemId]
                langButton.text = "→ " + languageName(prefs.targetLang)
                run()
                true
            }
        }.show()
    }

    private fun copyAll() {
        val text = view.blocks.sortedWith(compareBy<RenderBlock>({ it.rect.top }, { it.rect.left }))
            .joinToString("\n\n") { it.translated }
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Translation", text))
        Toast.makeText(context, "Translation copied", Toast.LENGTH_SHORT).show()
    }

    /** Text the foreground app exposes to assistants, with screen positions. */
    private fun collectText(structure: AssistStructure): List<SourceText> {
        val out = ArrayList<SourceText>()
        fun walk(node: AssistStructure.ViewNode, ox: Int, oy: Int) {
            if (node.visibility != View.VISIBLE) return
            val x = ox + node.left
            val y = oy + node.top
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && node.width > 0 && node.height > 0 && text.any { it.isLetter() }) {
                out += SourceText(Rect(x, y, x + node.width, y + node.height), text, text.count { it == '\n' } + 1)
            }
            for (i in 0 until node.childCount) walk(node.getChildAt(i), x - node.scrollX, y - node.scrollY)
        }
        for (i in 0 until structure.windowNodeCount) {
            val w = structure.getWindowNodeAt(i)
            walk(w.rootViewNode, w.left, w.top)
        }
        return out
    }
}
