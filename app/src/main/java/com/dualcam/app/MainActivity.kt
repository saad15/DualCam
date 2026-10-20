package com.dualcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var mainView: PreviewView
    private lateinit var pipView: PreviewView
    private lateinit var timer: Chronometer
    private lateinit var status: TextView
    private lateinit var btnMode: Button
    private lateinit var btnShutter: Button

    private var provider: ProcessCameraProvider? = null
    private var photoMode = true
    private var swapped = false
    private var split = false
    private var concurrent = false

    private var backImage: ImageCapture? = null
    private var frontImage: ImageCapture? = null
    private var backVideo: VideoCapture<Recorder>? = null
    private var frontVideo: VideoCapture<Recorder>? = null
    private var recBack: Recording? = null
    private var recFront: Recording? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (r[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mainView = findViewById(R.id.previewMain)
        pipView = findViewById(R.id.previewPip)
        mainView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        pipView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        pipView.setZOrderMediaOverlay(true)
        timer = findViewById(R.id.timer)
        status = findViewById(R.id.status)
        btnMode = findViewById(R.id.btnMode)
        btnShutter = findViewById(R.id.btnShutter)

        btnMode.setOnClickListener {
            if (isRecording()) return@setOnClickListener
            photoMode = !photoMode
            btnMode.text = if (photoMode) "PHOTO" else "VIDEO"
            btnShutter.text = if (photoMode) "CAPTURE" else "REC"
            bind()
        }
        btnShutter.setOnClickListener { if (photoMode) takePhotos() else toggleRecording() }
        findViewById<Button>(R.id.btnSwap).setOnClickListener {
            if (isRecording()) return@setOnClickListener
            swapped = !swapped; bind()
        }
        findViewById<Button>(R.id.btnLayout).setOnClickListener { v ->
            split = !split
            (v as Button).text = if (split) "PIP" else "SPLIT"
            applyLayout()
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera() else permLauncher.launch(needed)
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun applyLayout() {
        val h = resources.displayMetrics.heightPixels
        val dp = resources.displayMetrics.density
        val mlp = mainView.layoutParams as FrameLayout.LayoutParams
        val plp = pipView.layoutParams as FrameLayout.LayoutParams
        if (!concurrent) { pipView.visibility = View.GONE; mlp.height = FrameLayout.LayoutParams.MATCH_PARENT; mainView.layoutParams = mlp; return }
        pipView.visibility = View.VISIBLE
        if (split) {
            mlp.height = h / 2; mlp.gravity = Gravity.BOTTOM
            plp.width = FrameLayout.LayoutParams.MATCH_PARENT; plp.height = h / 2
            plp.gravity = Gravity.TOP; plp.setMargins(0, 0, 0, 0)
        } else {
            mlp.height = FrameLayout.LayoutParams.MATCH_PARENT; mlp.gravity = Gravity.TOP
            plp.width = (150 * dp).toInt(); plp.height = (200 * dp).toInt()
            plp.gravity = Gravity.TOP or Gravity.END
            val m = (16 * dp).toInt(); plp.setMargins(m, m, m, m)
        }
        mainView.layoutParams = mlp; pipView.layoutParams = plp
    }

    private fun videoCase(): VideoCapture<Recorder> {
        val rec = Recorder.Builder().setQualitySelector(
            QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        ).build()
        return VideoCapture.withOutput(rec)
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        backImage = null; frontImage = null; backVideo = null; frontVideo = null

        val pair = p.availableConcurrentCameraInfos.firstOrNull { l ->
            CameraSelector.DEFAULT_BACK_CAMERA.filter(l).isNotEmpty() &&
                CameraSelector.DEFAULT_FRONT_CAMERA.filter(l).isNotEmpty()
        }
        if (pair != null) {
            try {
                val backSel = CameraSelector.DEFAULT_BACK_CAMERA.filter(pair).first().cameraSelector
                val frontSel = CameraSelector.DEFAULT_FRONT_CAMERA.filter(pair).first().cameraSelector
                val backPreviewView = if (swapped) pipView else mainView
                val frontPreviewView = if (swapped) mainView else pipView

                val bg = UseCaseGroup.Builder()
                val fg = UseCaseGroup.Builder()
                bg.addUseCase(Preview.Builder().build().also { it.setSurfaceProvider(backPreviewView.surfaceProvider) })
                fg.addUseCase(Preview.Builder().build().also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) })
                if (photoMode) {
                    backImage = ImageCapture.Builder().build().also { bg.addUseCase(it) }
                    frontImage = ImageCapture.Builder().build().also { fg.addUseCase(it) }
                } else {
                    backVideo = videoCase().also { bg.addUseCase(it) }
                    frontVideo = videoCase().also { fg.addUseCase(it) }
                }
                p.bindToLifecycle(listOf(
                    ConcurrentCamera.SingleCameraConfig(backSel, bg.build(), this),
                    ConcurrentCamera.SingleCameraConfig(frontSel, fg.build(), this)
                ))
                concurrent = true
                applyLayout()
                return
            } catch (e: Exception) {
                p.unbindAll()
                Toast.makeText(this, "Dual camera failed: ${e.message}. Using single camera.", Toast.LENGTH_LONG).show()
            }
        }
        // Fallback: single camera (device doesn't support concurrent front+back)
        concurrent = false
        applyLayout()
        val sel = if (swapped) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val prev = Preview.Builder().build().also { it.setSurfaceProvider(mainView.surfaceProvider) }
        try {
            if (photoMode) {
                backImage = ImageCapture.Builder().build()
                p.bindToLifecycle(this, sel, prev, backImage!!)
            } else {
                backVideo = videoCase()
                p.bindToLifecycle(this, sel, prev, backVideo!!)
            }
            showStatus("This device can't run both cameras at once — single camera mode (SWAP switches)")
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg; status.visibility = View.VISIBLE
        status.postDelayed({ status.visibility = View.GONE }, 4000)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun photo(ic: ImageCapture, tag: String, ts: String) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DualCam_${ts}_$tag")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DualCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) {
                    showStatus("Saved $tag photo → Pictures/DualCam")
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Photo error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun takePhotos() {
        val ts = stamp()
        backImage?.let { photo(it, if (concurrent || !swapped) "back" else "front", ts) }
        frontImage?.let { photo(it, "front", ts) }
    }

    private fun isRecording() = recBack != null || recFront != null

    @SuppressLint("MissingPermission")
    private fun record(vc: VideoCapture<Recorder>, tag: String, ts: String, audio: Boolean): Recording {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "DualCam_${ts}_$tag")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DualCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        return pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                if (ev.hasError()) Toast.makeText(this, "Video error (${ev.error})", Toast.LENGTH_SHORT).show()
                else showStatus("Saved $tag video → Movies/DualCam")
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording()) {
            recBack?.stop(); recFront?.stop(); recBack = null; recFront = null
            timer.stop(); timer.visibility = View.GONE
            btnShutter.text = "REC"; btnMode.isEnabled = true
        } else {
            val ts = stamp()
            // Audio only on one stream: two simultaneous mic captures aren't reliable
            backVideo?.let { recBack = record(it, if (concurrent || !swapped) "back" else "front", ts, true) }
            frontVideo?.let { recFront = record(it, "front", ts, false) }
            timer.base = SystemClock.elapsedRealtime(); timer.visibility = View.VISIBLE; timer.start()
            btnShutter.text = "STOP"; btnMode.isEnabled = false
        }
    }
}
