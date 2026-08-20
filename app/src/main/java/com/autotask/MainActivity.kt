package com.autotask

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.autotask.databinding.ActivityMainBinding
import com.autotask.service.AutoTaskAccessibilityService
import com.autotask.service.FloatingWindowService
import com.autotask.service.ScreenshotService
import com.autotask.service.TaskExecutor
import com.autotask.ui.SettingsActivity
import com.autotask.ui.TaskAdapter
import com.autotask.ui.TaskListManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = TaskRepository(this)
        taskListManager = TaskListManager(repository)
        taskExecutor = TaskExecutor(this, repository)

        setupUI()
        setupExecutorCallbacks()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshTaskList()
        updatePermissionStatus()
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
            onEditActions = { task -> showStepEditorDialog(task) }
        )
        binding.rvTasks.layoutManager = LinearLayoutManager(this)
        binding.rvTasks.adapter = taskAdapter

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
            requestAllPermissionsSequentially()
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
                    success == null -> "正在执行: $stepDesc"
                    success -> "✓ $stepDesc"
                    else -> "✗ $stepDesc"
                }
                val intent = Intent(this, FloatingWindowService::class.java).apply {
                    putExtra("action", "show_execute")
                    putExtra("progress", stepIndex + 1)
                    putExtra("total", totalSteps)
                    putExtra("message", message)
                }
                startService(intent)
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
    }

    // 上次请求权限的时间，防止重复请求
    private var lastRequestTime = 0L
    // 是否已请求过截屏权限
    private var hasAskedScreenshot = false

    private fun checkPermissions() {
        updatePermissionStatus()
        // 首次启动或距离上次请求超过 5 秒时才重新检查
        val now = System.currentTimeMillis()
        if (lastRequestTime == 0L || now - lastRequestTime > 5000) {
            lastRequestTime = now
            requestAllPermissionsSequentially()
        }
    }

    /**
     * 按顺序申请所有需要的权限
     * 只在权限未授予时才请求，已授予则跳过
     */
    private fun requestAllPermissionsSequentially() {
        // 1. 检查无障碍服务 - 未开启则引导
        if (!AutoTaskAccessibilityService.isAccessibilityServiceEnabled(this)) {
            showPermissionRationaleDialog(
                "无障碍服务",
                "无障碍服务用于识别界面元素并执行自动操作，必须开启才能使用本应用。",
                { openAccessibilitySettings() }
            )
            return
        }

        // 2. 检查悬浮窗权限 - 未授予则引导
        if (!Settings.canDrawOverlays(this)) {
            showPermissionRationaleDialog(
                "悬浮窗权限",
                "悬浮窗权限用于显示执行进度，建议开启以便了解任务执行情况。",
                { requestOverlayPermission() }
            )
            return
        }

        // 3. 检查通知权限 - 未授予则请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
                return
            }
        }

        // 4. 检查截屏权限 - 只请求一次
        if (!ScreenshotService.hasPermission() && !hasAskedScreenshot) {
            hasAskedScreenshot = true
            showPermissionRationaleDialog(
                "截屏权限",
                "截屏权限用于识别界面元素，仅在需要视觉识别时才会使用。",
                { ScreenshotService.requestPermission(this) }
            )
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
        AlertDialog.Builder(this)
            .setTitle("需要 $title 权限")
            .setMessage(message)
            .setPositiveButton("去开启") { _, _ -> onConfirm() }
            .setNegativeButton("稍后提醒", null)
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

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到「AutoTask」并开启", Toast.LENGTH_LONG).show()
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
            val tasks = repository.getEnabledTasks()
            taskAdapter.submitList(tasks)
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

        lifecycleScope.launch {
            val tasks = repository.getEnabledTasks()
            if (tasks.isEmpty()) {
                Toast.makeText(this@MainActivity, "没有启用的任务", Toast.LENGTH_SHORT).show()
                return@launch
            }

            var successCount = 0
            var failCount = 0
            for (task in tasks) {
                val result = taskExecutor.executeTask(task)
                repository.updateTaskResult(task.id, result.success)
                if (result.success) successCount++ else failCount++
            }

            Toast.makeText(this@MainActivity, "执行完成: $successCount 成功, $failCount 失败", Toast.LENGTH_LONG).show()
            refreshTaskList()
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
                if (ScreenshotService.handlePermissionResult(requestCode, resultCode, data)) {
                    Toast.makeText(this, "截屏权限已获取", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
