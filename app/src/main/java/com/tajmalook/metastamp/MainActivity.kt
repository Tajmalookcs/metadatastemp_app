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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        btnLocate.setOnClickListener { item()?.let { lookup(it, force = true) } }
        btnSave.setOnClickListener { item()?.let { saveItems(listOf(it)) } }
        btnSaveAll.setOnClickListener { saveItems(items.filter { it.loaded }) }

        handleShare(intent)
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
        val start = items.size
        uris.forEach { items.add(PhotoItem(it)) }
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
        preview.setImageBitmap(Stamp.render(src, it, swMap.isChecked))
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
                rebuildThumbs()
                bind()
                toast(if (err == null) "Saved $ok to Pictures/MetaStamp" else "Saved $ok. Problem: $err")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { if (!binding) f() }
    }
}
