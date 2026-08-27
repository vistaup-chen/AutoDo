package com.autotask.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.provider.Settings
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autotask.MainActivity
import com.autotask.R

/**
 * 悬浮窗服务 - 提供引导式教学和调试交互
 */
interface TeachCallback {
    fun onStepConfirmed(hint: String)
    fun onStepSkipped()
    fun onTeachFinished()
}

interface DebugCallback {
    fun onRetry()
    fun onManualSpecify()
    fun onModifyHint(newHint: String)
    fun onContinue()
}

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_window_channel"

        const val MODE_HIDDEN = 0
        const val MODE_TEACH = 1
        const val MODE_EXECUTE = 2
        const val MODE_DEBUG = 3

        var teachCallback: TeachCallback? = null
        var debugCallback: DebugCallback? = null
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var currentMode = MODE_HIDDEN
    private var params: WindowManager.LayoutParams? = null

    private var currentStepIndex = 0
    private var totalSteps = 0
    private var currentHint = ""

    // 记录最后一次执行窗口状态，用于截图后恢复显示
    private var lastExecuteProgress = 0
    private var lastExecuteTotal = 0
    private var lastExecuteMessage = ""

    // 消息历史（最近 8 条），悬浮窗多行显示，方便不接电脑排查
    private val messageHistory = ArrayDeque<String>()
    private val MAX_HISTORY = 8

    private fun pushMessage(message: String) {
        messageHistory.addLast(message)
        while (messageHistory.size > MAX_HISTORY) messageHistory.removeFirst()
    }

    private fun renderMessage(view: View?) {
        view?.findViewById<TextView>(R.id.tv_message)?.text = messageHistory.joinToString("\n")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // Android 14 (targetSdk 34) 起 startForeground 必须带类型，否则抛 MissingForegroundServiceTypeException 直接崩溃
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getStringExtra("action")) {
                "show_teach" -> showTeachWindow(
                    stepIndex = it.getIntExtra("step_index", 0),
                    totalSteps = it.getIntExtra("total_steps", 0)
                )
                "show_execute" -> {
                    Log.d(TAG, "show_execute: progress=${it.getIntExtra("progress", 0)}, total=${it.getIntExtra("total", 0)}")
                    showExecuteWindow(
                        progress = it.getIntExtra("progress", 0),
                        total = it.getIntExtra("total", 0),
                        message = it.getStringExtra("message") ?: ""
                    )
                }
                "show_debug" -> showDebugWindow(
                    hint = it.getStringExtra("hint") ?: "",
                    reason = it.getStringExtra("reason") ?: ""
                )
                "hide" -> hideFloatingWindow()
                "hide_for_screenshot" -> {
                    // 视觉定位截图前临时隐藏悬浮窗，避免遮挡屏幕干扰模型识别
                    hideFloatingWindow()
                }
                "restore_execute" -> {
                    // 截图完成后恢复执行进度悬浮窗（不重复 push 历史）
                    if (lastExecuteTotal > 0 && currentMode == MODE_HIDDEN) {
                        showExecuteWindow(lastExecuteProgress, lastExecuteTotal, lastExecuteMessage, push = false)
                    }
                }
                "update_message" -> updateMessage(it.getStringExtra("message") ?: "")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideFloatingWindow()
        super.onDestroy()
    }

    // ==================== 教学模式悬浮窗 ====================

    private fun showTeachWindow(stepIndex: Int, totalSteps: Int) {
        currentMode = MODE_TEACH
        currentStepIndex = stepIndex
        // 如果没有传入 totalSteps，使用当前已记录的值
        if (totalSteps > 0) {
            this.totalSteps = totalSteps
        }
        createFloatingWindow(R.layout.floating_teach)

        floatingView?.let { view ->
            val tvStep = view.findViewById<TextView>(R.id.tv_step)
            val tvHint = view.findViewById<TextView>(R.id.tv_hint)
            val etInput = view.findViewById<EditText>(R.id.et_input)
            val btnConfirm = view.findViewById<Button>(R.id.btn_confirm)
            val btnSkip = view.findViewById<Button>(R.id.btn_skip)
            val btnFinish = view.findViewById<Button>(R.id.btn_finish)

            // 显示当前步骤，如果是第一步显示"开始教学"
            val stepText = if (this.totalSteps > 0) {
                "步骤 ${stepIndex + 1}/${this.totalSteps}"
            } else {
                "步骤 ${stepIndex + 1}"
            }
            tvStep.text = stepText
            tvHint.text = "当前页面要做什么？（描述要点击的元素）"
            etInput.hint = "例如：点击\"我的\"按钮"

            btnConfirm.setOnClickListener {
                val hint = etInput.text.toString().trim()
                if (hint.isNotEmpty()) {
                    hideFloatingWindow()
                    teachCallback?.onStepConfirmed(hint)
                } else {
                    Toast.makeText(this, "请输入操作描述", Toast.LENGTH_SHORT).show()
                }
            }

            btnSkip.setOnClickListener {
                hideFloatingWindow()
                teachCallback?.onStepSkipped()
            }

            btnFinish.setOnClickListener {
                hideFloatingWindow()
                teachCallback?.onTeachFinished()
            }
        }
    }

    // ==================== 执行进度悬浮窗 ====================

    private fun showExecuteWindow(progress: Int, total: Int, message: String, push: Boolean = true) {
        Log.d(TAG, "show_execute: progress=$progress, total=$total, message=$message")
        currentMode = MODE_EXECUTE
        // 记录状态，供截图后恢复
        lastExecuteProgress = progress
        lastExecuteTotal = total
        lastExecuteMessage = message
        if (push) {
            pushMessage(message)
        }
        createFloatingWindow(R.layout.floating_execute)

        floatingView?.let { view ->
            val tvProgress = view.findViewById<TextView>(R.id.tv_progress)
            val tvMessage = view.findViewById<TextView>(R.id.tv_message)
            val btnStop = view.findViewById<Button>(R.id.btn_stop)

            tvProgress.text = "$progress / $total"
            renderMessage(view)

            btnStop.setOnClickListener {
                // 通知停止执行
                TaskExecutor.instance?.let { it.stop() }
                hideFloatingWindow()
            }
        }
    }

    // ==================== 调试模式悬浮窗 ====================

    private fun showDebugWindow(hint: String, reason: String) {
        currentMode = MODE_DEBUG
        currentHint = hint
        createFloatingWindow(R.layout.floating_debug)

        floatingView?.let { view ->
            val tvReason = view.findViewById<TextView>(R.id.tv_reason)
            val etHint = view.findViewById<EditText>(R.id.et_hint)
            val btnRetry = view.findViewById<Button>(R.id.btn_retry)
            val btnManual = view.findViewById<Button>(R.id.btn_manual)
            val btnContinue = view.findViewById<Button>(R.id.btn_continue)

            tvReason.text = "找不到元素: $hint\n原因: $reason"
            etHint.setText(hint)

            btnRetry.setOnClickListener {
                val newHint = etHint.text.toString().trim()
                if (newHint.isNotEmpty() && newHint != hint) {
                    // 如果修改了描述，用修改后的重试
                    hideFloatingWindow()
                    debugCallback?.onModifyHint(newHint)
                } else {
                    hideFloatingWindow()
                    debugCallback?.onRetry()
                }
            }

            btnManual.setOnClickListener {
                hideFloatingWindow()
                debugCallback?.onManualSpecify()
            }

            btnContinue.setOnClickListener {
                hideFloatingWindow()
                debugCallback?.onContinue()
            }
        }
    }

    // ==================== 悬浮窗工具方法 ====================

    private fun createFloatingWindow(layoutResId: Int) {
        Log.d(TAG, "createFloatingWindow: layoutResId=$layoutResId")
        hideFloatingWindow()

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "没有悬浮窗权限，无法显示悬浮窗")
            return
        }

        // 用 Material3 主题包装 Service Context 再 inflate：
        // Service 的 Context 主题不是 Material 系（Activity 才有 ThemeManager.setTheme），
        // 直接 inflate 含 MaterialCardView 等组件的布局会抛 "app theme to be Theme.MaterialComponents"
        val themedContext = ContextThemeWrapper(this, R.style.Theme_AutoTask)
        val inflater = LayoutInflater.from(themedContext)
        floatingView = inflater.inflate(layoutResId, null)
        Log.d(TAG, "floatingView=${floatingView != null}")

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        // 支持拖动
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        try {
            Log.d(TAG, "准备添加悬浮窗: floatingView=$floatingView, params=$params")
            windowManager.addView(floatingView, params)
            Log.d(TAG, "悬浮窗添加成功")
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮窗失败: ${e.message}", e)
        }
    }

    private fun hideFloatingWindow() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮窗失败: ${e.message}")
            }
        }
        floatingView = null
        currentMode = MODE_HIDDEN
    }

    private fun updateMessage(message: String) {
        if (currentMode == MODE_EXECUTE) {
            pushMessage(message)
            renderMessage(floatingView)
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoTask 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 AutoTask 服务运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoTask")
            .setContentText("服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
