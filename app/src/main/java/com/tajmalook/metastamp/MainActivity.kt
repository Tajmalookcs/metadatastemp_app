package com.tajmalook.metastamp

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val items = mutableListOf<PhotoItem>()
    private var current = -1
    private val io = Executors.newFixedThreadPool(3)
    private val saveExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var binding = false
    private var pendingUris: List<Uri>? = null
    private var permForSave = false
    private var cameraFile: java.io.File? = null
    private lateinit var tracker: LocationTracker
    private var waitDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var empty: View
    private lateinit var editor: View
    private lateinit var actions: View
    private lateinit var thumbs: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var status: android.widget.TextView
    private lateinit var address: TextInputEditText
    private lateinit var lat: TextInputEditText
    private lateinit var lng: TextInputEditText
    private lateinit var btnDate: MaterialButton
    private lateinit var btnLocate: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnSaveAll: MaterialButton
    private lateinit var swMap: MaterialSwitch

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val p = pendingUris
        pendingUris = null
        if (permForSave) { permForSave = false; return@registerForActivityResult }
        if (p != null) addUris(p, askPerms = false) else picker.launch(arrayOf("image/*"))
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onCaptured(ok)
    }

    private val locPermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tracker = LocationTracker(this)
        savedInstanceState?.getString("cameraFile")?.let { cameraFile = java.io.File(it) }
        io.execute { CameraHelper.cleanup(this) }
        findViewById<View>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<View>(R.id.btnCameraBig).setOnClickListener { openCamera() }

        empty = findViewById(R.id.empty)
        editor = findViewById(R.id.editor)
        actions = findViewById(R.id.actions)
        thumbs = findViewById(R.id.thumbs)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        address = findViewById(R.id.address)
        lat = findViewById(R.id.lat)
        lng = findViewById(R.id.lng)
        btnDate = findViewById(R.id.btnDate)
        btnLocate = findViewById(R.id.btnLocate)
        btnSave = findViewById(R.id.btnSave)
        btnSaveAll = findViewById(R.id.btnSaveAll)
        swMap = findViewById(R.id.swMap)

        findViewById<View>(R.id.btnAdd).setOnClickListener { choose() }
        findViewById<View>(R.id.btnAddBig).setOnClickListener { choose() }

        address.addTextChangedListener(watcher {
            item()?.let { it.address = address.text?.toString(); it.addressEdited = true; schedulePreview() }
        })
        val coordWatcher = watcher {
            item()?.let {
                it.lat = lat.text?.toString()?.toDoubleOrNull()
                it.lng = lng.text?.toString()?.toDoubleOrNull()
                schedulePreview()
            }
        }
        lat.addTextChangedListener(coordWatcher)
        lng.addTextChangedListener(coordWatcher)
        swMap.setOnCheckedChangeListener { _, _ -> schedulePreview() }
        btnDate.setOnClickListener { pickDate() }
        findViewById<View>(R.id.btnCrop).setOnClickListener { openCrop() }
        btnLocate.setOnClickListener { item()?.let { lookup(it, force = true) } }
        btnSave.setOnClickListener { item()?.let { saveItems(listOf(it)) } }
        btnSaveAll.setOnClickListener { saveItems(items.filter { it.loaded }) }

        handleShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraFile?.let { outState.putString("cameraFile", it.absolutePath) }
    }

    override fun onDestroy() {
        tracker.stop()
        super.onDestroy()
    }

    // ---- Camera ----

    private fun hasLocationPerm() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun openCamera() {
        if (!hasLocationPerm()) {
            locPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        launchCamera()
    }

    /** Called after the location permission answer (granted or not). */
    private fun launchCamera() {
        if (!hasLocationPerm()) {
            toast("No location permission. The photo will have no GPS unless your camera adds it.")
            startCapture()
            return
        }
        if (!tracker.isLocationOn()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Location is off")
                .setMessage("Turn on location so the photo gets GPS coordinates and an address.")
                .setPositiveButton("Turn on") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    catch (_: Exception) {}
                }
                .setNegativeButton("Take without GPS") { _, _ -> startCapture() }
                .show()
            return
        }
        tracker.start()
        startCapture()
    }

    private fun startCapture() {
        val file = CameraHelper.newFile(this)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraFile = file
        try {
            takePicture.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            cameraFile = null
            tracker.stop()
            toast("No camera app found on this phone")
        }
    }

    private fun onCaptured(ok: Boolean) {
        val file = cameraFile
        cameraFile = null
        if (!ok || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            tracker.stop()
            return
        }
        val taken = java.util.Date()
        if (!hasLocationPerm()) { finishCapture(file, taken, null); return }
        tracker.start()
        var done = false
        fun finish() {
            if (done) return
            done = true
            waitDialog?.dismiss(); waitDialog = null
            finishCapture(file, taken, tracker.best)
        }
        val b = tracker.best
        if (b != null && b.accuracy <= 50f && System.currentTimeMillis() - b.time < 60_000) { finish(); return }
        waitDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Getting your location…")
            .setMessage("Waiting for GPS. Standing outside helps.")
            .setCancelable(false)
            .setNegativeButton("Skip") { _, _ -> finish() }
            .show()
        tracker.whenReady { finish() }
        main.postDelayed({ finish() }, 15_000)
    }

    private fun finishCapture(file: java.io.File, taken: java.util.Date, loc: android.location.Location?) {
        tracker.stop()
        io.execute {
            CameraHelper.writeCaptureMetadata(file, taken, loc)
            main.post {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                addItems(listOf(PhotoItem(uri).apply { cameraFile = file }))
                if (loc == null) toast("No GPS fix. Type the coordinates or tap Find address later.")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) addUris(uris)
    }

    private fun neededPerms(): Array<String> {
        val list = mutableListOf<String>()
        when {
            Build.VERSION.SDK_INT >= 33 -> list += Manifest.permission.READ_MEDIA_IMAGES
            else -> list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 29) list += Manifest.permission.ACCESS_MEDIA_LOCATION
        if (Build.VERSION.SDK_INT < 29) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun choose() {
        val need = neededPerms()
        if (need.isNotEmpty()) permLauncher.launch(need) else picker.launch(arrayOf("image/*"))
    }

    private fun addUris(uris: List<Uri>, askPerms: Boolean = true) {
        if (askPerms) {
            val need = neededPerms()
            if (need.isNotEmpty()) { pendingUris = uris; permLauncher.launch(need); return }
        }
        addItems(uris.map { PhotoItem(it) })
    }

    private fun addItems(newItems: List<PhotoItem>) {
        val start = items.size
        items.addAll(newItems)
        empty.visibility = View.GONE
        editor.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        rebuildThumbs()
        select(start)
        items.subList(start, items.size).toList().forEach { item ->
            io.execute {
                MetaReader.load(this, item)
                main.post {
                    rebuildThumbs()
                    if (item === item()) bind()
                }
                lookup(item, force = false)
            }
        }
    }

    /** Reverse geocode + map tile for one photo (runs on background). */
    private fun lookup(item: PhotoItem, force: Boolean) {
        val la = item.lat
        val ln = item.lng
        if (la == null || ln == null) {
            if (force) toast("Enter latitude and longitude first")
            return
        }
        if (force) {
            item.addressEdited = false
            status.text = "Looking up address…"
        }
        io.execute {
            val addr = MetaReader.geocode(this, la, ln)
            val tile = MetaReader.mapTile(la, ln)
            main.post {
                if (!item.addressEdited || force) {
                    item.address = addr ?: item.address ?: PlusCode.short(la, ln)
                    item.addressEdited = false
                }
                if (tile != null) item.mapTile = tile
                if (item === item()) bind()
                if (force && addr == null) toast("Couldn't get the address. Check internet, or type it in.")
            }
        }
    }

    private fun item(): PhotoItem? = items.getOrNull(current)

    private fun select(i: Int) {
        current = i
        rebuildThumbs()
        bind()
    }

    private fun rebuildThumbs() {
        thumbs.removeAllViews()
        val size = (64 * resources.displayMetrics.density).toInt()
        val m = (6 * resources.displayMetrics.density).toInt()
        items.forEachIndexed { i, it ->
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = m }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(4, 4, 4, 4)
                setBackgroundResource(if (i == current) R.drawable.thumb_selected else R.drawable.thumb_normal)
                it.thumb?.let { b -> setImageBitmap(b) }
                alpha = if (it.saved) 0.55f else 1f
                contentDescription = it.name
                setOnClickListener { select(i) }
            }
            thumbs.addView(iv)
        }
    }

    private fun bind() {
        val it = item() ?: return
        binding = true
        if (address.text?.toString() != (it.address ?: "")) address.setText(it.address ?: "")
        val latS = it.lat?.let { v -> String.format(Locale.US, "%.6f", v) } ?: ""
        val lngS = it.lng?.let { v -> String.format(Locale.US, "%.6f", v) } ?: ""
        if (lat.text?.toString()?.toDoubleOrNull() != it.lat) lat.setText(latS)
        if (lng.text?.toString()?.toDoubleOrNull() != it.lng) lng.setText(lngS)
        btnDate.text = Stamp.formatDate(it.date)
        binding = false

        status.text = when {
            !it.loaded -> "Reading ${it.name}…"
            else -> buildString {
                append(if (it.gpsFromExif) "Location from photo" else "No GPS in this photo, type the coordinates")
                append(" · ")
                append(if (it.dateFromExif) "date from photo" else "date from file, check it")
                if (it.rotation != 0 || it.straighten != 0f) append(" · rotated")
                if (it.crop != null) append(" · cropped")
            }
        }
        status.setTextColor(ContextCompat.getColor(this,
            if (it.loaded && !it.gpsFromExif) R.color.warn else R.color.muted))
        renderPreview()
    }

    private val previewTask = Runnable { renderPreview() }
    private fun schedulePreview() {
        if (binding) return
        main.removeCallbacks(previewTask)
        main.postDelayed(previewTask, 180)
    }

    private fun renderPreview() {
        val it = item() ?: return
        val src = it.preview
        if (src == null) { preview.setImageDrawable(null); return }
        preview.setImageBitmap(Stamp.render(MetaReader.applyEdits(src, it), it, swMap.isChecked))
    }

    private fun openCrop() {
        val it = item() ?: return
        val src = it.preview ?: return
        val dp = resources.displayMetrics.density
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111413.toInt())
        }
        var rot = it.rotation
        var tilt = it.straighten
        var turned = MetaReader.rotateBy(src, rot)
        val cropView = CropView(this).apply {
            bitmap = MetaReader.straighten(turned, tilt)
            setCrop(it.crop)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(cropView)

        // Rotate controls: 90° left / right and a fine straighten slider
        val tiltLabel = android.widget.TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            minWidth = (92 * dp).toInt()
        }
        fun showTilt() { tiltLabel.text = String.format(Locale.US, "Straighten %+.1f°", tilt) }
        showTilt()
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = -45f; valueTo = 45f; stepSize = 0.5f
            value = (Math.round(tilt * 2) / 2f).coerceIn(-45f, 45f)
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            trackActiveTintList = android.content.res.ColorStateList.valueOf(0xFFF7D046.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF7D046.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tiltTask = Runnable { cropView.bitmap = MetaReader.straighten(turned, tilt) }
        slider.addOnChangeListener { _, v, _ ->
            tilt = v; showTilt()
            main.removeCallbacks(tiltTask); main.postDelayed(tiltTask, 40)
        }
        fun turn(by: Int) {
            rot = ((rot + by) % 360 + 360) % 360
            turned = MetaReader.rotateBy(src, rot)
            val c = android.graphics.RectF(cropView.crop)
            val n = if (by > 0) android.graphics.RectF(1 - c.bottom, c.left, 1 - c.top, c.right)
                    else android.graphics.RectF(c.top, 1 - c.right, c.bottom, 1 - c.left)
            cropView.bitmap = MetaReader.straighten(turned, tilt)
            cropView.aspect = null
            cropView.setCrop(n)
        }
        fun iconBtn(label: String, f: () -> Unit) = MaterialButton(this).apply {
            text = label
            minWidth = 0; minimumWidth = 0
            insetTop = 0; insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A302D.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt())
                .apply { marginEnd = (6 * dp).toInt() }
            setOnClickListener { f() }
        }
        val rotateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        val tiltRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            addView(tiltLabel)
            addView(slider)
        }

        val ratios = listOf<Pair<String, Float?>>(
            "Free" to null,
            "Original" to src.width.toFloat() / src.height,
            "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f,
            "16:9" to 16f / 9f, "9:16" to 9f / 16f
        )
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0)
        }
        val chips = mutableListOf<MaterialButton>()
        fun mark(sel: MaterialButton) = chips.forEach { c ->
            c.setTextColor(if (c === sel) 0xFF111413.toInt() else 0xFFFFFFFF.toInt())
            c.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (c === sel) 0xFFF7D046.toInt() else 0xFF2A302D.toInt())
        }
        ratios.forEach { (label, r) ->
            val b = MaterialButton(this).apply {
                text = label
                minWidth = 0; minimumWidth = 0
                insetTop = 0; insetBottom = 0
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()
                ).apply { marginEnd = (6 * dp).toInt() }
            }
            b.setOnClickListener {
                cropView.aspect = if (label == "Original") cropView.bitmap?.let { bm -> bm.width.toFloat() / bm.height } else r
                mark(b)
            }
            chips += b
            chipRow.addView(b)
        }
        mark(chips[0])
        rotateRow.addView(iconBtn("↺ 90°") { turn(-90); mark(chips[0]) })
        rotateRow.addView(iconBtn("↻ 90°") { turn(90); mark(chips[0]) })
        rotateRow.addView(iconBtn("Level") { slider.value = 0f })
        root.addView(rotateRow)
        root.addView(tiltRow)
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
        }
        fun action(label: String, primary: Boolean, f: () -> Unit) = MaterialButton(this).apply {
            text = label
            if (!primary) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A302D.toInt())
                setTextColor(0xFFFFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = (8 * dp).toInt() }
            setOnClickListener { f() }
        }
        btnRow.addView(action("Reset", false) {
            rot = 0; turned = src; slider.value = 0f; tilt = 0f
            cropView.bitmap = src
            cropView.reset(); mark(chips[0])
        })
        btnRow.addView(action("Cancel", false) { dialog.dismiss() })
        btnRow.addView(action("Apply", true) {
            val c = android.graphics.RectF(cropView.crop)
            it.crop = if (c.left <= 0.001f && c.top <= 0.001f && c.right >= 0.999f && c.bottom >= 0.999f) null else c
            it.rotation = rot
            it.straighten = tilt
            dialog.dismiss()
            bind()
        })
        root.addView(btnRow)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun pickDate() {
        val it = item() ?: return
        val cal = Calendar.getInstance().apply { time = it.date }
        DatePickerDialog(this, { _, y, mo, d ->
            TimePickerDialog(this, { _, h, mi ->
                cal.set(y, mo, d, h, mi)
                it.date = cal.time
                bind()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveItems(list: List<PhotoItem>) {
        if (list.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permForSave = true
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        btnSave.isEnabled = false
        btnSaveAll.isEnabled = false
        val showMap = swMap.isChecked
        saveExec.execute {
            var ok = 0
            var err: String? = null
            list.forEachIndexed { i, item ->
                main.post { status.text = "Saving ${i + 1} of ${list.size}…" }
                try { Saver.save(this, item, showMap); ok++ } catch (e: Throwable) { err = e.message }
            }
            main.post {
                btnSave.isEnabled = true
                btnSaveAll.isEnabled = true
                closeSaved()
                toast(if (err == null) "Saved $ok to Pictures/MetaStamp" else "Saved $ok. Problem: $err")
            }
        }
    }

    /** Removes saved photos; shows the photo chooser again when none are left. */
    private fun closeSaved() {
        val cur = item()
        val firstSaved = items.indexOfFirst { it.saved }
        items.filter { it.saved }.forEach { it.cameraFile?.delete() }
        items.removeAll { it.saved }
        if (items.isEmpty()) {
            current = -1
            preview.setImageDrawable(null)
            thumbs.removeAllViews()
            editor.visibility = View.GONE
            actions.visibility = View.GONE
            empty.visibility = View.VISIBLE
            findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
            return
        }
        val keep = items.indexOf(cur)
        select(if (keep >= 0) keep else firstSaved.coerceIn(0, items.size - 1))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { if (!binding) f() }
    }
}
