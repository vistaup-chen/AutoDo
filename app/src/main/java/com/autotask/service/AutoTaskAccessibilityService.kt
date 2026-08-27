package com.autotask.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.autotask.MainActivity
import com.autotask.config.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 无障碍服务 - 核心执行层
 * 功能: 节点查找、手势点击、截屏、UI 遍历
 */
class AutoTaskAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTaskA11y"
        private const val CHANNEL_ID = "autotask_accessibility"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: AutoTaskAccessibilityService? = null
            private set

        /**
         * 检查无障碍服务是否在系统设置中已启用
         * 这比检查instance更可靠，因为服务可能被系统回收但权限仍然开启
         */
        fun isAvailable(): Boolean = instance != null

        /**
         * 检查无障碍服务是否在系统设置中已启用
         * 通过 Settings.Secure 读取 enabled_accessibility_services，最可靠
         */
        fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
            // 先检查 instance（服务正在运行，同进程时有效）
            if (instance != null) return true

            return try {
                val expectedComponent = ComponentName(
                    context.packageName,
                    AutoTaskAccessibilityService::class.java.name
                ).flattenToString()

                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val componentName = splitter.next()
                    if (componentName == expectedComponent) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "检查无障碍服务状态失败", e)
                false
            }
        }

        fun click(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
            instance?.dispatchClick(x, y, callback)
        }

        fun findNodeByText(text: String): AccessibilityNodeInfo? {
            return instance?.findNodeByTextInternal(text)
        }

        fun getAllTextNodes(): List<String> {
            return instance?.getAllTextNodesInternal() ?: emptyList()
        }

        fun goBack(callback: ((Boolean) -> Unit)? = null) {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
            callback?.invoke(true)
        }

        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300) {
            instance?.dispatchSwipe(startX, startY, endX, endY, duration, null)
        }

        suspend fun takeScreenshot(): Bitmap? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                instance?.takeScreenshotSuspend()
            } else {
                null
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startForegroundNotification()
        Log.i(TAG, "无障碍服务已连接")
    }

    /**
     * 启动前台通知 - 防止系统杀掉无障碍服务
     */
    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 创建通知渠道（Android 8+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoTask 服务运行中",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持自动化服务在后台运行"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        // 点击通知打开主界面
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AutoTask 运行中")
            .setContentText("自动化服务正在后台运行")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            // Android 14 (targetSdk 34) 起 startForeground 必须带类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "前台通知已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台通知失败: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 主要用于监听窗口变化，暂不需要处理
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "无障碍服务已断开，请求系统重新绑定")
        // 返回 true 告诉系统：如果还有 AccessibilityEvent 需要处理，请重新绑定我
        // 这是保持服务在被系统回收后能自动恢复的关键
        return true
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "停止前台通知失败: ${e.message}")
        }
        super.onDestroy()
    }

    // ==================== 策略A: 无障碍节点查找 ====================

    /**
     * 通过文本查找节点
     */
    private fun findNodeByTextInternal(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursive(root, text)
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // 检查当前节点
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }

        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, text)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * 查找节点并点击（策略A 直接点击）
     */
    fun clickNodeByText(text: String, callback: ((Boolean) -> Unit)? = null) {
        serviceScope.launch {
            val node = findNodeByTextInternal(text)
            if (node != null) {
                val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                callback?.invoke(success)
            } else {
                callback?.invoke(false)
            }
        }
    }

    /**
     * 查找节点并通过手势点击（更可靠，适用于 WebView）
     */
    fun clickNodeByTextViaGesture(text: String, callback: ((Boolean) -> Unit)? = null) {
        serviceScope.launch {
            val node = findNodeByTextInternal(text)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val x = rect.centerX().toFloat()
                val y = rect.centerY().toFloat()
                node.recycle()
                dispatchClick(x, y, callback)
            } else {
                callback?.invoke(false)
            }
        }
    }

    /**
     * 获取页面上所有文本节点
     */
    private fun getAllTextNodesInternal(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectAllTexts(root, texts)
        return texts
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, texts)
            child.recycle()
        }
    }

    // ==================== 策略B: 手势操作 ====================

    /**
     * 手势点击（适用于任何 UI，包括 WebView）
     */
    fun dispatchClick(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "点击完成: ($x, $y)")
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "点击取消: ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 手势滑动
     */
    fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 300,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 长按
     */
    fun dispatchLongClick(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback?.invoke(false)
            return
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback?.invoke(false)
            }
        }, null)
    }

    // ==================== 截屏 ====================

    /**
     * 截图 - Android R+ 使用 takeScreenshot API
     */
    private suspend fun takeScreenshotSuspend(): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bitmap = Bitmap.createBitmap(
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                    Bitmap.Config.ARGB_8888
                )
                // 使用 takeScreenshot 的回调版本
                val result = kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont ->
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val bmp = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                if (bmp != null) {
                                    val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                    cont.resume(copy) {}
                                } else {
                                    cont.resume(null) {}
                                }
                                hardwareBuffer.close()
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "截图失败: $errorCode")
                                cont.resume(null) {}
                            }
                        }
                    )
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "截图异常: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    /**
     * 保存截图到文件
     */
    fun saveScreenshotToFile(bitmap: Bitmap, taskId: String, stepIndex: Int): String? {
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

    // ==================== 应用启动 ====================

    /**
     * 启动应用
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                Log.e(TAG, "未找到应用: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: ${e.message}")
            false
        }
    }

    /**
     * 返回上一页
     */
    fun goBack(callback: ((Boolean) -> Unit)? = null) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        callback?.invoke(true)
    }
}
