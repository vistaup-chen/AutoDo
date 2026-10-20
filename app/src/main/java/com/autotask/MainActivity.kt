package com.autotask

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.autotask.config.AutomationTask
import com.autotask.config.TaskRepository
import com.autotask.config.TaskStep
import com.autotask.databinding.ActivityMainBinding
import com.autotask.service.AutoTaskAccessibilityService
import com.autotask.service.DebugCallback
import com.autotask.service.FloatingWindowService
import com.autotask.service.ScreenshotService
import com.autotask.service.TaskExecutor
import com.autotask.ui.SettingsActivity
import com.autotask.ui.TaskAdapter
import com.autotask.ui.TaskListManager
import com.autotask.ui.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AT-MainActivity"
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_NOTIFICATION = 1002
        private const val REQUEST_SCREENSHOT = 1003
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: TaskRepository
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var taskExecutor: TaskExecutor
    private lateinit var taskListManager: TaskListManager

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = TaskRepository(this)
        taskListManager = TaskListManager(repository)
        taskExecutor = TaskExecutor(this, repository)

        setupUI()
        setupExecutorCallbacks()
        checkPermissions()
        triggerScreenshotConfirmation()
    }

    /**
     * 主动触发系统截图确认（Android 14+ 无障碍截图需要用户确认，否则任务执行时截图返回错误码3）
     * App 打开且无障碍已开启时截一次图，让用户提前点掉确认弹窗
     */
    private fun triggerScreenshotConfirmation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        lifecycleScope.launch {
            if (AutoTaskAccessibilityService.isAvailable()) {
                delay(1500) // 等服务连接稳定
                val bmp = AutoTaskAccessibilityService.takeScreenshot()
                if (bmp != null) {
                    bmp.recycle()
                    Log.d(TAG, "截图权限确认成功")
                } else if (AutoTaskAccessibilityService.lastScreenshotError == 3) {
                    Toast.makeText(
                        this@MainActivity,
                        "系统要求确认截图权限：请留意屏幕弹窗并点击「允许」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTaskList()
        updatePermissionStatus()
        // 从系统权限页返回后继续引导下一项
        checkPermissions()
    }

    override fun onDestroy() {
        FloatingWindowService.teachCallback = null
        FloatingWindowService.debugCallback = null
        super.onDestroy()
    }

    private fun setupUI() {
        // Toolbar
        setSupportActionBar(binding.toolbar)

        // RecyclerView
        taskAdapter = TaskAdapter(
            onToggleEnabled = { task, enabled -> toggleTaskEnabled(task, enabled) },
            onDelete = { task -> deleteTask(task) },
            onExecute = { task -> executeSingleTask(task) },
            onEdit = { task -> showEditTaskDialog(task) },
            onEditActions = { task -> showStepEditorDialog(task) },
            onMove = { from, to -> handleTaskMove(from, to) }
        )
        binding.rvTasks.layoutManager = LinearLayoutManager(this)
        binding.rvTasks.adapter = taskAdapter
        // 长按拖动排序
        taskAdapter.createItemTouchHelper().attachToRecyclerView(binding.rvTasks)

        // 开启无障碍按钮
        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 悬浮窗开关
        binding.switchFloatingWindow.setOnCheckedChangeListener { _, isChecked ->
            taskExecutor.floatingWindowEnabled = isChecked
        }
        taskExecutor.floatingWindowEnabled = binding.switchFloatingWindow.isChecked()

        // 权限状态和检查按钮
        updatePermissionStatus()
        binding.btnCheckPermissions.setOnClickListener {
            showPermissionDialog()
        }

        // 悬浮窗回调
        taskExecutor.floatingWindowCallback = { stepIndex, totalSteps, stepDesc, success, isFirst ->
            runOnUiThread {
                // 检查悬浮窗权限
                if (!Settings.canDrawOverlays(this)) {
                    return@runOnUiThread
                }
                val message = when {
                    isFirst -> "准备开始: $stepDesc"
                    success == null -> stepDesc // TaskExecutor 已带"正在执行："前缀
                    success -> "✓ $stepDesc"
                    else -> "✗ $stepDesc"
                }
                val intent = Intent(this, FloatingWindowService::class.java).apply {
                    putExtra("action", "show_execute")
                    putExtra("progress", stepIndex + 1)
                    putExtra("total", totalSteps)
                    putExtra("message", message)
                }
                // 服务已在 App 前台时预启动并常驻，这里只是更新内容；
                // 包 try-catch 防止后台启动前台服务被系统拒绝时崩溃
                try {
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "启动悬浮窗服务失败: ${e.message}")
                }
            }
        }

        // 预启动悬浮窗服务：必须在 App 前台时启动，否则任务执行切到后台后
        // 系统会拒绝启动前台服务，悬浮窗就永远显示不出来
        if (Settings.canDrawOverlays(this)) {
            try {
                startService(Intent(this, FloatingWindowService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "预启动悬浮窗服务失败: ${e.message}")
            }
        }

        // 添加任务按钮
        binding.btnAddTask.setOnClickListener {
            showAddTaskDialog()
        }

        // 一键执行按钮
        binding.btnStartAll.setOnClickListener {
            startAllTasks()
        }

        // 设置按钮（底部栏）
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupExecutorCallbacks() {
        taskExecutor.progressCallback = { current, total, message ->
            runOnUiThread {
                binding.btnStartAll.text = "$current / $total"
            }
        }

        taskExecutor.stepCallback = { stepIndex, step, success, message ->
            Log.d(TAG, "步骤 $stepIndex: ${if (success) "成功" else "失败"} - $message")
        }

        taskExecutor.completionCallback = { result ->
            runOnUiThread {
                binding.btnStartAll.text = "一键执行"
                val msg = if (result.success) "执行完成" else "执行部分失败: ${result.message}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshTaskList()
            }
        }

        // 步骤失败时弹出调试悬浮窗，挂起等待用户选择重试/修改描述/跳过/手动完成
        taskExecutor.debugCallback = { stepIndex, step, reason, _ ->
            Log.i(TAG, "步骤 ${stepIndex + 1} 失败，等待用户调试: $reason")
            awaitDebugAction(step, reason)
        }
    }

    /**
     * 显示调试悬浮窗并等待用户操作。悬浮窗不可用时直接终止，避免任务永久挂起
     */
    private suspend fun awaitDebugAction(step: TaskStep, reason: String): TaskExecutor.DebugAction {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "没有悬浮窗权限，无法进入调试模式")
            return TaskExecutor.DebugAction.Abort
        }
        return suspendCancellableCoroutine { cont ->
            var finished = false

            fun finish(action: TaskExecutor.DebugAction) {
                if (finished) return
                finished = true
                FloatingWindowService.debugCallback = null
                if (cont.isActive) cont.resume(action)
            }

            FloatingWindowService.debugCallback = object : DebugCallback {
                override fun onRetry() = finish(TaskExecutor.DebugAction.Retry)
                override fun onModifyHint(newHint: String) =
                    finish(TaskExecutor.DebugAction.ModifyAndRetry(newHint))
                override fun onContinue() = finish(TaskExecutor.DebugAction.Skip)
                override fun onManualSpecify() = finish(TaskExecutor.DebugAction.ManualDone)
                override fun onAbort() = finish(TaskExecutor.DebugAction.Abort)
            }

            try {
                startService(Intent(this, FloatingWindowService::class.java).apply {
                    putExtra("action", "show_debug")
                    putExtra("hint", step.hint.ifEmpty { step.targetText })
                    putExtra("reason", reason)
                })
            } catch (e: Exception) {
                Log.e(TAG, "显示调试悬浮窗失败: ${e.message}")
                finish(TaskExecutor.DebugAction.Abort)
            }

            cont.invokeOnCancellation { FloatingWindowService.debugCallback = null }
        }
    }

    private var permissionDialogShowing = false
    private var accessibilityReminderShown = false

    private fun checkPermissions() {
        updatePermissionStatus()
        if (permissionDialogShowing) return
        requestAllPermissionsSequentially()
    }

    /**
     * 按顺序引导权限：一次只弹一个说明框，用户从系统设置返回后由 onResume 继续下一项。
     * 已拒绝过的可选项用 SharedPreferences 记住，避免每次回前台都纠缠
     */
    private fun requestAllPermissionsSequentially() {
        val prefs = getSharedPreferences("auto_task", MODE_PRIVATE)

        // 1. 无障碍服务（必需）：每次启动最多提醒一次
        if (!AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)) {
            if (!accessibilityReminderShown) {
                accessibilityReminderShown = true
                showPermissionRationaleDialog(
                    "无障碍服务",
                    "无障碍服务用于识别界面元素并执行自动操作，必须开启才能使用本应用。\n\n" +
                            "开启后请勿关闭本应用的后台运行权限，否则无障碍服务可能被系统回收。",
                    { openAccessibilitySettings() }
                )
            }
            return
        }

        // 2. 电池白名单（防止系统杀掉无障碍服务），只引导一次
        if (!isIgnoringBatteryOptimizations() && !prefs.getBoolean("battery_whitelist_asked", false)) {
            prefs.edit().putBoolean("battery_whitelist_asked", true).apply()
            showPermissionRationaleDialog(
                "后台运行权限",
                "为了确保自动化任务在后台稳定运行，请在接下来的设置中允许本应用「不受电池优化」。\n\n" +
                        "这是无障碍服务不被系统杀掉的关键！",
                { requestIgnoreBatteryOptimizations() }
            )
            return
        }

        // 3. 悬浮窗（建议），只引导一次
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("overlay_asked", false)) {
            prefs.edit().putBoolean("overlay_asked", true).apply()
            showPermissionRationaleDialog(
                "悬浮窗权限",
                "悬浮窗权限用于显示执行进度，建议开启以便了解任务执行情况。",
                { requestOverlayPermission() }
            )
            return
        }

        // 4. 通知（建议），只请求一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_requested", false)
        ) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            return
        }

        // 截屏授权不在这里弹：纯无障碍任务不需要截图，只在执行视觉兜底时按需请求
    }

    private var hasAskedScreenCapture = false

    /**
     * 执行前确保截图能力可用（API<30 需要 MediaProjection 授权）。
     * @return true 表示刚刚请求了授权，本次执行应中止，授权后用户重新点执行
     */
    private fun ensureVisionScreenshotPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false
        if (ScreenshotService.hasPermission()) return false
        if (repository.getGlobalConfig().strategy == "accessibility") return false

        if (hasAskedScreenCapture) {
            Toast.makeText(this, "视觉识别不可用：可在「权限管理」中重新授予截屏权限", Toast.LENGTH_SHORT).show()
            return false
        }
        hasAskedScreenCapture = true
        Toast.makeText(this, "请先授予截屏权限，授权后重新点击执行", Toast.LENGTH_LONG).show()
        ScreenshotService.requestPermission(this)
        return true
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开电池优化设置: ${e.message}")
            // 降级：打开通用电池优化列表
            try {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "请在设置中手动关闭本应用的电池优化", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 检查是否所有必要权限都已授予
     */
    private fun hasAllPermissions(): Boolean {
        val accessibilityOk = AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val notificationOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        return accessibilityOk && overlayOk && notificationOk
    }

    /**
     * 显示权限说明对话框
     */
    private fun showPermissionRationaleDialog(title: String, message: String, onConfirm: () -> Unit) {
        if (permissionDialogShowing) return
        permissionDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("需要 $title 权限")
            .setMessage(message)
            .setPositiveButton("去开启") { _, _ -> onConfirm() }
            .setNegativeButton("稍后提醒", null)
            .setOnDismissListener { permissionDialogShowing = false }
            .show()
    }

    /**
     * 申请悬浮窗权限
     */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY)
    }

    private fun updatePermissionStatus() {
        val accessibilityEnabled = AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)
        binding.cardPermission.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE

        // 更新底部权限状态文本
        val ctx = this@MainActivity
        val status = buildString {
            append("无障碍: ${if (accessibilityEnabled) "✓" else "✗"} | ")
            append("悬浮窗: ${if (Settings.canDrawOverlays(ctx)) "✓" else "✗"} | ")
            val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            append("通知: ${if (hasNotification) "✓" else "✗"}")
        }
        binding.tvPermissionStatus.text = status
    }

    /**
     * 显示权限管理对话框 - 每个条目带独立「去设置」按钮
     */
    private fun showPermissionDialog() {
        val ctx = this@MainActivity
        val dp = ctx.resources.displayMetrics.density

        // 检查各项权限状态
        val accessibilityEnabled = AutoTaskAccessibilityService.isAccessibilityServiceEnabled(ctx)
        val overlayEnabled = Settings.canDrawOverlays(ctx)
        val batteryIgnored = isIgnoringBatteryOptimizations()
        val notificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        // 权限定义
        data class PermItem(
            val name: String,
            val enabled: Boolean,
            val required: Boolean,
            val description: String,
            val action: () -> Unit
        )

        val permissions = listOf(
            PermItem("无障碍服务", accessibilityEnabled, true, "识别界面元素，执行自动操作") {
                openAccessibilitySettings()
            },
            PermItem("后台运行（电池优化）", batteryIgnored, true, "防止系统杀掉无障碍服务") {
                requestIgnoreBatteryOptimizations()
            },
            PermItem("悬浮窗权限", overlayEnabled, false, "显示执行进度浮窗") {
                requestOverlayPermission()
            },
            PermItem("通知权限", notificationEnabled, false, "显示服务运行状态通知") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
                }
            }
        ) + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            listOf(
                PermItem("截屏权限", ScreenshotService.hasPermission(), false, "视觉识别时截取屏幕") {
                    ScreenshotService.requestPermission(this)
                }
            )
        } else {
            emptyList()
        }

        // 构建自定义布局
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        for (p in permissions) {
            val statusIcon = if (p.enabled) "✅" else "❌"
            val tag = if (p.required) "必需" else "建议"

            // 每个权限行：名称+状态 | 按钮
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }

            // 左侧文字
            val textView = android.widget.TextView(ctx).apply {
                text = "$statusIcon ${p.name}（$tag）"
                textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            // 右侧按钮
            val btn = android.widget.Button(ctx).apply {
                text = "去设置"
                textSize = 13f
                isAllCaps = false
                isEnabled = !p.enabled
                text = if (p.enabled) "已开启" else "去设置"
                setOnClickListener {
                    p.action()
                }
            }

            row.addView(textView)
            row.addView(btn)
            container.addView(row)

            // 分隔线
            val divider = android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * dp).toInt()
                )
                setBackgroundColor(0xFFE0E0E0.toInt())
            }
            container.addView(divider)
        }

        AlertDialog.Builder(ctx)
            .setTitle("权限管理")
            .setView(container)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        // 直接跳转到无障碍设置页
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到「AutoTask」并开启无障碍服务", Toast.LENGTH_LONG).show()
    }

    /**
     * 打开悬浮窗权限设置
     */
    private fun openOverlayPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshTaskList() {
        lifecycleScope.launch {
            val tasks = repository.getAllTasks()
            taskAdapter.submitList(tasks)
        }
    }

    /**
     * 任务拖拽排序后保存新顺序（列表顺序 = 执行顺序）
     */
    private fun handleTaskMove(from: Int, to: Int) {
        lifecycleScope.launch {
            val tasks = repository.getAllTasks().toMutableList()
            if (from in tasks.indices && to in tasks.indices && from != to) {
                val item = tasks.removeAt(from)
                tasks.add(to, item)
                repository.saveAllTasks(tasks)
                refreshTaskList()
            }
        }
    }

    private fun toggleTaskEnabled(task: AutomationTask, enabled: Boolean) {
        lifecycleScope.launch {
            repository.updateTask(task.copy(enabled = enabled))
            refreshTaskList()
        }
    }

    private fun deleteTask(task: AutomationTask) {
        lifecycleScope.launch {
            repository.deleteTask(task.id)
            refreshTaskList()
        }
    }

    private fun showAddTaskDialog() {
        taskListManager.showAddTaskDialog(this) { task ->
            lifecycleScope.launch {
                repository.saveTask(task)
                refreshTaskList()
                Toast.makeText(this@MainActivity, "任务已创建", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeSingleTask(task: AutomationTask) {
        if (!AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (ensureVisionScreenshotPermission()) return

        Toast.makeText(this, "开始执行: ${task.name}", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = taskExecutor.executeTask(task)
            repository.updateTaskResult(task.id, result.success)

            if (result.success) {
                Toast.makeText(this@MainActivity, "✓ ${task.name} 执行成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "✗ ${task.name} 执行失败: ${result.message}", Toast.LENGTH_LONG).show()
            }

            refreshTaskList()
        }
    }

    private fun startAllTasks() {
        if (!AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (ensureVisionScreenshotPermission()) return

        binding.btnStartAll.isEnabled = false
        lifecycleScope.launch {
            try {
                val tasks = repository.getEnabledTasks()
                if (tasks.isEmpty()) {
                    Toast.makeText(this@MainActivity, "没有启用的任务", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Log.i(TAG, "一键执行开始：共 ${tasks.size} 个任务")
                var successCount = 0
                var failCount = 0
                val waitBetween = repository.getGlobalConfig().waitBetweenTasksMs
                for ((index, task) in tasks.withIndex()) {
                    Log.i(TAG, ">>> 第 ${index + 1}/${tasks.size} 个任务: ${task.name} (enabled=${task.enabled})")
                    val result = taskExecutor.executeTask(task)
                    Log.i(TAG, "<<< 第 ${index + 1}/${tasks.size} 个任务结束: ${task.name} - success=${result.success} msg=${result.message}")
                    repository.updateTaskResult(task.id, result.success)
                    if (result.success) successCount++ else failCount++
                    // 任务之间留间隔：上一个任务最后一步操作（如签到）的动画/网络请求可能还在进行，
                    // 等它完成再切下一个应用，避免"刚点了签到就被切走"
                    if (index < tasks.size - 1) {
                        Log.i(TAG, "任务间隔等待 ${waitBetween}ms 后继续下一个任务")
                        delay(waitBetween)
                    }
                }

                Log.i(TAG, "一键执行完成：$successCount 成功, $failCount 失败")
                Toast.makeText(this@MainActivity, "执行完成: $successCount 成功, $failCount 失败", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnStartAll.isEnabled = true
                binding.btnStartAll.text = "一键执行"
                refreshTaskList()
            }
        }
    }

    private fun showEditTaskDialog(task: AutomationTask) {
        taskListManager.showEditTaskDialog(this, task) { updatedTask ->
            lifecycleScope.launch {
                repository.updateTask(updatedTask)
                refreshTaskList()
                Toast.makeText(this@MainActivity, "任务已更新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStepEditorDialog(task: AutomationTask) {
        Log.d("MainActivity", "showStepEditorDialog 被调用: ${task.name}")
        taskListManager.currentContext = this
        taskListManager.showStepEditorDialog(task) { updatedTask ->
            Log.d("MainActivity", "步骤编辑器保存: ${updatedTask.name}")
            lifecycleScope.launch {
                repository.updateTask(updatedTask)
                refreshTaskList()
                Toast.makeText(this@MainActivity, "步骤已更新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "悬浮窗权限未获取，悬浮窗可能无法显示", Toast.LENGTH_LONG).show()
                }
                // 继续检查其他权限
                requestAllPermissionsSequentially()
            }
            REQUEST_SCREENSHOT -> {
                if (ScreenshotService.handlePermissionResult(this, requestCode, resultCode, data)) {
                    Toast.makeText(this, "截屏权限已获取", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未授予截屏权限，视觉识别将不可用", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
