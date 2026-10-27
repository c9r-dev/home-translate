package app.screentranslate.live

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.screentranslate.R
import app.screentranslate.core.Prefs
import app.screentranslate.core.TranslateEngine
import app.screentranslate.core.TranslationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Live translate: watches the screen and keeps a translation overlay on top of whatever app is
 * open. The overlay hides itself as soon as the screen changes (scrolling, new page) and comes
 * back translated once things settle, so it never shows stale text in the wrong place.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    private var overlay: TranslationView? = null
    private var bubble: TextView? = null
    private val bubbleParams = WindowManager.LayoutParams()
    @Volatile private var paused = false
    @Volatile private var forceRefresh = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground before touching the projection token (Android 14+).
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification("Starting…"))
        }

        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        val mp = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent!!.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)

        createCapture()
        addOverlay()
        addBubble()
        running = true
        scope.launch { loop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        overlay?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        display?.release()
        reader?.close()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    // ---- capture ----

    private fun realSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    @SuppressLint("WrongConstant")
    private fun createCapture() {
        val (w, h) = realSize()
        width = w; height = h
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "screen-translate", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main
        )
    }

    /** Follow rotation: the virtual display has to match the real one. */
    @SuppressLint("WrongConstant")
    private fun checkResize(): Boolean {
        val (w, h) = realSize()
        if (w == width && h == height) return false
        width = w; height = h
        val old = reader
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display?.resize(w, h, resources.displayMetrics.densityDpi)
        display?.surface = reader!!.surface
        old?.close()
        return true
    }

    /** Newest frame since the last call, or null if the screen hasn't changed. */
    private fun grab(): Bitmap? {
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padded.width == image.width) return padded
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } finally {
            image.close()
        }
    }

    /** Tiny greyscale thumbnail used to tell whether the screen changed. */
    private fun signature(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, SIG_W, SIG_H, true)
        val px = IntArray(SIG_W * SIG_H)
        small.getPixels(px, 0, SIG_W, 0, 0, SIG_W, SIG_H)
        if (small !== bmp) small.recycle()
        return FloatArray(px.size) { i ->
            val c = px[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }
    }

    /** Fraction of thumbnail cells that changed noticeably. */
    private fun diff(a: FloatArray, b: FloatArray): Float {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 0.08f) n++
        return n.toFloat() / a.size
    }

    // ---- main loop ----

    private suspend fun loop() {
        var frame: Bitmap? = null
        var prevSig: FloatArray? = null
        var overlayRef: FloatArray? = null // what the screen looks like with our overlay on it
        var overlayShown = false
        var stable = 0
        var dirty = true

        fun clearOverlay() {
            overlay?.blocks = emptyList()
            overlayShown = false
            overlayRef = null
            prevSig = null
            stable = 0
            dirty = true
        }

        while (scope.isActive) {
            delay(TICK_MS)
            if (paused) continue
            if (forceRefresh) { forceRefresh = false; clearOverlay() }
            if (checkResize()) { clearOverlay(); continue }

            val fresh = withContext(Dispatchers.Default) { grab() }
            if (fresh != null) { frame?.recycle(); frame = fresh }
            val cur = frame ?: continue
            if (fresh == null && !dirty) continue // nothing new on screen
            val sig = prevSig.takeIf { fresh == null } ?: withContext(Dispatchers.Default) { signature(cur) }

            if (overlayShown) {
                val ref = overlayRef
                if (ref == null) {
                    if (fresh != null) overlayRef = sig
                } else if (fresh != null && diff(sig, ref) > CHANGE_THRESHOLD) {
                    clearOverlay() // the app underneath moved; drop the stale boxes
                }
                continue
            }

            val prev = prevSig
            if (prev != null && diff(sig, prev) <= CHANGE_THRESHOLD) stable++ else {
                if (prev != null) dirty = true
                stable = 0
            }
            prevSig = sig

            if (dirty && stable >= 1) {
                dirty = false
                updateNotification("Translating…")
                val items = try {
                    TranslateEngine.recognize(this@LiveService, cur, 1f, prefs.script, listOfNotNull(bubbleRect())) { updateNotification(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateNotification("Couldn't read the screen: ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
                val blocks = TranslateEngine.translate(this, items, prefs.targetLang, cur, 1f) { updateNotification(it) }
                updateNotification(if (blocks.isEmpty()) "Watching the screen" else "Showing ${blocks.size} translations")
                if (blocks.isNotEmpty() && !paused) {
                    overlay?.blocks = blocks
                    overlayShown = true
                    overlayRef = null
                }
            }
        }
    }

    // ---- windows ----

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun addOverlay() {
        val v = TranslationView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android only lets touches pass through another app's overlay when it is at most 80% opaque.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "Home Translate overlay"
        }
        wm.addView(v, lp)
        overlay = v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val b = TextView(this).apply {
            text = "文A"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1A73E8.toInt()) }
            elevation = 6 * d
        }
        bubbleParams.apply {
            width = size; height = size
            type = overlayType()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - (8 * d).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }
        // Tap: pause/resume. Long-press: stop. Drag: move.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > 10 * d || abs(dy) > 10 * d) {
                        moved = true
                        bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                        wm.updateViewLayout(b, bubbleParams)
                    }
                }
            }
            false
        }
        b.setOnClickListener { if (!moved) togglePause() }
        b.setOnLongClickListener { if (!moved) stopSelf(); true }
        wm.addView(b, bubbleParams)
        bubble = b
    }

    private fun bubbleRect(): Rect? = bubble?.let {
        val loc = IntArray(2); it.getLocationOnScreen(loc)
        Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
    }

    private fun togglePause() {
        paused = !paused
        overlay?.blocks = emptyList()
        overlay?.visibility = if (paused) View.GONE else View.VISIBLE
        bubble?.alpha = if (paused) 0.45f else 1f
        updateNotification(if (paused) "Paused, tap the bubble to resume" else "Watching the screen")
        if (!paused) forceRefresh = true
    }

    // ---- notification ----

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live translate", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Live translate is on")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "app.screentranslate.STOP"
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 350L
        private const val SIG_W = 36
        private const val SIG_H = 72
        private const val CHANGE_THRESHOLD = 0.015f

        @Volatile var running = false
            private set
    }
}
