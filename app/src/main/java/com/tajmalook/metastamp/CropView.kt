package com.tajmalook.metastamp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a bitmap with a draggable crop frame.
 * The crop is kept in normalized image coordinates (0..1).
 */
class CropView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }

    /** Width/height ratio in image pixels, or null for free. */
    var aspect: Float? = null
        set(v) { field = v; fitAspect(); invalidate() }

    /** Normalized crop rect. */
    val crop = RectF(0f, 0f, 1f, 1f)

    private val dp = resources.displayMetrics.density
    private val imgRect = RectF()
    private val dim = Paint().apply { color = 0xA0000000.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * dp
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt(); strokeWidth = 1 * dp
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF7D046.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * dp
        strokeCap = Paint.Cap.SQUARE
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setCrop(r: RectF?) {
        crop.set(r ?: RectF(0f, 0f, 1f, 1f))
        invalidate()
    }

    fun reset() {
        aspect = null
        crop.set(0f, 0f, 1f, 1f)
        invalidate()
    }

    private fun imgW() = bitmap?.width?.toFloat() ?: 1f
    private fun imgH() = bitmap?.height?.toFloat() ?: 1f

    /** Largest centered crop of the given aspect inside the current crop's center. */
    private fun fitAspect() {
        val a = aspect ?: return
        val iw = imgW(); val ih = imgH()
        // aspect in normalized units
        val na = a * ih / iw
        var w = 1f
        var h = w / na
        if (h > 1f) { h = 1f; w = h * na }
        val cx = crop.centerX().coerceIn(w / 2, 1 - w / 2)
        val cy = crop.centerY().coerceIn(h / 2, 1 - h / 2)
        crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        val pad = 20 * dp
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        val s = min(aw / b.width, ah / b.height)
        val w = b.width * s
        val h = b.height * s
        imgRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(b, null, imgRect, bmpPaint)

        val r = screenCrop()
        val p = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(imgRect, Path.Direction.CW)
            addRect(r, Path.Direction.CW)
        }
        c.drawPath(p, dim)
        for (i in 1..2) {
            val x = r.left + r.width() * i / 3
            val y = r.top + r.height() * i / 3
            c.drawLine(x, r.top, x, r.bottom, grid)
            c.drawLine(r.left, y, r.right, y, grid)
        }
        c.drawRect(r, frame)
        val L = 18 * dp
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            val dx = if (x == r.left) L else -L
            val dy = if (y == r.top) L else -L
            c.drawLine(x, y, x + dx, y, handle)
            c.drawLine(x, y, x, y + dy, handle)
        }
    }

    private fun screenCrop() = RectF(
        imgRect.left + crop.left * imgRect.width(),
        imgRect.top + crop.top * imgRect.height(),
        imgRect.left + crop.right * imgRect.width(),
        imgRect.top + crop.bottom * imgRect.height()
    )

    // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR, 6 L, 7 T, 8 R, 9 B
    private var mode = 0
    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || imgRect.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = hit(e.x, e.y)
                lastX = e.x; lastY = e.y
                if (mode != 0) parent?.requestDisallowInterceptTouchEvent(true)
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / imgRect.width()
                val dy = (e.y - lastY) / imgRect.height()
                lastX = e.x; lastY = e.y
                drag(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
        }
        return false
    }

    private fun hit(x: Float, y: Float): Int {
        val r = screenCrop()
        val t = 28 * dp
        val nl = abs(x - r.left) < t; val nr = abs(x - r.right) < t
        val nt = abs(y - r.top) < t; val nb = abs(y - r.bottom) < t
        val inY = y > r.top - t && y < r.bottom + t
        val inX = x > r.left - t && x < r.right + t
        return when {
            nl && nt -> 2; nr && nt -> 3; nl && nb -> 4; nr && nb -> 5
            aspect == null && nl && inY -> 6
            aspect == null && nt && inX -> 7
            aspect == null && nr && inY -> 8
            aspect == null && nb && inX -> 9
            r.contains(x, y) -> 1
            else -> 0
        }
    }

    private fun drag(dx: Float, dy: Float) {
        val minW = 60 * dp / imgRect.width()
        val minH = 60 * dp / imgRect.height()
        val c = RectF(crop)
        when (mode) {
            1 -> {
                val mx = dx.coerceIn(-c.left, 1 - c.right)
                val my = dy.coerceIn(-c.top, 1 - c.bottom)
                c.offset(mx, my)
            }
            2 -> { c.left += dx; c.top += dy }
            3 -> { c.right += dx; c.top += dy }
            4 -> { c.left += dx; c.bottom += dy }
            5 -> { c.right += dx; c.bottom += dy }
            6 -> c.left += dx
            7 -> c.top += dy
            8 -> c.right += dx
            9 -> c.bottom += dy
        }
        c.left = c.left.coerceIn(0f, c.right - minW)
        c.top = c.top.coerceIn(0f, c.bottom - minH)
        c.right = c.right.coerceIn(c.left + minW, 1f)
        c.bottom = c.bottom.coerceIn(c.top + minH, 1f)

        val a = aspect
        if (a != null && mode in 2..5) {
            val na = a * imgH() / imgW()
            // Keep the opposite corner fixed; derive height from width.
            var w = c.width()
            var h = w / na
            val anchorX = if (mode == 2 || mode == 4) crop.right else crop.left
            val anchorY = if (mode == 2 || mode == 3) crop.bottom else crop.top
            val maxW = if (mode == 2 || mode == 4) anchorX else 1 - anchorX
            val maxH = if (mode == 2 || mode == 3) anchorY else 1 - anchorY
            if (h > maxH) { h = maxH; w = h * na }
            if (w > maxW) { w = maxW; h = w / na }
            w = max(w, minW); h = w / na
            c.left = if (mode == 2 || mode == 4) anchorX - w else anchorX
            c.right = c.left + w
            c.top = if (mode == 2 || mode == 3) anchorY - h else anchorY
            c.bottom = c.top + h
        }
        crop.set(c)
    }
}
