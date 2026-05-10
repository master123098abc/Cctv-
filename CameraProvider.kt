package com.example.cctv.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.cctv.storage.VideoStorageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class CameraProvider(
    private val context: Context,
    private val lifecycle: LifecycleOwner,
    private val analyzer: MotionAnalyzer,
    private val storage: VideoStorageManager
) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Optional PreviewView — set when the Activity binds
    private var previewSurface: PreviewView? = null
    private var previewUseCase: Preview?     = null

    fun setPreviewView(previewView: PreviewView) {
        previewSurface = previewView
        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
    }

    suspend fun bindCamera() {
        val camProvider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context))
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview — surface provider attached if PreviewView already set
        val preview = Preview.Builder().build().also { p ->
            previewUseCase = p
            previewSurface?.let { p.setSurfaceProvider(it.surfaceProvider) }
        }

        // Analysis
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }

        // Video capture
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            camProvider.unbindAll()
            camProvider.bindToLifecycle(lifecycle, selector, preview, imageAnalysis, videoCapture)
        } catch (e: Exception) {
            Log.e("CameraProvider", "Binding failed", e)
        }
    }

    fun startRecording(durationMs: Long, onComplete: () -> Unit) {
        val vc = videoCapture ?: run { onComplete(); return }
        val outputFile = storage.createOutputFile()

        activeRecording = vc.output
            .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) storage.notifyMediaStore(outputFile)
                    else outputFile.delete()
                    onComplete()
                }
            }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            activeRecording?.stop()
        }, durationMs)
    }

    fun shutdown() {
        activeRecording?.stop()
        analysisExecutor.shutdown()
    }
}
