package com.m832print.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.m832print.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedPages: List<Bitmap> = emptyList()
    private var selectedDevice: BluetoothDevice? = null

    // True for a single plain image (the 297mm page-count estimate and the
    // shrink-to-fit suggestion only apply there, per 2026-10-06 request) --
    // false for PDFs/docx/odt, which already have a real, discrete page count.
    private var isSingleImage = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val prefs by lazy { getSharedPreferences("m832_prefs", MODE_PRIVATE) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadPickedUri(uri)
    }

    private val requestBtConnect = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPairedDevicePicker() else log("Bluetooth permission denied -- can't list paired printers without it.")
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val rect = RectF(
                data.getFloatExtra(CropActivity.EXTRA_LEFT, 0f),
                data.getFloatExtra(CropActivity.EXTRA_TOP, 0f),
                data.getFloatExtra(CropActivity.EXTRA_RIGHT, 1f),
                data.getFloatExtra(CropActivity.EXTRA_BOTTOM, 1f),
            )
            applyCropToPages(rect)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Version + build timestamp, always accurate regardless of whether
        // versionName got bumped -- added 2026-10-05 so it's possible to
        // confirm which exact build is actually installed after a reinstall.
        binding.titleText.text = "${getString(R.string.app_name)}  v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})"

        binding.pickFileButton.setOnClickListener {
            pickFile.launch(arrayOf("image/*", "application/pdf", OfficeDocumentReader.DOCX_MIME, OfficeDocumentReader.ODT_MIME))
        }
        binding.selectPrinterButton.setOnClickListener { onSelectPrinterClicked() }
        binding.printButton.setOnClickListener { onPrintClicked() }
        binding.feedButton.setOnClickListener { onFeedClicked() }
        binding.cropButton.setOnClickListener { launchCropActivity() }
        binding.shrinkButton.setOnClickListener { shrinkToFitOnePage() }

        handleIncomingShareIntent()
        tryAutoSelectLastPrinter()
    }

    private fun handleIncomingShareIntent() {
        val type = intent?.type
        if (intent?.action != android.content.Intent.ACTION_SEND || type == null) return
        val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM) ?: return
        loadPickedUri(uri, mimeOverride = type)
    }

    private fun loadPickedUri(uri: Uri, mimeOverride: String? = null) {
        val mime = mimeOverride ?: contentResolver.getType(uri)
        when {
            mime?.startsWith("image/") == true -> loadImage(uri)
            mime == "application/pdf" -> loadPdf(uri)
            mime != null && OfficeDocumentReader.isSupported(mime) -> loadOfficeDocument(uri, mime)
            else -> log("Unsupported file type: $mime")
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSampleSize(1)
                    // ImageDecoder defaults to Config.HARDWARE (GPU-backed,
                    // no CPU pixel access at all) -- M832Protocol needs
                    // getPixels() for dithering, so force software allocation.
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            isSingleImage = true
            selectedPages = listOf(bmp)
            log("Image loaded: ${bmp.width}x${bmp.height}")
            updatePreviewUi()
            updatePrintButtonEnabled()
        } catch (e: Exception) {
            log("Failed to load image: ${e.message}")
        }
    }

    /**
     * Renders every page of a PDF to a Bitmap via Android's built-in
     * PdfRenderer (API 21+, no extra library needed), at M832Protocol.DPI so
     * page content lands at the same effective resolution the printer
     * actually uses -- each page then goes through the exact same
     * scale/dither/crop pipeline as a plain image, via
     * M832Protocol.buildPrintJob(List<Bitmap>).
     */
    private fun loadPdf(uri: Uri) {
        try {
            val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Could not open file descriptor")
            val pages = mutableListOf<Bitmap>()
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        // page.width/height are in PDF points (1/72in); scale to our DPI.
                        val widthPx = (page.width * M832Protocol.DPI / 72f).roundToInt().coerceAtLeast(1)
                        val heightPx = (page.height * M832Protocol.DPI / 72f).roundToInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                        // PdfRenderer leaves transparent pixels outside drawn content;
                        // without a white backdrop those would dither as black.
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        pages.add(bmp)
                    }
                }
            }
            pfd.close()

            if (pages.isEmpty()) {
                log("PDF has no pages")
                return
            }
            isSingleImage = false
            selectedPages = pages
            log("Document loaded: ${pages.size} page(s), first page ${pages[0].width}x${pages[0].height}")
            updatePreviewUi()
            updatePrintButtonEnabled()
        } catch (e: Exception) {
            log("Failed to load document: ${e.message}")
        }
    }

    /**
     * .docx/.odt path -- text-only extraction and rendering, see
     * OfficeDocumentReader's doc for why formatting/images/tables aren't
     * preserved. Uses the same WIDTH_DOTS/PAGE_LENGTH_DOTS page geometry
     * as the image page-count estimate, so a rendered document page and
     * the roll-fed "page" concept agree with each other.
     */
    private fun loadOfficeDocument(uri: Uri, mime: String) {
        try {
            val paragraphs = OfficeDocumentReader.extractParagraphs(contentResolver, uri, mime)
            if (paragraphs == null) {
                log("Could not read this document (unrecognized internal structure)")
                return
            }
            val pages = OfficeDocumentReader.renderToPages(paragraphs, M832Protocol.WIDTH_DOTS, M832Protocol.PAGE_LENGTH_DOTS)
            isSingleImage = false
            selectedPages = pages
            log("Document loaded: ${pages.size} page(s) (text-only rendering -- formatting/images not preserved)")
            updatePreviewUi()
            updatePrintButtonEnabled()
        } catch (e: Exception) {
            log("Failed to load document: ${e.message}")
        }
    }

    /** Refreshes the preview bitmap, page-count text, and crop/shrink button visibility from current state. */
    private fun updatePreviewUi() {
        val first = selectedPages.firstOrNull()
        binding.imagePreview.setImageBitmap(first)

        if (first == null) {
            binding.pageCountText.visibility = View.GONE
            binding.cropButton.visibility = View.GONE
            binding.shrinkSuggestionRow.visibility = View.GONE
            return
        }

        binding.cropButton.visibility = View.VISIBLE

        if (isSingleImage) {
            val pages = imagePageEstimate(first)
            binding.pageCountText.visibility = View.VISIBLE
            binding.pageCountText.text = "This image will use ~%.1f page(s) (%.0fmm each)".format(pages, M832Protocol.PAGE_LENGTH_MM)
            binding.shrinkSuggestionRow.visibility = if (pages > 1.0f) View.VISIBLE else View.GONE
        } else {
            binding.pageCountText.visibility = View.VISIBLE
            binding.pageCountText.text = "This document is ${selectedPages.size} page(s)"
            binding.shrinkSuggestionRow.visibility = View.GONE
        }
    }

    /** How many PAGE_LENGTH_DOTS-tall pages this bitmap will span once scaled to the printer's full width. */
    private fun imagePageEstimate(bmp: Bitmap): Float {
        val scaledHeight = bmp.height.toLong() * M832Protocol.WIDTH_DOTS / bmp.width
        return scaledHeight.toFloat() / M832Protocol.PAGE_LENGTH_DOTS
    }

    /**
     * Opens the separate full-screen CropActivity on the first page --
     * 2026-10-06: cropping inline in the small 200dp preview was too
     * imprecise and had no room for an always-visible apply/cancel row.
     * The bitmap is handed over via CropActivity.pendingBitmap (see its
     * doc for why), not through the launching Intent.
     */
    private fun launchCropActivity() {
        val first = selectedPages.firstOrNull() ?: return
        CropActivity.pendingBitmap = first
        cropLauncher.launch(Intent(this, CropActivity::class.java))
    }

    /** Applies the normalized crop rect returned by CropActivity to every loaded page uniformly. */
    private fun applyCropToPages(rect: RectF) {
        selectedPages = selectedPages.map { bmp ->
            val x = (rect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (rect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val w = (rect.width() * bmp.width).toInt().coerceIn(1, bmp.width - x)
            val h = (rect.height() * bmp.height).toInt().coerceIn(1, bmp.height - y)
            Bitmap.createBitmap(bmp, x, y, w, h)
        }
        log("Cropped to ${selectedPages.first().width}x${selectedPages.first().height}" + if (selectedPages.size > 1) " (all ${selectedPages.size} pages)" else "")
        updatePreviewUi()
        updatePrintButtonEnabled()
    }

    /**
     * Scales the image down (preserving aspect ratio, so this is a true
     * shrink, not a crop) so it fits in exactly one PAGE_LENGTH_DOTS-tall
     * page once centered on a full WIDTH_DOTS-wide white canvas -- the
     * padding keeps M832Protocol's own scale-to-width step from
     * stretching it back out to the full page length.
     */
    private fun shrinkToFitOnePage() {
        val bmp = selectedPages.firstOrNull() ?: return
        val pages = imagePageEstimate(bmp)
        if (pages <= 1.0f) return

        val shrinkFactor = 1f / pages
        val newContentWidth = (M832Protocol.WIDTH_DOTS * shrinkFactor).toInt().coerceAtLeast(1)
        val newContentHeight = M832Protocol.PAGE_LENGTH_DOTS

        val shrunkContent = Bitmap.createScaledBitmap(bmp, newContentWidth, newContentHeight, true)
        val padded = Bitmap.createBitmap(M832Protocol.WIDTH_DOTS, M832Protocol.PAGE_LENGTH_DOTS, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.WHITE)
            drawBitmap(shrunkContent, (M832Protocol.WIDTH_DOTS - newContentWidth) / 2f, 0f, null)
        }

        selectedPages = listOf(padded)
        log("Shrunk to fit 1 page (content ${newContentWidth}x$newContentHeight, centered on a ${M832Protocol.WIDTH_DOTS}x${M832Protocol.PAGE_LENGTH_DOTS} page)")
        updatePreviewUi()
        updatePrintButtonEnabled()
    }

    private fun onSelectPrinterClicked() {
        if (!hasBluetoothConnectPermission()) {
            requestBtConnect.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        showPairedDevicePicker()
    }

    /**
     * Filters the paired-device list down to ones named "M832" by default
     * (2026-10-06 request), falling back to showing everything if none
     * match, with a "Show all paired devices" entry to escalate on demand.
     */
    @SuppressLint("MissingPermission") // permission already checked by the caller above
    private fun showPairedDevicePicker(showAll: Boolean = false) {
        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null) {
            log("No Bluetooth adapter on this device.")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is off -- turn it on and try again.")
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_LONG).show()
            return
        }

        val allPaired = adapter.bondedDevices.toList()
        if (allPaired.isEmpty()) {
            log("No paired devices found. Pair the M832 in Android's own Bluetooth settings first.")
            return
        }

        val filtered = allPaired.filter { (it.name ?: "").contains("M832", ignoreCase = true) }
        val showingAll = showAll || filtered.isEmpty()
        val list = if (showingAll) allPaired else filtered

        val labels = list.map { "${it.name ?: "(unnamed)"} — ${it.address}" }.toMutableList()
        if (!showingAll) labels.add("Show all paired devices...")

        AlertDialog.Builder(this)
            .setTitle(if (showingAll) "Select printer (all paired devices)" else "Select the M832")
            .setItems(labels.toTypedArray()) { _, which ->
                if (!showingAll && which == list.size) {
                    showPairedDevicePicker(showAll = true)
                } else {
                    selectDevice(list[which])
                }
            }
            .show()
    }

    private fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
        binding.selectedPrinterText.text = "${device.name ?: device.address} (${device.address})"
        log("Printer selected: ${device.name ?: device.address}")
        prefs.edit().putString(KEY_LAST_PRINTER_MAC, device.address).apply()
        updatePrintButtonEnabled()
    }

    /**
     * Restores the last-selected printer on launch (2026-10-06 request),
     * silently -- only if BLUETOOTH_CONNECT is already granted from a
     * previous use, so this never forces a permission prompt just to
     * restore state the user hasn't asked to do anything with yet.
     */
    @SuppressLint("MissingPermission") // hasBluetoothConnectPermission() checked just above
    private fun tryAutoSelectLastPrinter() {
        if (!hasBluetoothConnectPermission()) return
        val mac = prefs.getString(KEY_LAST_PRINTER_MAC, null) ?: return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val device = adapter.bondedDevices.firstOrNull { it.address == mac } ?: return
        selectedDevice = device
        binding.selectedPrinterText.text = "${device.name ?: device.address} (${device.address})"
        log("Auto-selected last used printer: ${device.name ?: device.address}")
        updatePrintButtonEnabled()
    }

    private fun updatePrintButtonEnabled() {
        binding.printButton.isEnabled = selectedPages.isNotEmpty() && selectedDevice != null
        binding.feedButton.isEnabled = selectedDevice != null
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // hasBluetoothConnectPermission() checked just below
    private fun onPrintClicked() {
        val pages = selectedPages.takeIf { it.isNotEmpty() } ?: return
        val device = selectedDevice ?: return

        if (!hasBluetoothConnectPermission()) {
            log("Bluetooth permission was revoked -- select the printer again.")
            return
        }

        binding.printButton.isEnabled = false
        log("Building raster (${pages.size} page(s))...")

        Thread {
            try {
                val data = M832Protocol.buildPrintJob(pages)
                runOnUiThread { log("Raster built: ${data.size} bytes. Connecting to ${device.name ?: device.address}...") }

                // Progress fires once per ~512-byte chunk; only log every 32nd
                // one (roughly every 16KB) plus the final chunk, so a ~1MB job
                // doesn't flood the log with hundreds of lines.
                var callCount = 0
                val result = M832BluetoothClient.sendJob(
                    device,
                    data,
                    onProgress = { sent, total ->
                        callCount++
                        if (sent == total || callCount % 32 == 0) {
                            runOnUiThread { log("  $sent/$total bytes sent") }
                        }
                    },
                    onPhase = { phase -> runOnUiThread { log("  $phase") } },
                )

                runOnUiThread {
                    log("Done: ${result.bytesSent}/${result.totalBytes} bytes sent.")
                    binding.printButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("Print failed: ${e.message}")
                    binding.printButton.isEnabled = true
                }
            }
        }.start()
    }

    /**
     * Manual recovery action: resends just the tail feed sequence
     * (M832Protocol.buildFeedOnlyJob()) on its own, for the case where a
     * print finishes but the paper doesn't advance to the tear line --
     * lets the user retry the feed from here instead of opening the
     * printer. Confirms before sending since it's still a paper-movement
     * command, not just a status query.
     */
    @SuppressLint("MissingPermission") // hasBluetoothConnectPermission() checked just below
    private fun onFeedClicked() {
        val device = selectedDevice ?: return
        if (!hasBluetoothConnectPermission()) {
            log("Bluetooth permission was revoked -- select the printer again.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Feed paper?")
            .setMessage("Sends the feed command to ${device.name ?: device.address} now.")
            .setPositiveButton("Feed") { _, _ ->
                binding.feedButton.isEnabled = false
                log("Sending feed command to ${device.name ?: device.address}...")
                Thread {
                    try {
                        val data = M832Protocol.buildFeedOnlyJob()
                        val result = M832BluetoothClient.sendJob(
                            device,
                            data,
                            onProgress = { _, _ -> },
                            onPhase = { phase -> runOnUiThread { log("  $phase") } },
                        )
                        runOnUiThread {
                            log("Feed done: ${result.bytesSent}/${result.totalBytes} bytes sent.")
                            binding.feedButton.isEnabled = true
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            log("Feed failed: ${e.message}")
                            binding.feedButton.isEnabled = true
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun log(message: String) {
        val line = "[${timeFmt.format(Date())}] $message\n"
        binding.logText.append(line)
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        private const val KEY_LAST_PRINTER_MAC = "last_printer_mac"
    }
}
