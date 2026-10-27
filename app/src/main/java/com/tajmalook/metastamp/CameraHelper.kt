package com.tajmalook.metastamp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Listens for the phone's position while the camera app is open,
 * keeping the most accurate recent fix.
 */
class LocationTracker(ctx: Context) {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var best: Location? = null
        private set
    private var running = false
    private val listeners = mutableListOf<(Location) -> Unit>()

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = offer(l)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    fun isLocationOn(): Boolean = try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.getLastKnownLocation(p)?.let { if (fresh(it, 2 * 60_000)) offer(it) }
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        listeners.clear()
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
    }

    /** Calls back once a usable fix exists (immediately if one already does). */
    fun whenReady(f: (Location) -> Unit) {
        val b = best
        if (b != null && b.accuracy <= 50f) f(b) else listeners += f
    }

    private fun fresh(l: Location, maxAgeMs: Long) =
        System.currentTimeMillis() - l.time < maxAgeMs

    private fun offer(l: Location) {
        val b = best
        val better = b == null || !fresh(b, 60_000) || l.accuracy <= b.accuracy || fresh(l, 5_000) && l.accuracy < 30f
        if (better) best = l
        val cur = best ?: return
        if (cur.accuracy <= 50f && listeners.isNotEmpty()) {
            val ls = listeners.toList(); listeners.clear()
            ls.forEach { it(cur) }
        }
    }
}

object CameraHelper {

    fun dir(ctx: Context) = File(ctx.cacheDir, "camera").apply { mkdirs() }

    fun newFile(ctx: Context): File {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return File(dir(ctx), name)
    }

    /** Deletes leftover camera files older than a day. */
    fun cleanup(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        dir(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * Writes capture time, GPS (when the camera didn't already add it) and device
     * into the new photo, so it carries full metadata even before stamping.
     */
    fun writeCaptureMetadata(file: File, taken: Date, loc: Location?) {
        try {
            val ex = ExifInterface(file.absolutePath)
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            if (ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
                val ds = fmt.format(taken)
                ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ds)
                ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, ds)
                ex.setAttribute(ExifInterface.TAG_DATETIME, ds)
                val off = TimeZone.getDefault().getOffset(taken.time) / 60000
                val tz = String.format(Locale.US, "%s%02d:%02d", if (off < 0) "-" else "+", Math.abs(off) / 60, Math.abs(off) % 60)
                ex.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tz)
            }
            if (ex.getAttribute(ExifInterface.TAG_MAKE) == null) ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            if (ex.getAttribute(ExifInterface.TAG_MODEL) == null) ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            val existing = ex.latLong
            val hasGps = existing != null && !(existing[0] == 0.0 && existing[1] == 0.0)
            if (!hasGps && loc != null) {
                ex.setGpsInfo(loc)
            }
            ex.saveAttributes()
        } catch (_: Exception) {}
    }
}
