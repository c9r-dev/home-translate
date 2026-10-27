package app.screentranslate.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View

/**
 * Draws translated text boxes at their real screen positions. Optionally draws the screenshot
 * underneath, so the assistant view looks like the app it was opened over.
 */
class TranslationView(context: Context) : View(context) {
    var screenshot: Bitmap? = null
        set(v) { field = v; invalidate() }

    /** Screenshot pixels → screen pixels. */
    var screenshotScale = 1f

    /** Dim whatever is underneath (used when there is no screenshot to draw). */
    var dimBackground = false
        set(v) { field = v; invalidate() }

    var blocks: List<RenderBlock> = emptyList()
        set(v) { field = v; layouts.clear(); invalidate() }

    var showOriginal = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val layouts = HashMap<RenderBlock, StaticLayout>()
    private val loc = IntArray(2)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        screenshot?.let {
            canvas.save()
            canvas.scale(screenshotScale, screenshotScale)
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.restore()
        }
        if (dimBackground) canvas.drawColor(0xB0000000.toInt())
        if (!showOriginal) blocks.forEach { draw(canvas, it) }
        canvas.restore()
    }

    private fun draw(canvas: Canvas, b: RenderBlock) {
        val layout = layouts.getOrPut(b) { fit(b) }
        val pad = 2 * density
        val h = maxOf(b.rect.height(), layout.height.toFloat())
        box.set(b.rect.left - pad, b.rect.top - pad, b.rect.right + pad, b.rect.top + h + pad)
        boxPaint.color = b.bg
        boxPaint.alpha = 245
        canvas.drawRoundRect(box, 3 * density, 3 * density, boxPaint)
        canvas.save()
        canvas.translate(b.rect.left, b.rect.top + maxOf(0f, (b.rect.height() - layout.height) / 2))
        layout.draw(canvas)
        canvas.restore()
    }

    /** Largest text size whose wrapped layout fits the original text's box. */
    private fun fit(b: RenderBlock): StaticLayout {
        val width = b.rect.width().toInt().coerceAtLeast(1)
        val minSize = 9 * density
        var size = (b.rect.height() / b.lines * 0.78f).coerceIn(minSize, 40 * density)
        while (true) {
            val paint = TextPaint(textPaint).apply { textSize = size; color = b.fg }
            val layout = StaticLayout.Builder.obtain(b.translated, 0, b.translated.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.95f)
                .build()
            if (layout.height <= b.rect.height() || size <= minSize) return layout
            size = maxOf(minSize, size * 0.9f)
        }
    }

    init {
        textPaint.color = Color.BLACK
    }
}
