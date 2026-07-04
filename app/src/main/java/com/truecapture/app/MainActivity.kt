package com.truecapture.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import com.truecapture.app.databinding.ActivityMainBinding
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

    // The physical lens (ultra-wide, main, tele) currently selected, or null
    // for the camera's default lens. Lets the zoom bar switch real lenses.
    private var lenses: List<Lens> = emptyList()
    private var selectedLensId: String? = null
    private var lensMode = false

    // Where photos and videos are saved. DCIM/Camera is the phone's normal
    // camera folder, so they show up directly in the gallery.
    private val cameraFolder = "DCIM/Camera"

    // Digital zoom steps used when a camera only has one physical lens.
    private val candidateZoomLevels = listOf(0.6f, 1f, 2f, 3f, 6f)

    // A physical camera and how much it zooms compared with the main lens.
    private data class Lens(
        val id: String,
        val relativeZoom: Float,
        val selector: CameraSelector
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

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val baseSelector = if (usingBack) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val selector = selectedLensId?.let { selectorForCameraId(it) } ?: baseSelector

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
                        VideoCapture.withOutput(recorder).also { videoCapture = it }
                    }
                    imageCapture = null
                    cameraProvider.bindToLifecycle(this, selector, preview, capture)
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setFlashMode(flashMode)
                        .build()
                    videoCapture = null
                    cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
                }
                torchOn = false
                if (switchingWhileRecording) {
                    runCatching { recording?.resume() }
                }
                lenses = computeLenses(cameraProvider, usingBack)
                buildZoomBar()
                updateButtons()
            } catch (e: Exception) {
                // If the switch failed, stop the recording so the video that
                // was captured so far is still saved rather than lost.
                if (switchingWhileRecording) {
                    runCatching { recording?.stop() }
                    recording = null
                    updateButtons()
                }
                Toast.makeText(this, R.string.camera_start_failed, Toast.LENGTH_LONG).show()
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

        val name = timeStamp()
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, cameraFolder)
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
                is VideoRecordEvent.Start -> updateButtons()
                is VideoRecordEvent.Finalize -> {
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
        selectedLensId = null
        startCamera()
    }

    private fun setVideoMode(video: Boolean) {
        if (recording != null || videoMode == video) return
        videoMode = video
        selectedLensId = null
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
            val currentId = selectedLensId ?: mainLensId()
            for (lens in lenses) {
                val chip = makeZoomChip(zoomLabel(lens.relativeZoom))
                setChipSelected(chip, lens.id == currentId)
                chip.setOnClickListener {
                    if (selectedLensId != lens.id) {
                        selectedLensId = lens.id
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

    private fun mainLensId(): String? {
        return lenses.minByOrNull { abs(it.relativeZoom - 1f) }?.id
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

    private fun selectorForCameraId(id: String): CameraSelector {
        return CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }
        }.build()
    }

    private fun computeLenses(provider: ProcessCameraProvider, back: Boolean): List<Lens> {
        return try {
            val facing = if (back) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            val infos = provider.availableCameraInfos.filter {
                runCatching { it.lensFacing == facing }.getOrDefault(false)
            }
            if (infos.size < 2) return emptyList()

            val baseSelector = if (back) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val mainInfo = baseSelector.filter(infos).firstOrNull() ?: return emptyList()
            val mainEquiv = equivFocalLength(mainInfo) ?: return emptyList()

            val lenses = infos.mapNotNull { info ->
                val equiv = equivFocalLength(info) ?: return@mapNotNull null
                val id = Camera2CameraInfo.from(info).cameraId
                Lens(id, equiv / mainEquiv, selectorForCameraId(id))
            }
                .filter { it.relativeZoom in 0.3f..12f }
                .sortedBy { it.relativeZoom }
                .distinctBy { zoomLabel(it.relativeZoom) }

            if (lenses.size < 2) emptyList() else lenses
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun equivFocalLength(info: CameraInfo): Float? {
        return try {
            val c2 = Camera2CameraInfo.from(info)
            val focal = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull() ?: return null
            val size = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            ) ?: return null
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

    private fun timeStamp(): String {
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
