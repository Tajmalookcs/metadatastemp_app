package com.tajmalook.metastamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object Stamp {

    private val dateFmt = SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.ENGLISH)

    fun formatDate(d: Date): String =
        dateFmt.format(d).replace("AM", "am").replace("PM", "pm")

    private val stampDateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.ENGLISH)

    /** "Wednesday, 28/10/2026 04:11 PM GMT +05:00" */
    fun formatStampDate(d: Date): String {
        val off = java.util.TimeZone.getDefault().getOffset(d.time) / 60000
        val sign = if (off < 0) "-" else "+"
        val a = kotlin.math.abs(off)
        return stampDateFmt.format(d) + String.format(Locale.US, " GMT %s%02d:%02d", sign, a / 60, a % 60)
    }

    fun formatLatLng(lat: Double, lng: Double): String =
        String.format(Locale.US, "Lat %.6f°  Long %.6f°", lat, lng)

    /** Draws the stamp onto a mutable copy of [src] and returns it. */
    fun render(src: Bitmap, item: PhotoItem, showMap: Boolean): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        // Unit scale: reference design is 1080px wide.
        val u = min(w / 1080f, h / 720f)

        val pad = 18f * u
        val mapSize = if (showMap) 210f * u else 0f
        val gap = if (showMap) 24f * u else 0f
        val textLeft = pad + mapSize + gap
        val textWidth = (w - pad - textLeft).toInt().coerceAtLeast(10)

        val address = item.address?.takeIf { it.isNotBlank() }
            ?: item.lat?.let { lat -> item.lng?.let { PlusCode.short(lat, it) } }
            ?: ""
        val latLine = if (item.lat != null && item.lng != null)
            formatLatLng(item.lat!!, item.lng!!) else ""
        val dateLine = formatStampDate(item.date)

        val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f * u
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(2f * u, 0f, u, 0x66000000)
        }
        val addrLayout = StaticLayout.Builder
            .obtain(address, 0, address.length, addrPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(2f * u, 1f)
            .setMaxLines(4)
            .build()

        val latPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8E8E8.toInt()
            textSize = 23f * u
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * u, 0f, u, 0x66000000)
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7D046.toInt()
            textSize = 30f * u
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f * u, 0f, 1.5f * u, 0xCC000000.toInt())
        }
        // Shrink the date to fit on one line if needed.
        while (datePaint.measureText(dateLine) > textWidth && datePaint.textSize > 12f * u) {
            datePaint.textSize -= u
        }

        val latH = if (latLine.isEmpty()) 0f else latPaint.fontSpacing
        val dateFm = datePaint.fontMetrics
        val dateH = dateFm.descent - dateFm.ascent
        val spacing1 = if (address.isEmpty()) 0f else 10f * u
        val spacing2 = 10f * u
        val contentH = addrLayout.height + spacing1 + latH + spacing2 + dateH

        val bandH = max(mapSize, contentH) + pad * 2
        val bandTop = h - bandH
        c.drawRect(0f, bandTop, w, h, Paint().apply { color = 0x80000000.toInt() })

        // Map thumbnail
        if (showMap) {
            val mTop = bandTop + (bandH - mapSize) / 2f
            val rect = RectF(pad, mTop, pad + mapSize, mTop + mapSize)
            c.drawRect(
                RectF(rect.left - 3f * u, rect.top - 3f * u, rect.right + 3f * u, rect.bottom + 3f * u),
                Paint().apply { color = Color.WHITE }
            )
            val tile = item.mapTile
            if (tile != null) {
                c.drawBitmap(tile, Rect(0, 0, tile.width, tile.height), rect,
                    Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                drawFallbackMap(c, rect, u)
            }
            drawPin(c, rect.centerX(), rect.centerY(), mapSize, u)
            if (tile != null) {
                val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF444444.toInt(); textSize = 11f * u
                }
                val label = "© OpenStreetMap"
                val tw = ap.measureText(label)
                val bx = rect.right - tw - 6f * u
                val by = rect.bottom - 4f * u
                c.drawRect(bx - 3f * u, by - ap.textSize, rect.right, rect.bottom,
                    Paint().apply { color = 0xCCFFFFFF.toInt() })
                c.drawText(label, bx, by - 2f * u, ap)
            }
        }

        // Text block, centered vertically in band
        val cx = textLeft + textWidth / 2f
        var y = bandTop + (bandH - contentH) / 2f
        if (address.isNotEmpty()) {
            c.save()
            c.translate(textLeft, y)
            addrLayout.draw(c)
            c.restore()
            y += addrLayout.height + spacing1
        }
        if (latLine.isNotEmpty()) {
            c.drawText(latLine, cx, y - latPaint.fontMetrics.ascent, latPaint)
            y += latH
        }
        y += spacing2
        c.drawText(dateLine, cx, y - dateFm.ascent, datePaint)

        return out
    }

    private fun drawFallbackMap(c: Canvas, r: RectF, u: Float) {
        c.save()
        c.clipRect(r)
        c.drawRect(r, Paint().apply { color = 0xFFF2EFE9.toInt() })
        val s = r.width()
        val water = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA9D3EE.toInt(); strokeWidth = s * 0.025f; style = Paint.Style.STROKE
        }
        c.drawLine(r.left, r.top + s * 0.62f, r.right, r.top + s * 0.02f, water)
        val roadEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD9CF86.toInt(); strokeWidth = s * 0.11f; style = Paint.Style.STROKE
        }
        val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF7F3B5.toInt(); strokeWidth = s * 0.085f; style = Paint.Style.STROKE
        }
        c.drawLine(r.left, r.top + s * 0.92f, r.right, r.top + s * 0.3f, roadEdge)
        c.drawLine(r.left, r.top + s * 0.92f, r.right, r.top + s * 0.3f, road)
        c.restore()
    }

    private fun drawPin(c: Canvas, tipX: Float, tipY: Float, mapSize: Float, u: Float) {
        val r = mapSize * 0.11f
        val cy = tipY - r * 2.3f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
        c.drawOval(RectF(tipX - r * 0.6f, tipY - r * 0.18f, tipX + r * 0.6f, tipY + r * 0.18f), shadow)
        val path = Path().apply {
            moveTo(tipX, tipY)
            cubicTo(tipX - r * 0.35f, tipY - r * 0.9f, tipX - r, cy + r * 0.75f, tipX - r, cy)
            arcTo(RectF(tipX - r, cy - r, tipX + r, cy + r), 180f, 180f, false)
            cubicTo(tipX + r, cy + r * 0.75f, tipX + r * 0.35f, tipY - r * 0.9f, tipX, tipY)
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA61D1D.toInt(); style = Paint.Style.STROKE; strokeWidth = max(1f, 1.5f * u)
        })
        c.drawCircle(tipX, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E1616.toInt() })
    }
}

/** Open Location Code (Google Plus Code) encoder. */
object PlusCode {
    private const val A = "23456789CFGHJMPQRVWX"

    fun full(latIn: Double, lngIn: Double): String {
        val lat = latIn.coerceIn(-90.0, 90.0 - 1e-10)
        val lng = ((lngIn + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        var la = Math.floor(Math.round((lat + 90.0) * 8000.0 * 5.0 * 1e6) / 1e6).toLong()
        var lo = Math.floor(Math.round((lng + 180.0) * 8000.0 * 4.0 * 1e6) / 1e6).toLong()
        val grid = A[((la % 5) * 4 + (lo % 4)).toInt()]
        la /= 5; lo /= 4
        val sb = StringBuilder()
        repeat(5) {
            sb.insert(0, A[(lo % 20).toInt()])
            sb.insert(0, A[(la % 20).toInt()])
            la /= 20; lo /= 20
        }
        return sb.substring(0, 8) + "+" + sb.substring(8) + grid
    }

    /** Short form like "3765+9X3" (drops the 4-char area prefix). */
    fun short(lat: Double, lng: Double): String = full(lat, lng).substring(4)
}
