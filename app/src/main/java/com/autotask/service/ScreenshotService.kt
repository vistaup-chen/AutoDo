package com.autotask.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * 截屏服务 - 使用 MediaProjection API
 * 支持 Android 5.0+，不依赖无障碍截图权限
 */
class ScreenshotService : android.app.Service() {

    companion object {
        private const val TAG = "AT-Screenshot"
        private const val REQUEST_CODE_SCREENSHOT = 1001

        @Volatile
        var instance: ScreenshotService? = null

        private var pendingCallback: ((Bitmap?) -> Unit)? = null
        private var pendingResultCode: Int = 0
        private var pendingResultData: Intent? = null

        /**
         * 请求截屏权限
         */
        fun requestPermission(activity: Activity) {
            val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            activity.startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREENSHOT)
        }

        /**
         * 处理权限结果
         */
        fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode == REQUEST_CODE_SCREENSHOT) {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    pendingResultCode = resultCode
                    pendingResultData = data
                    return true
                }
            }
            return false
        }

        /**
         * 检查是否已授权
         */
        fun hasPermission(): Boolean = pendingResultData != null

        /**
         * 截屏
         */
        suspend fun captureScreenshot(): Bitmap? {
            return instance?.capture() ?: run {
                Log.w(TAG, "ScreenshotService 未启动")
                null
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        initScreenMetrics()
    }

    override fun onDestroy() {
        release()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = android.os.Binder()

    private fun initScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun initMediaProjection() {
        if (pendingResultData == null) {
            Log.e(TAG, "没有截屏权限")
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(pendingResultCode, pendingResultData!!!!)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoTaskScreenshot",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                release()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private suspend fun capture(): Bitmap? {
        if (mediaProjection == null) {
            initMediaProjection()
        }

        if (imageReader == null) {
            Log.e(TAG, "ImageReader 未初始化")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            val reader = imageReader!!

            reader.setOnImageAvailableListener({ imgReader ->
                var image: Image? = null
                try {
                    image = imgReader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        if (cont.isActive) {
                            cont.resume(bitmap) {}
                        }
                    } else {
                        if (cont.isActive) {
                            cont.resume(null) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "截图异常: ${e.message}")
                    if (cont.isActive) {
                        cont.resume(null) {}
                    }
                } finally {
                    image?.close()
                    imgReader.setOnImageAvailableListener(null, null)
                }
            }, Handler(Looper.getMainLooper()))

            // 触发一帧
            virtualDisplay?.let { display ->
                // VirtualDisplay 会自动推送帧
            }

            // 超时处理
            Handler(Looper.getMainLooper()).postDelayed({
                if (cont.isActive) {
                    cont.resume(null) {}
                }
            }, 3000)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉多余的 padding
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image 转 Bitmap 失败: ${e.message}")
            null
        }
    }

    /**
     * 保存截图到文件
     */
    fun saveScreenshot(bitmap: Bitmap, taskId: String, stepIndex: Int): String? {
        return try {
            val dir = File(filesDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "${taskId}_step${stepIndex}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败: ${e.message}")
            null
        }
    }

    /**
     * Bitmap 转字节数组
     */
    fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    private fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
