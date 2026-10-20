package com.autotask.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/**
 * 截屏服务 - MediaProjection 实现，用于 API < 30（无障碍 takeScreenshot 不可用）
 * 授权流程：Activity 调 requestPermission -> onActivityResult 调 handlePermissionResult -> 启动本服务
 */
class ScreenshotService : Service() {

    companion object {
        private const val TAG = "AT-Screenshot"
        private const val CHANNEL_ID = "autotask_screenshot"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_START_CAPTURE = "start_capture"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CAPTURE_TIMEOUT_MS = 3000L

        // 与 MainActivity.REQUEST_SCREENSHOT 保持一致（不能和悬浮窗权限的 1001 撞码）
        const val REQUEST_CODE_SCREENSHOT = 1003

        @Volatile
        var instance: ScreenshotService? = null
            private set

        @Volatile
        private var pendingResultCode: Int = 0

        @Volatile
        private var pendingResultData: Intent? = null

        fun requestPermission(activity: Activity) {
            val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREENSHOT)
        }

        /**
         * 处理授权结果：保存 token 并启动服务建立 VirtualDisplay。
         * MediaProjection token 只在当前进程有效，App 重启后需要重新授权。
         */
        fun handlePermissionResult(context: Context, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != REQUEST_CODE_SCREENSHOT) return false
            if (resultCode != Activity.RESULT_OK || data == null) return false

            pendingResultCode = resultCode
            pendingResultData = data
            val intent = Intent(context, ScreenshotService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动截屏服务失败: ${e.message}")
                return false
            }
            return true
        }

        fun hasPermission(): Boolean = pendingResultData != null

        suspend fun captureScreenshot(): Bitmap? = instance?.capture()
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val latestLock = Any()
    private var latestBitmap: Bitmap? = null
    private var latestTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        initScreenMetrics()
        captureThread = HandlerThread("AT-Screenshot").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (code != 0 && data != null) {
            pendingResultCode = code
            pendingResultData = data
            initMediaProjection()
        } else {
            Log.w(TAG, "服务启动但未携带授权数据，截屏不可用")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        release()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        instance = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AutoTask 截屏", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "视觉识别时截取屏幕"
                    setShowBadge(false)
                }
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AutoTask 截屏服务")
            .setContentText("视觉识别运行中")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台通知失败: ${e.message}")
        }
    }

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
        if (mediaProjection != null) return
        val data = pendingResultData
        if (data == null) {
            Log.e(TAG, "没有截屏授权数据")
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(pendingResultCode, data)
            if (projection == null) {
                Log.e(TAG, "getMediaProjection 返回 null")
                return
            }
            mediaProjection = projection

            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            // 常驻监听：VirtualDisplay 主动推帧，缓存最新一帧供随时取用（屏幕静止时不会有新帧）
            reader.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val bmp = imageToBitmap(image)
                    if (bmp != null) {
                        synchronized(latestLock) {
                            latestBitmap?.recycle()
                            latestBitmap = bmp
                            latestTimestamp = SystemClock.elapsedRealtime()
                        }
                    }
                } finally {
                    image.close()
                }
            }, captureHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "AutoTaskScreenshot",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "截屏授权被系统回收，需要重新授权")
                    pendingResultData = null
                    release()
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, captureHandler)
            Log.i(TAG, "MediaProjection 初始化完成 ${screenWidth}x$screenHeight")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 MediaProjection 失败: ${e.message}")
            release()
        }
    }

    private suspend fun capture(): Bitmap? {
        if (mediaProjection == null) initMediaProjection()
        if (imageReader == null || mediaProjection == null) {
            Log.e(TAG, "截屏未初始化（未授权或服务未启动）")
            return null
        }

        val start = SystemClock.elapsedRealtime()
        val baseline = synchronized(latestLock) { latestTimestamp }
        while (SystemClock.elapsedRealtime() - start < CAPTURE_TIMEOUT_MS) {
            val fresh = synchronized(latestLock) {
                val cached = latestBitmap
                if (cached != null && !cached.isRecycled && latestTimestamp > baseline) {
                    Bitmap.createBitmap(cached)
                } else {
                    null
                }
            }
            if (fresh != null) return fresh

            // 屏幕静止时可能长期没有新帧，缓存帧仍是有效画面
            if (SystemClock.elapsedRealtime() - start >= 500) {
                synchronized(latestLock) {
                    val cached = latestBitmap
                    if (cached != null && !cached.isRecycled) return Bitmap.createBitmap(cached)
                }
            }
            delay(50)
        }
        Log.w(TAG, "等待新帧超时")
        return synchronized(latestLock) {
            latestBitmap?.takeIf { !it.isRecycled }?.let { Bitmap.createBitmap(it) }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val padded = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
                padded.recycle()
                cropped
            } else {
                padded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image 转 Bitmap 失败: ${e.message}")
            null
        }
    }

    private fun release() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        synchronized(latestLock) {
            latestBitmap?.recycle()
            latestBitmap = null
            latestTimestamp = 0
        }
    }
}
