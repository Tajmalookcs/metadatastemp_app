package com.tajmalook.metastamp

import android.content.Context
import android.location.Address
import android.location.Geocoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Builds an address like
 * "RQMW+QVH, Jamia Masjid Bazar, Mamukanjan, Tandlianwala, Punjab, Pakistan"
 * by combining the phone's geocoder (several results), OpenStreetMap Nominatim and Photon.
 * Each level (street, area, town, tehsil, province, country) takes the first good name found.
 */
object AddressFinder {

    private enum class Level { STREET, AREA, TOWN, TEHSIL, DISTRICT, STATE, COUNTRY }

    private const val UA = "MetaStamp/1.0 (Android; https://github.com/Tajmalookcs/metadatastemp_app)"
    private val plusRe = Regex("[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]*", RegexOption.IGNORE_CASE)
    private val latin = Regex("^[\\p{IsLatin}0-9 .,'()\\-/&#]+$")

    private class Slots {
        val map = LinkedHashMap<Level, MutableList<String>>().apply {
            Level.values().forEach { put(it, mutableListOf()) }
        }

        fun add(level: Level, raw: String?) {
            val v = clean(raw) ?: return
            map[level]!!.add(v)
        }

        fun first(level: Level) = map[level]!!.firstOrNull()
    }

    fun find(ctx: Context, lat: Double, lng: Double): String? {
        val slots = Slots()
        phone(ctx, lat, lng, slots)
        nominatim(lat, lng, slots)
        photon(lat, lng, slots)

        val picked = mutableListOf<String>()
        fun put(level: Level) {
            val v = slots.map[level]!!.firstOrNull { cand -> picked.none { same(it, cand) } } ?: return
            picked += v
        }
        put(Level.STREET)
        put(Level.AREA)
        put(Level.TOWN)
        put(Level.TEHSIL)
        // District only when there is no town/tehsil to name the place.
        if (slots.first(Level.TOWN) == null || slots.first(Level.TEHSIL) == null) put(Level.DISTRICT)
        put(Level.STATE)
        put(Level.COUNTRY)

        if (picked.isEmpty()) return null
        return (listOf(PlusCode.short(lat, lng)) + picked).joinToString(", ")
    }

    // ---- sources ----

    @Suppress("DEPRECATION")
    private fun phone(ctx: Context, lat: Double, lng: Double, s: Slots) {
        if (!Geocoder.isPresent()) return
        val list: List<Address> = try {
            Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lng, 10) ?: return
        } catch (e: Exception) {
            return
        }
        for (a in list) {
            s.add(Level.STREET, a.thoroughfare)
            val feature = a.featureName
            if (feature != null && feature != a.thoroughfare && feature != a.locality &&
                feature != a.subLocality && feature != a.adminArea && feature != a.countryName &&
                !feature.any { it.isDigit() } ) s.add(Level.STREET, feature)
            s.add(Level.AREA, a.subLocality)
            s.add(Level.TOWN, a.locality)
            a.subAdminArea?.let {
                if (it.contains("district", true) || it.contains("division", true)) s.add(Level.DISTRICT, it)
                else s.add(Level.TEHSIL, it)
            }
            s.add(Level.STATE, a.adminArea)
            s.add(Level.COUNTRY, a.countryName)
        }
        // Low-priority: pieces of the formatted line, e.g. "RQMW+VWP jamia masjid bazar, Mamunkanjan, Pakistan"
        for (a in list) {
            val line = a.getAddressLine(0) ?: continue
            val parts = line.split(",").mapNotNull { clean(it) }
                .filterNot { p -> listOf(a.adminArea, a.countryName).any { it != null && same(it, p) } }
            parts.getOrNull(0)?.let { s.add(Level.STREET, it) }
            parts.getOrNull(1)?.let { s.add(Level.TOWN, it) }
        }
    }

    private fun nominatim(lat: Double, lng: Double, s: Slots) {
        val json = get(String.format(Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.7f&lon=%.7f&zoom=18&addressdetails=1&accept-language=en",
            lat, lng), throttle = true) ?: return
        val a = try { JSONObject(json).optJSONObject("address") } catch (e: Exception) { null } ?: return
        fun f(vararg k: String) = k.forEach { s.add(levelOf(it), a.optString(it)) }
        f("road", "pedestrian", "street", "footway")
        f("neighbourhood", "quarter", "suburb", "hamlet")
        f("village", "town", "city", "municipality")
        f("county", "city_district")
        f("state_district")
        f("state", "province")
        f("country")
    }

    private fun levelOf(key: String) = when (key) {
        "road", "pedestrian", "street", "footway" -> Level.STREET
        "neighbourhood", "quarter", "suburb", "hamlet" -> Level.AREA
        "village", "town", "city", "municipality" -> Level.TOWN
        "county", "city_district" -> Level.TEHSIL
        "state_district" -> Level.DISTRICT
        "state", "province" -> Level.STATE
        else -> Level.COUNTRY
    }

    private fun photon(lat: Double, lng: Double, s: Slots) {
        val json = get(String.format(Locale.US,
            "https://photon.komoot.io/reverse?lat=%.7f&lon=%.7f&lang=en&limit=5", lat, lng)) ?: return
        val feats = try { JSONObject(json).optJSONArray("features") } catch (e: Exception) { null } ?: return
        for (i in 0 until feats.length()) {
            val p = feats.optJSONObject(i)?.optJSONObject("properties") ?: continue
            fun str(k: String) = p.optString(k).takeIf { it.isNotBlank() }
            s.add(Level.STREET, str("street"))
            if (str("type") == "street") s.add(Level.STREET, str("name"))
            s.add(Level.AREA, str("district"))
            s.add(Level.AREA, str("locality"))
            s.add(Level.TOWN, str("city"))
            s.add(Level.TEHSIL, str("county"))
            s.add(Level.STATE, str("state"))
            s.add(Level.COUNTRY, str("country"))
        }
    }

    // ---- helpers ----

    private val lock = Any()
    private var lastNominatim = 0L

    private fun get(url: String, throttle: Boolean = false): String? {
        if (throttle) synchronized(lock) {
            // Nominatim allows about one request per second.
            val wait = 1100 - (System.currentTimeMillis() - lastNominatim)
            if (wait > 0) Thread.sleep(wait)
            lastNominatim = System.currentTimeMillis()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "en")
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Trims, drops Plus Codes, postal codes, non-English script and "Unnamed Road". */
    private fun clean(raw: String?): String? {
        var v = raw?.trim() ?: return null
        v = plusRe.replace(v, "").trim()
        v = v.replace(Regex("\\b\\d{5,6}\\b"), "").trim().trim(',', ' ', '-')
        if (v.isEmpty() || v.all { it.isDigit() || it == ' ' }) return null
        if (!latin.matches(v)) return null
        if (v.equals("Unnamed Road", true)) return null
        return titleCase(v)
    }

    private fun titleCase(v: String): String =
        if (v != v.lowercase(Locale.ROOT)) v
        else v.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    private fun core(v: String) = v.lowercase(Locale.ROOT)
        .replace(Regex("\\b(district|tehsil|division|city)\\b"), "")
        .replace(Regex("[^a-z0-9]"), "")

    private fun same(a: String, b: String): Boolean {
        val ca = core(a); val cb = core(b)
        if (ca.isEmpty() || cb.isEmpty()) return a.equals(b, true)
        return ca == cb
    }
}
