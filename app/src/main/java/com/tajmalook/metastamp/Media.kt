package com.tajmalook.metastamp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.location.Geocoder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

class PhotoItem(val uri: Uri) {
    var name: String = "photo"
    var readUri: Uri = uri
    var lat: Double? = null
    var lng: Double? = null
    var gpsFromExif = false
    var date: Date = Date()
    var dateFromExif = false
    var orientation: Int = ExifInterface.ORIENTATION_NORMAL
    var address: String? = null
    var addressEdited = false
    var mapTile: Bitmap? = null
    var preview: Bitmap? = null
    var thumb: Bitmap? = null
    var loaded = false
    var saved = false
    var make: String? = null
    var model: String? = null
}

object MetaReader {

    private val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** Uri that gives un-redacted location, when the system allows it. */
    private fun originalUri(ctx: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val media = if (uri.authority == MediaStore.AUTHORITY) uri
            else MediaStore.getMediaUri(ctx, uri)
            media?.let { MediaStore.setRequireOriginal(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun readExif(ctx: Context, uri: Uri): ExifInterface? = try {
        ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (e: Exception) {
        null
    }

    fun load(ctx: Context, item: PhotoItem) {
        item.name = displayName(ctx, item.uri)
        var exif: ExifInterface? = null
        val orig = originalUri(ctx, item.uri)
        if (orig != null) {
            exif = readExif(ctx, orig)
            if (exif != null) item.readUri = orig
        }
        val fallback = readExif(ctx, item.uri)
        if (exif == null || (exif.latLong == null && fallback?.latLong != null)) exif = fallback

        if (exif != null) {
            exif.latLong?.let { ll ->
                if (!(ll[0] == 0.0 && ll[1] == 0.0)) {
                    item.lat = ll[0]; item.lng = ll[1]; item.gpsFromExif = true
                }
            }
            val ds = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (ds != null) {
                try {
                    exifDate.parse(ds.trim())?.let { item.date = it; item.dateFromExif = true }
                } catch (_: Exception) {}
            }
            item.orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            item.make = exif.getAttribute(ExifInterface.TAG_MAKE)
            item.model = exif.getAttribute(ExifInterface.TAG_MODEL)
        }
        if (!item.dateFromExif) lastModified(ctx, item.uri)?.let { item.date = it }

        item.preview = decode(ctx, item, 1600)
        item.thumb = item.preview?.let { thumbOf(it) }
        item.loaded = true
    }

    private fun thumbOf(b: Bitmap): Bitmap {
        val s = 160
        val side = minOf(b.width, b.height)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            b,
            Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2),
            Rect(0, 0, s, s), null
        )
        return out
    }

    fun decode(ctx: Context, item: PhotoItem, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, item)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = open(ctx, item)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge > maxEdge) {
            val f = maxEdge.toFloat() / longEdge
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }
        return rotate(bmp, item.orientation)
    }

    private fun open(ctx: Context, item: PhotoItem): InputStream? = try {
        ctx.contentResolver.openInputStream(item.readUri)
    } catch (e: Exception) {
        try { ctx.contentResolver.openInputStream(item.uri) } catch (e2: Exception) { null }
    }

    private fun rotate(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "photo.jpg"
    } catch (e: Exception) { "photo.jpg" }

    private fun lastModified(ctx: Context, uri: Uri): Date? = try {
        ctx.contentResolver.query(uri, arrayOf("last_modified"), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) Date(it.getLong(0)) else null
        }
    } catch (e: Exception) { null }

    /** Reverse geocode to "PLUSCODE, street, town, ..." style. Returns null if unavailable. */
    @Suppress("DEPRECATION")
    fun geocode(ctx: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val list = Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lng, 1)
            val a = list?.firstOrNull() ?: return null
            var line = a.getAddressLine(0) ?: listOfNotNull(
                a.thoroughfare, a.subLocality, a.locality, a.subAdminArea, a.adminArea, a.countryName
            ).joinToString(", ")
            line = line.replace(Regex(",?\\s*\\d{5}(?=,|$)"), "") // drop postal codes
            if (!Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+").containsMatchIn(line)) {
                line = PlusCode.short(lat, lng) + ", " + line
            }
            line
        } catch (e: Exception) {
            null
        }
    }

    /** Fetches a 256px OpenStreetMap square centered on the point (zoom 16). */
    fun mapTile(lat: Double, lng: Double, zoom: Int = 16): Bitmap? {
        return try {
            val n = (1 shl zoom).toDouble()
            val px = (lng + 180.0) / 360.0 * n * 256.0
            val latR = lat * PI / 180.0
            val py = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n * 256.0
            val size = 256
            val left = px - size / 2.0
            val top = py - size / 2.0
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val tx0 = floor(left / 256.0).toInt()
            val ty0 = floor(top / 256.0).toInt()
            var got = 0
            for (tx in tx0..tx0 + 1) for (ty in ty0..ty0 + 1) {
                val tile = fetchTile(zoom, tx, ty) ?: continue
                c.drawBitmap(tile, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
                got++
            }
            if (got == 0) null else out
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        val xx = ((x % n) + n) % n
        val conn = URL("https://tile.openstreetmap.org/$z/$xx/$y.png").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent",
                "MetaStamp/1.0 (Android; https://github.com/Tajmalookcs/metadatastemp_app)")
            if (conn.responseCode != 200) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

object Saver {

    /** Renders full resolution, writes JPEG with metadata, adds to gallery. Returns saved name. */
    fun save(ctx: Context, item: PhotoItem, showMap: Boolean): String {
        val full = MetaReader.decode(ctx, item, 4096) ?: throw IllegalStateException("Couldn't open ${item.name}")
        val stamped = Stamp.render(full, item, showMap)
        val base = item.name.substringBeforeLast('.')
        val outName = "${base}_stamped_${System.currentTimeMillis() % 100000}.jpg"

        val tmp = File(ctx.cacheDir, outName)
        FileOutputStream(tmp).use { stamped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        stamped.recycle()
        if (full != stamped) full.recycle()

        try {
            val ex = ExifInterface(tmp.absolutePath)
            val ds = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(item.date)
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ds)
            ex.setAttribute(ExifInterface.TAG_DATETIME, ds)
            item.make?.let { ex.setAttribute(ExifInterface.TAG_MAKE, it) }
            item.model?.let { ex.setAttribute(ExifInterface.TAG_MODEL, it) }
            if (item.lat != null && item.lng != null) ex.setLatLong(item.lat!!, item.lng!!)
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            ex.saveAttributes()
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MetaStamp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val dest = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gallery refused the file")
            resolver.openOutputStream(dest)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(dest, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MetaStamp")
            dir.mkdirs()
            val f = File(dir, outName)
            tmp.copyTo(f, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        }
        tmp.delete()
        item.saved = true
        return outName
    }
}
