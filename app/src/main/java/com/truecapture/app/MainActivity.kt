package com.truecapture.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var videoMode = false
    private var torchOn = false

    // Where photos and videos are saved. DCIM/Camera is the phone's normal
    // camera folder, so they show up directly in the gallery.
    private val cameraFolder = "DCIM/Camera"

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
        binding.modeButton.setOnClickListener { toggleMode() }

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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = if (videoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    imageCapture = null
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setFlashMode(flashMode)
                        .build()
                    videoCapture = null
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }
                torchOn = false
                updateButtons()
            } catch (e: Exception) {
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
        if (recording != null) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun toggleMode() {
        if (recording != null) return
        videoMode = !videoMode
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
        binding.modeButton.setText(if (videoMode) R.string.mode_video else R.string.mode_photo)

        if (videoMode) {
            binding.shutterButton.setText(if (recording != null) R.string.stop else R.string.record)
            binding.flashButton.setText(if (torchOn) R.string.light_on else R.string.light_off)
        } else {
            binding.shutterButton.setText(R.string.take_photo)
            val flashLabel = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                else -> R.string.flash_off
            }
            binding.flashButton.setText(flashLabel)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpTouchControls() {
        val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val control = camera?.cameraControl ?: return true
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                control.setZoomRatio(currentZoom * detector.scaleFactor)
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
}
