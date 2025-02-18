package com.example.myapplication.video

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var onFrameAvailable: ((ByteArray) -> Unit)? = null
    private var imageAnalyzer: ImageAnalysis? = null
    
    suspend fun startCamera(
        previewView: PreviewView,
        onVideoFrame: (ByteArray) -> Unit
    ) = suspendCoroutine { continuation ->
        onFrameAvailable = onVideoFrame
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor!!) { image ->
                            try {
                                // 将YUV格式转换为JPEG
                                val yBuffer = image.planes[0].buffer
                                val uBuffer = image.planes[1].buffer
                                val vBuffer = image.planes[2].buffer
                                
                                val ySize = yBuffer.remaining()
                                val uSize = uBuffer.remaining()
                                val vSize = vBuffer.remaining()
                                
                                val nv21 = ByteArray(ySize + uSize + vSize)
                                
                                yBuffer.get(nv21, 0, ySize)
                                vBuffer.get(nv21, ySize, vSize)
                                uBuffer.get(nv21, ySize + vSize, uSize)
                                
                                val yuvImage = YuvImage(
                                    nv21,
                                    ImageFormat.NV21,
                                    image.width,
                                    image.height,
                                    null
                                )
                                
                                val jpegStream = ByteArrayOutputStream()
                                // 压缩质量设置为80%，平衡图片质量和数据大小
                                yuvImage.compressToJpeg(
                                    Rect(0, 0, image.width, image.height),
                                    80,
                                    jpegStream
                                )
                                
                                onFrameAvailable?.invoke(jpegStream.toByteArray())
                                jpegStream.close()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                image.close()
                            }
                        }
                    }
                
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                
                continuation.resume(Unit)
            } catch (e: Exception) {
                continuation.resume(Unit)
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    fun stopCamera() {
        try {
            imageAnalyzer?.clearAnalyzer()
            imageAnalyzer = null
            onFrameAvailable = null
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
} 