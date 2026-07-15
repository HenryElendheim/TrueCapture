package com.truecapture.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Range
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.truecapture.app.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalCamera2Interop::class, ExperimentalPersistentRecording::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var usingBack = true
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var videoMode = false
    private var torchOn = false

    // Settings, read from the settings screen.
    private var frameRate = 30
    private var frontFlashColor = Color.WHITE
    private var shutterAnimation = true
    private var hapticCapture = true
    private var volumeCapture = false
    private var gridLines = false
    private var largeControls = false

    // Colour filters. The list is the built-in looks plus any custom ones.
    private var filters: List<Filter> = Filters.standard
    private var currentFilterIndex = 0
    private var customParams: MutableList<CustomParams> = mutableListOf()
    private var editingIndex: Int? = null

    // The physical lens (ultra-wide, main, tele) currently selected. null means
    // the camera's default (main) lens. Lets the zoom bar switch real lenses.
    private var lenses: List<Lens> = emptyList()
    private var selectedPhysicalId: String? = null
    private var lensMode = false

    // Where photos and videos are saved. DCIM/Camera is the phone's normal
    // camera folder, so they show up directly in the gallery.
    private val cameraFolder = "DCIM/Camera"

    // Digital zoom steps used when a camera only exposes one lens.
    private val candidateZoomLevels = listOf(0.6f, 1f, 2f, 3f, 6f)

    // A selectable lens. physicalId is the Camera2 physical camera id to bind,
    // or null for the main (logical) camera. relativeZoom is how much it zooms
    // compared with the main lens.
    private data class Lens(
        val physicalId: String?,
        val relativeZoom: Float
    )

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.shutterButton.setOnClickListener { onShutter() }
        binding.flipButton.setOnClickListener { flipCamera() }
        binding.flashButton.setOnClickListener { onFlashButton() }
        binding.modePhoto.setOnClickListener { setVideoMode(false) }
        binding.modeVideo.setOnClickListener { setVideoMode(true) }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.filterButton.setOnClickListener { toggleFilterPanel() }
        binding.saveFilter.setOnClickListener { saveFilterEditor() }
        binding.cancelFilter.setOnClickListener { cancelFilterEditor() }
        binding.deleteFilter.setOnClickListener { deleteFilterEditor() }
        setUpEditorSliders()

        // Place the preview: a 4:3 box in the upper-middle for photos, full
        // screen for video. Done in code so it can change with the mode.
        applyPreviewLayout()

        loadSettings()
        applyStaticSettings()
        setUpTouchControls()
        updateButtons()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val previousRate = frameRate
        loadSettings()
        applyStaticSettings()
        // If the frame rate changed in settings, rebind so it takes effect.
        if (frameRate != previousRate && recording == null && camera != null) {
            startCamera()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeCapture &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        frameRate = (prefs.getString("frame_rate", "30") ?: "30").toIntOrNull() ?: 30
        frontFlashColor = runCatching {
            Color.parseColor(prefs.getString("front_flash_color", "#FFFFFF"))
        }.getOrDefault(Color.WHITE)
        shutterAnimation = prefs.getBoolean("shutter_animation", true)
        hapticCapture = prefs.getBoolean("haptic_capture", true)
        volumeCapture = prefs.getBoolean("volume_capture", false)
        gridLines = prefs.getBoolean("grid_lines", false)
        largeControls = prefs.getBoolean("large_controls", false)
        reloadFilters()
    }

    private fun reloadFilters() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        customParams = Filters.loadCustom(prefs.getString("custom_filters", null)).toMutableList()
        rebuildFilterList()
    }

    private fun applyStaticSettings() {
        binding.gridOverlay.visibility = if (gridLines) View.VISIBLE else View.GONE
        binding.vignetteOverlay.setVignetteColor(frontFlashColor)
        applyControlSizes()
        updateVignette()
        applyPreviewFilter()
    }

    private fun applyControlSizes() {
        val shutter = if (largeControls) dp(96) else dp(76)
        binding.shutterButton.layoutParams = binding.shutterButton.layoutParams.apply {
            width = shutter
            height = shutter
        }
        val side = if (largeControls) dp(64) else dp(52)
        for (button in listOf(binding.flipButton, binding.flashButton)) {
            button.layoutParams = button.layoutParams.apply {
                width = side
                height = side
            }
        }
        binding.shutterButton.requestLayout()
    }

    // Where the live preview sits. In photo mode it is a 4:3 box pushed down
    // from the top so there is black above it and it feels centred, matching
    // the shape of the saved photo. In video mode it fills the whole screen,
    // which feels more natural for filming.
    private fun applyPreviewLayout() {
        val lp = binding.previewView.layoutParams as ConstraintLayout.LayoutParams
        if (videoMode) {
            lp.width = 0
            lp.height = 0
            lp.verticalBias = 0.5f
            binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        } else {
            // 4:3 box as tall as the screen is wide (times 4/3), nudged down
            // from the top so the top strip stays black.
            lp.width = 0
            lp.height = (resources.displayMetrics.widthPixels * 4f / 3f).toInt()
            lp.verticalBias = 0.22f
            binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        binding.previewView.layoutParams = lp
    }

    // The selfie camera has no real flash, so glow the screen corners instead.
    private fun updateVignette() {
        val frontFlashOn = !usingBack &&
            (if (videoMode) torchOn else flashMode != ImageCapture.FLASH_MODE_OFF)
        binding.vignetteOverlay.visibility = if (frontFlashOn) View.VISIBLE else View.GONE
    }

    private fun playShutterEffect() {
        if (!shutterAnimation) return
        val overlay = binding.shutterOverlay
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun vibrateOnCapture() {
        if (!hapticCapture) return
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // --- Colour filters ----------------------------------------------------

    private fun currentFilter(): Filter? = filters.getOrNull(currentFilterIndex)

    private fun toggleFilterPanel() {
        val showing = binding.filterScroll.visibility == View.VISIBLE
        binding.filterScroll.visibility = if (showing) View.GONE else View.VISIBLE
        if (!showing) populateFilterPanel()
    }

    private fun populateFilterPanel() {
        binding.filterBar.removeAllViews()
        filters.forEachIndexed { index, filter ->
            val chip = makeFilterChip(filter.name)
            setChipSelected(chip, index == currentFilterIndex)
            chip.setOnClickListener { selectFilter(index) }
            // Long press a custom filter to edit or remove it.
            if (filter.params != null) {
                chip.setOnLongClickListener {
                    editFilter(index)
                    true
                }
            }
            binding.filterBar.addView(chip)
        }
        // A plus chip to build your own filter. It disappears once the picker
        // is full (ten filters) and comes back after a custom one is deleted.
        if (filters.size < Filters.MAX_FILTERS) {
            val add = makeFilterChip("+")
            add.setOnClickListener { openFilterEditor(null) }
            binding.filterBar.addView(add)
        }
    }

    private fun makeFilterChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            contentDescription = label
        }
    }

    private fun selectFilter(index: Int) {
        currentFilterIndex = index
        applyPreviewFilter()
        populateFilterPanel()
    }

    // Show the chosen look on the live preview. Needs Android 12 (S) or newer.
    private fun applyPreviewFilter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val matrix = if (!videoMode) currentFilter()?.matrix else null
        binding.previewView.setRenderEffect(
            if (matrix != null) {
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            } else {
                null
            }
        )
    }

    // --- Custom filter editor (live) ---------------------------------------

    private fun setUpEditorSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (binding.filterEditor.visibility == View.VISIBLE) applyLiveFilter()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.warmthSlider.setOnSeekBarChangeListener(listener)
        binding.tintSlider.setOnSeekBarChangeListener(listener)
        binding.brightnessSlider.setOnSeekBarChangeListener(listener)
        binding.contrastSlider.setOnSeekBarChangeListener(listener)
        binding.saturationSlider.setOnSeekBarChangeListener(listener)
    }

    private fun editFilter(filterIndex: Int) {
        val customIndex = filterIndex - Filters.standard.size
        if (customIndex in customParams.indices) openFilterEditor(customIndex)
    }

    // editIndex is the position within the custom filters, or null for a new one.
    private fun openFilterEditor(editIndex: Int?) {
        // Adding a new filter is blocked once the picker is full.
        if (editIndex == null && filters.size >= Filters.MAX_FILTERS) return
        editingIndex = editIndex
        val params = editIndex?.let { customParams.getOrNull(it) }
        binding.filterName.setText(params?.name ?: "")
        binding.warmthSlider.progress = (((params?.warmth ?: 0f) + 50f).toInt()).coerceIn(0, 100)
        binding.tintSlider.progress = (((params?.tint ?: 0f) + 50f).toInt()).coerceIn(0, 100)
        binding.brightnessSlider.progress =
            (((params?.brightness ?: 0f) + 50f).toInt()).coerceIn(0, 100)
        binding.contrastSlider.progress =
            ((((params?.contrast ?: 1f) - 0.5f) * 100f).toInt()).coerceIn(0, 100)
        binding.saturationSlider.progress =
            (((params?.saturation ?: 1f) * 100f).toInt()).coerceIn(0, 200)
        binding.deleteFilter.visibility = if (editIndex != null) View.VISIBLE else View.GONE
        binding.filterScroll.visibility = View.GONE
        // The scrim sits behind the panel and swallows taps meant for the
        // buttons underneath, so nothing behind the editor can be pressed.
        binding.editorScrim.visibility = View.VISIBLE
        binding.filterEditor.visibility = View.VISIBLE
        applyLiveFilter()
    }

    private fun editorParams(): CustomParams {
        val name = binding.filterName.text.toString().ifBlank { "Custom" }
        return CustomParams(
            name,
            (binding.warmthSlider.progress - 50).toFloat(),
            (binding.tintSlider.progress - 50).toFloat(),
            (binding.brightnessSlider.progress - 50).toFloat(),
            // Contrast runs 0.5 (flat) to 1.5 (punchy), with the middle at 1.
            0.5f + binding.contrastSlider.progress / 100f,
            binding.saturationSlider.progress / 100f
        )
    }

    // Update the preview as the sliders move, so the look is seen in real time.
    private fun applyLiveFilter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val matrix = Filters.matrixFor(editorParams())
        binding.previewView.setRenderEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
        )
    }

    private fun saveFilterEditor() {
        val params = editorParams()
        val editIndex = editingIndex
        if (editIndex != null && editIndex in customParams.indices) {
            customParams[editIndex] = params
        } else {
            customParams.add(params)
        }
        saveCustomFilters()
        rebuildFilterList()
        val customPos = editIndex ?: (customParams.size - 1)
        currentFilterIndex = (Filters.standard.size + customPos).coerceIn(0, filters.size - 1)
        closeFilterEditor()
    }

    private fun deleteFilterEditor() {
        val editIndex = editingIndex
        if (editIndex != null && editIndex in customParams.indices) {
            customParams.removeAt(editIndex)
            saveCustomFilters()
            rebuildFilterList()
            if (currentFilterIndex >= filters.size) currentFilterIndex = 0
        }
        closeFilterEditor()
    }

    private fun cancelFilterEditor() {
        closeFilterEditor()
    }

    private fun closeFilterEditor() {
        editingIndex = null
        binding.filterEditor.visibility = View.GONE
        binding.editorScrim.visibility = View.GONE
        binding.filterScroll.visibility = View.VISIBLE
        applyPreviewFilter()
        populateFilterPanel()
    }

    private fun rebuildFilterList() {
        filters = Filters.standard + customParams.map { Filters.toFilter(it) }
        if (currentFilterIndex >= filters.size) currentFilterIndex = 0
    }

    private fun saveCustomFilters() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("custom_filters", Filters.toJson(customParams))
            .apply()
    }

    // Bake the chosen look into the saved photo. Runs off the main thread.
    private fun applyFilterToPhoto(uri: Uri?, matrix: ColorMatrix) {
        uri ?: return
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@Thread
                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                // Keep the photo the right way up.
                val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                bitmap = rotateForExif(bitmap, orientation)
                val output = Bitmap.createBitmap(
                    bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
                )
                Canvas(output).drawBitmap(
                    bitmap, 0f, 0f,
                    Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
                )
                contentResolver.openOutputStream(uri, "wt")?.use {
                    output.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
            } catch (e: Exception) {
                // Ignore -> the pending flag is cleared below either way.
            }
            // Make the photo visible now that it is finished.
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            }
        }.start()
    }

    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        // If a recording is running this is a camera switch. Pause it across
        // the rebind so the persistent recording keeps the same file.
        val switchingWhileRecording = recording != null
        if (switchingWhileRecording) {
            runCatching { recording?.pause() }
        }

        // Physical lens overrides only apply to the back camera.
        val physId = if (usingBack) selectedPhysicalId else null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Photos are 4:3 (the full sensor). Videos are 16:9. Match the
            // preview to the same shape so what you see is what you get.
            val aspect = if (videoMode) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else {
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            }
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .build()

            val previewBuilder = Preview.Builder().setResolutionSelector(resolution)
            if (physId != null) {
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physId)
            }
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val selector = if (usingBack) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                camera = if (videoMode) {
                    // Reuse the recorder while filming so the camera can be
                    // switched without ending the recording.
                    val capture = if (switchingWhileRecording && videoCapture != null) {
                        videoCapture!!
                    } else {
                        val recorder = Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.fromOrderedList(
                                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                                )
                            )
                            .build()
                        val vcBuilder = VideoCapture.Builder(recorder)
                            .setTargetFrameRate(Range(frameRate, frameRate))
                        if (physId != null) {
                            Camera2Interop.Extender(vcBuilder).setPhysicalCameraId(physId)
                        }
                        vcBuilder.build().also { videoCapture = it }
                    }
                    imageCapture = null
                    cameraProvider.bindToLifecycle(this, selector, preview, capture)
                } else {
                    val icBuilder = ImageCapture.Builder()
                        .setFlashMode(flashMode)
                        .setResolutionSelector(resolution)
                    if (physId != null) {
                        Camera2Interop.Extender(icBuilder).setPhysicalCameraId(physId)
                    }
                    imageCapture = icBuilder.build()
                    videoCapture = null
                    cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
                }
                torchOn = false
                if (switchingWhileRecording) {
                    runCatching { recording?.resume() }
                }
                // Work out the physical lenses only when on the main lens of the
                // back camera; keep the list while a specific lens is selected.
                if (usingBack && selectedPhysicalId == null) {
                    val logicalId = runCatching {
                        Camera2CameraInfo.from(camera!!.cameraInfo).cameraId
                    }.getOrNull()
                    lenses = computeBackLenses(logicalId)
                } else if (!usingBack) {
                    lenses = emptyList()
                }
                buildZoomBar()
                updateButtons()
            } catch (e: Exception) {
                if (switchingWhileRecording) {
                    runCatching { recording?.stop() }
                    recording = null
                    updateButtons()
                }
                if (physId != null) {
                    // The chosen lens could not be opened. Fall back to the main
                    // lens so the preview keeps working.
                    selectedPhysicalId = null
                    Toast.makeText(this, R.string.lens_unavailable, Toast.LENGTH_SHORT).show()
                    startCamera()
                } else {
                    Toast.makeText(this, R.string.camera_start_failed, Toast.LENGTH_LONG).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onShutter() {
        if (videoMode) {
            toggleRecording()
        } else {
            takePhoto()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        playShutterEffect()
        vibrateOnCapture()

        // If a filter is picked, bake it in after saving. Hide the photo until
        // then with IS_PENDING so the unfiltered version never flashes up.
        val filterMatrix = currentFilter()?.matrix

        val name = timeStamp()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, cameraFolder)
            if (filterMatrix != null) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (filterMatrix != null) {
                        applyFilterToPhoto(output.savedUri, filterMatrix)
                    }
                    Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, R.string.photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return

        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }

        val name = timeStamp()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, cameraFolder)
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled()
        }
        // Persistent so the recording keeps going (same file) when the camera
        // is switched mid-recording.
        pending = pending.asPersistentRecording()

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    startTimer()
                    updateButtons()
                }
                is VideoRecordEvent.Finalize -> {
                    stopTimer()
                    val message = if (event.hasError()) R.string.video_failed else R.string.video_saved
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    recording = null
                    updateButtons()
                }
            }
        }
        updateButtons()
    }

    private fun flipCamera() {
        usingBack = !usingBack
        selectedPhysicalId = null
        startCamera()
    }

    private fun setVideoMode(video: Boolean) {
        if (recording != null || videoMode == video) return
        videoMode = video
        selectedPhysicalId = null
        applyPreviewLayout()
        startCamera()
    }

    private fun onFlashButton() {
        if (videoMode) {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        updateButtons()
    }

    private fun updateButtons() {
        // Mode tabs: the active one is bold.
        binding.modePhoto.setTypeface(null, if (videoMode) Typeface.NORMAL else Typeface.BOLD)
        binding.modeVideo.setTypeface(null, if (videoMode) Typeface.BOLD else Typeface.NORMAL)

        // Update the selfie-camera corner glow to match the flash state.
        updateVignette()

        // Filters are a photo feature, so hide the button in video mode.
        binding.filterButton.visibility = if (videoMode) View.GONE else View.VISIBLE
        if (videoMode) binding.filterScroll.visibility = View.GONE
        applyPreviewFilter()

        // Shutter button graphic.
        val shutter = when {
            !videoMode -> R.drawable.bg_shutter_photo
            recording != null -> R.drawable.bg_shutter_recording
            else -> R.drawable.bg_shutter_video
        }
        binding.shutterButton.setBackgroundResource(shutter)

        // Flash button shows a lightning symbol. In video mode it toggles the
        // light (torch) instead of the photo flash.
        val (icon, desc) = if (videoMode) {
            if (torchOn) R.drawable.ic_flash_on to R.string.light_on
            else R.drawable.ic_flash_off to R.string.light_off
        } else {
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on to R.string.flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto to R.string.flash_auto
                else -> R.drawable.ic_flash_off to R.string.flash_off
            }
        }
        binding.flashButton.setImageResource(icon)
        binding.flashButton.contentDescription = getString(desc)
    }

    // --- Zoom bar -----------------------------------------------------------

    private fun buildZoomBar() {
        binding.zoomBar.removeAllViews()

        if (lenses.size >= 2) {
            lensMode = true
            for (lens in lenses) {
                val chip = makeZoomChip(zoomLabel(lens.relativeZoom))
                setChipSelected(chip, lens.physicalId == selectedPhysicalId)
                chip.setOnClickListener {
                    if (recording != null) return@setOnClickListener
                    if (lens.physicalId != selectedPhysicalId) {
                        selectedPhysicalId = lens.physicalId
                        startCamera()
                    }
                }
                binding.zoomBar.addView(chip)
            }
            binding.zoomBar.visibility = View.VISIBLE
            return
        }

        // One physical lens: fall back to digital zoom steps.
        lensMode = false
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (zoomState == null) {
            binding.zoomBar.visibility = View.GONE
            return
        }
        val levels = candidateZoomLevels.filter { it in zoomState.minZoomRatio..zoomState.maxZoomRatio }
        if (levels.size < 2) {
            binding.zoomBar.visibility = View.GONE
            return
        }
        for (level in levels) {
            val chip = makeZoomChip(zoomLabel(level))
            chip.tag = level
            chip.setOnClickListener {
                camera?.cameraControl?.setZoomRatio(level)
                highlightDigital(level)
            }
            binding.zoomBar.addView(chip)
        }
        binding.zoomBar.visibility = View.VISIBLE
        highlightDigital(zoomState.zoomRatio)
    }

    private fun highlightDigital(ratio: Float) {
        for (i in 0 until binding.zoomBar.childCount) {
            val chip = binding.zoomBar.getChildAt(i) as TextView
            val level = chip.tag as? Float ?: continue
            setChipSelected(chip, abs(level - ratio) < 0.05f)
        }
    }

    private fun makeZoomChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = dp(40)
            val padV = dp(6)
            setPadding(dp(6), padV, dp(6), padV)
            isClickable = true
        }
    }

    private fun setChipSelected(chip: TextView, selected: Boolean) {
        chip.background = if (selected) {
            ContextCompat.getDrawable(this, R.drawable.bg_zoom_selected)
        } else {
            null
        }
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun zoomLabel(z: Float): String {
        return when {
            abs(z - 1f) < 0.06f -> "1×"
            z < 1f -> String.format(Locale.US, "%.1f", z)
            abs(z - z.roundToInt()) < 0.12f -> z.roundToInt().toString()
            else -> String.format(Locale.US, "%.1f", z)
        }
    }

    // --- Physical lens discovery -------------------------------------------

    // Find the ultra-wide, main, and telephoto lenses that make up the back
    // camera. On most phones these are physical sub-cameras of one logical
    // camera, reachable through Camera2's physical camera ids.
    private fun computeBackLenses(logicalId: String?): List<Lens> {
        if (logicalId == null) return emptyList()
        return try {
            val cm = getSystemService(CameraManager::class.java) ?: return emptyList()
            val physicalIds = cm.getCameraCharacteristics(logicalId).physicalCameraIds.toList()
            if (physicalIds.isEmpty()) return emptyList()

            val entries = physicalIds.mapNotNull { id ->
                equivFocalLength(cm, id)?.let { id to it }
            }
            if (entries.size < 2) return emptyList()

            // Main lens = the shortest focal length that is not an ultra-wide
            // (18mm or longer). Every lens is measured relative to it.
            val mainEquiv = entries.map { it.second }.filter { it >= 18f }.minOrNull()
                ?: entries.minOf { it.second }

            val result = mutableListOf(Lens(null, 1f)) // main = logical camera
            for ((id, equiv) in entries) {
                val ratio = equiv / mainEquiv
                if (abs(ratio - 1f) < 0.12f) continue      // this is the main lens
                if (ratio !in 0.3f..12f) continue
                result.add(Lens(id, ratio))
            }

            result.sortedBy { it.relativeZoom }
                .distinctBy { zoomLabel(it.relativeZoom) }
                .let { if (it.size < 2) emptyList() else it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun equivFocalLength(cm: CameraManager, id: String): Float? {
        return try {
            val c = cm.getCameraCharacteristics(id)
            val focal = c.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull() ?: return null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val diagonal = sqrt(size.width * size.width + size.height * size.height)
            if (diagonal <= 0f) null else focal * (43.2666f / diagonal)
        } catch (e: Exception) {
            null
        }
    }

    // --- Touch: tap to focus, pinch to zoom --------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpTouchControls() {
        val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val control = camera?.cameraControl ?: return true
                val state = camera?.cameraInfo?.zoomState?.value ?: return true
                val target = (state.zoomRatio * detector.scaleFactor)
                    .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                control.setZoomRatio(target)
                if (!lensMode) {
                    highlightDigital(target)
                }
                return true
            }
        }
        val scaleDetector = ScaleGestureDetector(this, scaleListener)

        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                focusAt(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        control.startFocusAndMetering(action)
    }

    private fun startTimer() {
        binding.recordTimer.base = SystemClock.elapsedRealtime()
        binding.recordTimer.start()
        binding.recordIndicator.visibility = View.VISIBLE
    }

    private fun stopTimer() {
        binding.recordTimer.stop()
        binding.recordIndicator.visibility = View.GONE
    }

    private fun timeStamp(): String {
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
