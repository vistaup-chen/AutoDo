package com.autotask.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autotask.config.Coordinates
import com.autotask.config.GlobalConfig
import com.autotask.config.AutomationTask
import com.autotask.config.StepAction
import com.autotask.config.StepResult
import com.autotask.config.TaskRepository
import com.autotask.config.TaskResult
import com.autotask.config.TaskStep
import com.autotask.model.ElementLocation
import com.autotask.model.ModelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 任务执行引擎 - 双策略执行 + 调试支持
 */
class TaskExecutor(
    private val context: Context,
    private val repository: TaskRepository
) {
    companion object {
        private const val TAG = "AT-TaskExecutor"

        @Volatile
        var instance: TaskExecutor? = null
            private set
    }

    init {
        instance = this
    }

    private var config: GlobalConfig = repository.getGlobalConfig()
    private var textClient = ModelClient(config.textModel)
    private var visionClient = ModelClient(if (config.unifiedModel) config.textModel else config.visionModel)

    // 模型切换锁，防止并发切换
    private val modelSwitchLock = Any()

    /**
     * 设置页修改后无需重启 App：执行前比对配置，有变化则重建客户端
     */
    private fun refreshConfigIfChanged() {
        val latest = repository.getGlobalConfig()
        if (latest == config) return
        config = latest
        textClient = ModelClient(latest.textModel)
        visionClient = ModelClient(if (latest.unifiedModel) latest.textModel else latest.visionModel)
        Log.i(TAG, "配置已刷新: 文本=${latest.textModel.modelName} 视觉=${latest.effectiveVisionModel.modelName}")
    }

    /**
     * 切换文本模型：索引必须写回配置，否则每次都从同一个旧索引算 next，永远只切到同一个备用模型
     */
    private fun switchTextModel() {
        synchronized(modelSwitchLock) {
            var group = config.modelGroup
            // 跳过用户列表里地址/模型名为空的占位项，避免切到不可用模型
            for (i in group.textModels.indices) {
                group = group.switchToNextTextModel()
                val candidate = group.getCurrentTextModel()
                if (candidate.apiBase.isNotBlank() && candidate.modelName.isNotBlank()) {
                    val updated = config.copy(modelGroup = group)
                    config = updated
                    repository.saveGlobalConfig(updated)
                    textClient = ModelClient(candidate)
                    if (updated.unifiedModel) {
                        // 共用模式下视觉客户端即文本客户端
                        visionClient = textClient
                    }
                    Log.i(TAG, "切换到文本模型: ${candidate.modelName}")
                    return
                }
            }
            Log.w(TAG, "没有可用的备用文本模型（地址或模型名为空）")
        }
    }

    /**
     * 切换视觉模型
     */
    private fun switchVisionModel() {
        synchronized(modelSwitchLock) {
            if (config.unifiedModel) {
                // 共用模式下视觉即文本，切换文本模型即可
                switchTextModel()
                return
            }
            var group = config.modelGroup
            for (i in group.visionModels.indices) {
                group = group.switchToNextVisionModel()
                val candidate = group.getCurrentVisionModel()
                if (candidate.apiBase.isNotBlank() && candidate.modelName.isNotBlank()) {
                    val updated = config.copy(modelGroup = group)
                    config = updated
                    repository.saveGlobalConfig(updated)
                    visionClient = ModelClient(candidate)
                    Log.i(TAG, "切换到视觉模型: ${candidate.modelName}")
                    return
                }
            }
            Log.w(TAG, "没有可用的备用视觉模型（地址或模型名为空）")
        }
    }

    /**
     * 视觉模型 API 调用失败：连续失败达到阈值才切换
     */
    private fun handleVisionModelFailure() {
        if (config.autoSwitchModel && visionClient.recordFailure()) {
            switchVisionModel()
        }
    }

    private fun handleVisionModelSuccess() {
        visionClient.recordSuccess()
    }

    // 执行状态回调
    var progressCallback: ((current: Int, total: Int, message: String) -> Unit)? = null
    var stepCallback: ((stepIndex: Int, step: TaskStep, success: Boolean, message: String) -> Unit)? = null

    /**
     * 步骤失败时的调试回调：挂起等待用户在悬浮窗上操作。
     * 未接线（null）时直接终止任务，保持旧行为
     */
    var debugCallback: (suspend (stepIndex: Int, step: TaskStep, reason: String, screenshot: Bitmap?) -> DebugAction)? = null
    var completionCallback: ((TaskResult) -> Unit)? = null

    // 悬浮窗回调
    var floatingWindowCallback: ((stepIndex: Int, totalSteps: Int, stepDescription: String, success: Boolean?, isFirst: Boolean) -> Unit)? = null

    // 悬浮窗开关
    var floatingWindowEnabled: Boolean = true

    // 调试动作
    sealed class DebugAction {
        object Retry : DebugAction()                                  // 原样重试
        data class ModifyAndRetry(val newHint: String) : DebugAction() // 修改描述后重试
        object Skip : DebugAction()                                   // 跳过当前步骤
        object ManualDone : DebugAction()                             // 用户已手动完成本步骤
        object Abort : DebugAction()                                  // 终止任务
    }

    @Volatile
    private var isExecuting = false

    @Volatile
    private var shouldStop = false

    // 执行协程不挂在 Activity 的 lifecycleScope 上：切换应用/Activity 被回收时任务仍能跑完
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * 后台执行单个任务，结果回主线程
     */
    fun executeAsync(task: AutomationTask, onFinished: suspend (TaskResult) -> Unit) {
        executionScope.launch {
            val result = executeTask(task)
            onFinished(result)
        }
    }

    /**
     * 在独立作用域里执行批量流程（一键执行）
     */
    fun launchBatch(block: suspend () -> Unit) {
        executionScope.launch { block() }
    }

    private fun acquireWakeLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK,
                "AutoTask:execution"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取屏幕常亮锁失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "释放屏幕常亮锁失败: ${e.message}")
        }
        wakeLock = null
    }

    /**
     * 执行单个任务
     */
    suspend fun executeTask(task: AutomationTask): TaskResult {
        if (isExecuting) {
            Log.w(TAG, "任务「${task.name}」被跳过：已有任务在执行中")
            return TaskResult(task, false, message = "已有任务在执行中")
        }

        isExecuting = true
        shouldStop = false
        refreshConfigIfChanged()
        // 长任务执行期间息屏会导致截图/手势失败，执行期间保持屏幕常亮
        acquireWakeLock()
        Log.i(TAG, "===== 开始执行任务「${task.name}」（${task.steps.size} 步，包名=${task.packageName}）=====")

        val stepResults = mutableListOf<StepResult>()
        var currentStep = 0
        val totalSteps = task.steps.size

        // 任务结束时通知悬浮窗显示最终结果（成功/失败/原因），方便不接电脑排查问题
        fun finish(result: TaskResult): TaskResult {
            Log.i(TAG, "===== 任务「${result.task.name}」结束: ${if (result.success) "成功" else "失败"} - ${result.message} =====")
            if (floatingWindowEnabled) {
                val status = if (result.success) "✓ 任务完成" else "✗ 任务失败"
                floatingWindowCallback?.invoke(
                    maxOf(0, result.totalSteps - 1),
                    result.totalSteps,
                    "$status：${result.message}",
                    result.success,
                    false
                )
                // 任务结束后自动收起悬浮窗，否则会挡住其他应用的点击区域
                try {
                    context.startService(
                        Intent(context, FloatingWindowService::class.java)
                            .putExtra("action", "hide_delayed")
                            .putExtra("delay_ms", 5000L)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "安排悬浮窗自动隐藏失败: ${e.message}")
                }
            }
            return result
        }

        try {
            progressCallback?.invoke(0, totalSteps, "开始执行: ${task.name}")
            if (floatingWindowEnabled) {
                floatingWindowCallback?.invoke(0, totalSteps, "准备开始执行「${task.name}」", null, true)
            }

            // 自动启动目标应用：任务绑定了 packageName 且步骤里没有「启动应用」步骤时，
            // 引擎先拉起目标 App（解决 AI 解析生成的任务第一步是"点击 APP 图标"导致应用未被拉起的问题）
            if (task.packageName.isNotEmpty() && task.steps.firstOrNull()?.action != StepAction.LAUNCH) {
                val autoLaunched = AutoTaskAccessibilityService.instance?.launchApp(resolveLaunchPackage(task)) ?: false
                if (autoLaunched) {
                    Log.i(TAG, "已自动启动目标应用: ${resolveLaunchPackage(task)}")
                    delay(config.waitAfterLaunchMs)
                    // 等页面加载完成再开始执行步骤
                    waitForPageReady()
                } else {
                    Log.w(TAG, "自动启动应用失败: ${task.packageName}")
                    // 应用拉不起来则直接终止任务，避免在错误界面执行后续步骤
                    isExecuting = false
                    return finish(TaskResult(task, false, stepResults, totalSteps, 0, "启动应用失败: ${task.packageName}"))
                }
            }

            for ((index, step) in task.steps.withIndex()) {
                if (shouldStop) break

                currentStep = index
                val stepDesc = getStepDescription(step)
                progressCallback?.invoke(index, totalSteps, "步骤 ${index + 1}/$totalSteps: $stepDesc")
                if (floatingWindowEnabled) {
                    floatingWindowCallback?.invoke(index, totalSteps, "正在执行：$stepDesc", null, false)
                }

                val result = executeStep(task, step, index)
                stepResults.add(result)

                stepCallback?.invoke(index, step, result.success, result.message)
                if (floatingWindowEnabled) {
                    val statusMsg = if (result.success) "完成：$stepDesc" else "失败：$stepDesc（${result.message}）"
                    floatingWindowCallback?.invoke(index, totalSteps, statusMsg, result.success, false)
                }

                if (!result.success && !step.optional) {
                    // 非可选步骤失败，进入调试流程（等待用户在悬浮窗上选择处理方式）
                    val screenshot = captureScreenshot()
                    val action = try {
                        withTimeoutOrNull(120_000) {
                            debugCallback?.invoke(index, step, result.message, screenshot)
                        } ?: DebugAction.Abort
                    } finally {
                        screenshot?.recycle()
                    }

                    when (action) {
                        DebugAction.Retry -> {
                            val retryResult = executeStep(task, step, index)
                            stepResults[stepResults.size - 1] = retryResult
                            if (!retryResult.success && !step.optional) {
                                isExecuting = false
                                return finish(TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 重试失败"))
                            }
                        }
                        is DebugAction.ModifyAndRetry -> {
                            val modified = step.copy(hint = action.newHint, targetText = action.newHint)
                            Log.i(TAG, "步骤 ${index + 1} 修改描述后重试: \"${action.newHint}\"")
                            val retryResult = executeStep(task, modified, index)
                            stepResults[stepResults.size - 1] = retryResult
                            if (!retryResult.success && !step.optional) {
                                isExecuting = false
                                return finish(TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 修改后重试失败"))
                            }
                        }
                        DebugAction.Skip -> {
                            continue
                        }
                        DebugAction.ManualDone -> {
                            stepResults[stepResults.size - 1] = StepResult(true, step, "用户手动完成")
                        }
                        DebugAction.Abort -> {
                            isExecuting = false
                            return finish(TaskResult(task, false, stepResults, totalSteps, index, "用户终止"))
                        }
                    }
                }

                // 步骤间延迟（由配置控制，新默认 1000ms，用户可自行调整）
                delay(config.clickDelayMs)
            }

            // 用下标关联原始步骤判断 optional，不能用 indexOf（内容相同的 StepResult 会取到错误下标）
            val success = stepResults.withIndex().all { (i, r) ->
                r.success || task.steps.getOrNull(i)?.optional == true
            }
            progressCallback?.invoke(totalSteps, totalSteps, if (success) "任务完成" else "任务部分失败")

            isExecuting = false
            return finish(TaskResult(task, success, stepResults, totalSteps, currentStep + 1,
                if (success) "全部步骤执行成功" else "部分步骤失败"))

        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消不是任务失败，向上传播让结构化并发正常收尾
            isExecuting = false
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}", e)
            isExecuting = false
            return finish(TaskResult(task, false, stepResults, totalSteps, currentStep, "执行异常: ${e.message}"))
        } finally {
            releaseWakeLock()
        }
    }

    /**
     * 停止执行
     */
    fun stop() {
        shouldStop = true
    }

    /**
     * 执行单个步骤
     */
    private suspend fun executeStep(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        return when (step.action) {
            StepAction.LAUNCH -> executeLaunch(task, stepIndex)
            StepAction.WAIT -> executeWait(step)
            StepAction.CLICK -> executeClick(task, step, stepIndex)
            StepAction.INPUT -> executeInput(task, step, stepIndex)
            StepAction.SCROLL -> executeScroll(task, step)
            StepAction.VERIFY -> executeVerify(task, step, stepIndex)
            StepAction.BACK -> executeBack()
            StepAction.SWIPE -> executeSwipe(task, step)
        }
    }

    /**
     * 解析任务的实际启动包名：
     * 小程序任务（packageName = "miniapp:名称"）实际启动微信，再通过微信内导航进入小程序
     */
    private fun resolveLaunchPackage(task: AutomationTask): String {
        return if (task.packageName.startsWith("miniapp:")) "com.tencent.mm" else task.packageName
    }

    /**
     * 启动应用
     */
    private suspend fun executeLaunch(task: AutomationTask, stepIndex: Int): StepResult {
        val step = task.steps.getOrNull(stepIndex) ?: TaskStep(StepAction.LAUNCH)
        val launched = AutoTaskAccessibilityService.instance?.launchApp(resolveLaunchPackage(task)) ?: false

        return if (launched) {
            delay(config.waitAfterLaunchMs)
            // 等页面真正加载完成再开始后续操作，避免冷启动白屏时点击失败
            waitForPageReady()
            StepResult(true, step, "应用已启动")
        } else {
            StepResult(false, step, "启动应用失败: ${task.packageName}")
        }
    }

    /**
     * 等待页面加载完成：
     * 1. 无障碍节点树就绪（有新窗口的 UI 内容）
     * 2. 页面静止（连续两次截图几乎无差异）
     * 两个条件都满足才认为页面加载完成；超时（默认 15 秒）后继续执行，不阻塞任务。
     */
    private suspend fun waitForPageReady(timeoutMs: Long = 15000) {
        val start = System.currentTimeMillis()
        var lastSnapshot: Bitmap? = null
        var stableCount = 0
        var screenshotFailures = 0

        try {
            // 冷启动白屏阶段截图没有意义，先给目标应用一点首帧时间
            delay(500)
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (isPageReady()) {
                    val snapshot = takeRawScreenshot()
                    if (snapshot == null) {
                        screenshotFailures++
                        // 截图不可用（未授权/服务未启动）时不要空等满超时，节点就绪即可继续
                        if (screenshotFailures >= 3) {
                            Log.w(TAG, "截图不可用，跳过页面静止检测，继续执行")
                            return
                        }
                    } else {
                        screenshotFailures = 0
                        if (lastSnapshot != null) {
                            val previous = lastSnapshot
                            val diff = withContext(Dispatchers.Default) { bitmapDiffRatio(previous, snapshot) }
                            previous.recycle()
                            lastSnapshot = null
                            if (diff < 0.005) {
                                stableCount++
                                if (stableCount >= 2) {
                                    Log.i(TAG, "页面加载完成（节点就绪 + 页面静止，差异 $diff）")
                                    snapshot.recycle()
                                    return
                                }
                            } else {
                                stableCount = 0
                            }
                        }
                        lastSnapshot = snapshot
                    }
                }
                delay(800)
            }
            Log.w(TAG, "等待页面加载完成超时(${timeoutMs}ms)，继续执行")
        } finally {
            lastSnapshot?.recycle()
        }
    }

    /**
     * 页面节点树是否就绪：轻量判断（root 存在且有子节点结构）。
     * 不做全树遍历（每次遍历都会触发整棵树 Binder 传输，刷屏大事务警告），
     * 页面是否真正稳定由截图静止判断兜底。
     */
    private fun isPageReady(): Boolean {
        val service = AutoTaskAccessibilityService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        val ready = root.childCount > 0
        root.recycle()
        return ready
    }

    /**
     * 原始截图（不触发悬浮窗隐藏/恢复），用于页面静止判断
     */
    private suspend fun takeRawScreenshot(): Bitmap? {
        return withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                AutoTaskAccessibilityService.takeScreenshot()
            } else {
                ScreenshotService.captureScreenshot()
            }
        }
    }

    /**
     * 计算两张截图的像素差异比例（抽样 1/16，降低开销）
     */
    private fun bitmapDiffRatio(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var diffCount = 0
        var total = 0
        val step = 16
        for (x in 0 until a.width step step) {
            for (y in 0 until a.height step step) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                total++
                if (kotlin.math.abs(android.graphics.Color.red(pa) - android.graphics.Color.red(pb)) > 12 ||
                    kotlin.math.abs(android.graphics.Color.green(pa) - android.graphics.Color.green(pb)) > 12 ||
                    kotlin.math.abs(android.graphics.Color.blue(pa) - android.graphics.Color.blue(pb)) > 12
                ) {
                    diffCount++
                }
            }
        }
        return if (total == 0) 1.0 else diffCount.toDouble() / total
    }

    /**
     * 等待
     */
    private suspend fun executeWait(step: TaskStep): StepResult {
        delay(step.duration * 1000)
        return StepResult(true, step, "等待 ${step.duration} 秒")
    }

    /**
     * 点击 - 双策略
     */
    private suspend fun executeClick(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val strategy = if (step.strategy == "auto") config.strategy else step.strategy

        return when (strategy) {
            "accessibility" -> clickByAccessibility(step)
            "vision" -> clickByVision(task, step, stepIndex)
            else -> clickByAuto(task, step, stepIndex) // auto: 无障碍优先，视觉兜底
        }
    }

    /**
     * 策略A: 无障碍节点查找点击
     * 增强：等待重试（异步加载）+ 自动滚动查找（目标在屏幕外）+ 可点击祖先点击（文字在子节点）
     */
    private suspend fun clickByAccessibility(step: TaskStep): StepResult {
        val targetText = step.targetText.ifEmpty { step.hint }
        val maxRetries = config.maxRetries.coerceIn(1, 10)
        val deadline = System.currentTimeMillis() + config.stepTimeoutMs.coerceAtLeast(3000)

        // 1. 立即查找 + 等待重试（元素可能异步加载/延迟出现）
        var node = AutoTaskAccessibilityService.findNodeByText(targetText)
        if (node == null) {
            Log.d(TAG, "首次未找到「$targetText」，等待重试")
        }
        var retry = 0
        while (node == null && retry < maxRetries && System.currentTimeMillis() < deadline) {
            delay(600)
            node = AutoTaskAccessibilityService.findNodeByText(targetText)
            retry++
            if (node == null) {
                Log.d(TAG, "重试 $retry/$maxRetries 仍未找到「$targetText」")
            }
        }
        if (node != null) {
            return clickFoundNode(node, step, "无障碍点击成功")
        }

        // 2. 目标可能在屏幕外：上下交替滚动查找
        Log.d(TAG, "未找到「$targetText」，尝试滚动查找")
        var scrollCount = 0
        while (scrollCount < maxRetries && System.currentTimeMillis() < deadline) {
            scrollPage(down = scrollCount % 2 == 0)
            delay(800)
            node = AutoTaskAccessibilityService.findNodeByText(targetText)
            if (node != null) {
                return clickFoundNode(node, step, "滚动后无障碍点击成功")
            }
            scrollCount++
        }

        return StepResult(false, step, "无障碍未找到: $targetText")
    }

    /**
     * 点击已找到的节点：
     * 1. 优先找可点击的自身/祖先（原生控件文字常在子节点，可点击在父容器）
     * 2. performAction 点击，失败则手势点击节点中心
     */
    private suspend fun clickFoundNode(node: AccessibilityNodeInfo, step: TaskStep, successMsg: String): StepResult {
        // 找可点击的自身或祖先
        val clickTarget = AutoTaskAccessibilityService.findClickableSelfOrAncestor(node) ?: node
        val rect = android.graphics.Rect()
        clickTarget.getBoundsInScreen(rect)

        // 坐标有效性检查：bounds 无效（0,0 或不在屏幕内）说明节点数据失效，
        // 直接返回失败走视觉兜底，绝不能点 (0,0) 还报成功
        val displayMetrics = context.resources.displayMetrics
        val screenRect = android.graphics.Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        if (rect.width() <= 0 || rect.height() <= 0 || !android.graphics.Rect.intersects(rect, screenRect)) {
            Log.w(TAG, "节点坐标无效: $rect 节点=\"${clickTarget.text}\" class=${clickTarget.className}，返回失败")
            clickTarget.recycle()
            if (clickTarget !== node) {
                node.recycle()
            }
            return StepResult(false, step, "节点坐标无效")
        }

        val success = try {
            clickTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            false
        }

        if (!success) {
            // 手势点击目标节点中心
            dispatchGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        Log.d(TAG, "$successMsg: 节点=\"${clickTarget.text}\" class=${clickTarget.className} 坐标=(${rect.centerX()}, ${rect.centerY()})")
        clickTarget.recycle()
        if (clickTarget !== node) {
            node.recycle()
        }
        return StepResult(true, step, if (success) successMsg else "无障碍手势点击成功")
    }

    /**
     * 页面滚动（用于"目标在屏幕外"的查找）
     */
    private suspend fun scrollPage(down: Boolean) {
        val displayMetrics = context.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val scrollDistance = displayMetrics.heightPixels * 0.4f
        val startY = if (down) centerY + scrollDistance / 2 else centerY - scrollDistance / 2
        val endY = if (down) centerY - scrollDistance / 2 else centerY + scrollDistance / 2
        AutoTaskAccessibilityService.swipe(centerX, startY, centerX, endY, 300)
    }

    /**
     * 策略B: 视觉识图定位点击
     */
    private suspend fun clickByVision(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val screenshot = captureScreenshot()
            ?: return StepResult(false, step, "截图失败")

        try {
            if (step.repeat == "until_no_more") {
                return clickMultipleByVision(task, step, stepIndex, screenshot)
            }

            val location = try {
                visionClient.locateElement(step.hint, screenshot)
            } catch (e: Exception) {
                Log.e(TAG, "视觉模型调用失败: ${e.message}")
                handleVisionModelFailure()
                return StepResult(false, step, "视觉模型调用失败: ${e.message}")
            }

            if (!location.isValid()) {
                // 只在失败时落盘截图，便于排查；成功路径不再每次写一张大图
                val screenshotPath = AutoTaskAccessibilityService.instance
                    ?.saveScreenshotToFile(screenshot, task.id, stepIndex) ?: ""
                return StepResult(false, step, "视觉未找到: ${step.hint}", screenshotPath = screenshotPath)
            }

            handleVisionModelSuccess()
            // 视觉粗定位 + 无障碍精校准：
            // 视觉模型单点坐标误差较大，若视觉坐标落在真实可点击节点上，改用节点中心点击（更准）
            val calibrated = AutoTaskAccessibilityService.findClickableNear(location.x, location.y)

            // 防误点：目标明确是数字/符号按钮时，校准节点的文本必须与目标一致，
            // 否则宁可失败也不要点到相邻按钮（GLM-4V 实测会把"1"定位到"6"）
            if (calibrated != null && isNumericOrSymbolTarget(step)) {
                val expected = com.autotask.util.SearchTerms.build(step.targetText.ifEmpty { step.hint })
                val label = calibrated.label
                val matched = label.isEmpty() || expected.any {
                    it.equals(label, ignoreCase = true) ||
                        label.contains(it, ignoreCase = true) ||
                        it.contains(label, ignoreCase = true)
                }
                if (!matched) {
                    Log.w(TAG, "视觉定位疑似错误: 坐标(${location.x}, ${location.y})落在「$label」上，目标「${step.hint}」")
                    return StepResult(false, step, "视觉定位疑似错误: 落在「$label」上")
                }
            }

            val clickX = calibrated?.x ?: location.x.toFloat()
            val clickY = calibrated?.y ?: location.y.toFloat()
            Log.i(
                TAG,
                "视觉点击: 模型(${location.x}, ${location.y})" +
                    if (calibrated != null) " → 无障碍校准($clickX, $clickY)" else " → 无校准节点，用模型坐标"
            )
            dispatchGestureClick(clickX, clickY)
            return StepResult(
                true, step, "视觉点击成功: ($clickX, $clickY)",
                Coordinates(clickX, clickY)
            )
        } finally {
            if (!screenshot.isRecycled) screenshot.recycle()
        }
    }

    /**
     * 自动策略: 无障碍优先，视觉兜底
     */
    private suspend fun clickByAuto(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val a11yResult = clickByAccessibility(step)
        if (a11yResult.success) return a11yResult

        Log.d(TAG, "无障碍失败，切换到视觉: ${step.hint}")
        return clickByVision(task, step, stepIndex)
    }

    /**
     * 批量点击（如蚂蚁森林收能量球）
     */
    private suspend fun clickMultipleByVision(task: AutomationTask, step: TaskStep, stepIndex: Int, initialScreenshot: Bitmap): StepResult {
        var screenshot = initialScreenshot
        var ownsScreenshot = false
        var clickCount = 0
        val maxAttempts = 20

        try {
            repeat(maxAttempts) {
                val locations = try {
                    visionClient.locateAllElements(step.hint, screenshot)
                } catch (e: Exception) {
                    Log.e(TAG, "批量视觉定位失败: ${e.message}")
                    handleVisionModelFailure()
                    return@repeat
                }
                if (locations.isEmpty()) return@repeat

                for (loc in locations) {
                    if (loc.isValid()) {
                        dispatchGestureClick(loc.x.toFloat(), loc.y.toFloat())
                        clickCount++
                        delay(config.clickDelayMs)
                    }
                }

                delay(500)
                val newScreenshot = captureScreenshot()
                if (newScreenshot != null) {
                    val remaining = try {
                        visionClient.locateAllElements(step.hint, newScreenshot)
                    } catch (e: Exception) {
                        handleVisionModelFailure()
                        emptyList()
                    }
                    if (ownsScreenshot && !screenshot.isRecycled) screenshot.recycle()
                    screenshot = newScreenshot
                    ownsScreenshot = true
                    if (remaining.isEmpty()) return@repeat
                }
            }
        } finally {
            if (ownsScreenshot && !screenshot.isRecycled) screenshot.recycle()
        }

        return if (clickCount > 0) {
            StepResult(true, step, "批量点击完成，共点击 $clickCount 次")
        } else {
            StepResult(false, step, "视觉未找到任何目标: ${step.hint}")
        }
    }

    /**
     * 输入文字
     */
    private suspend fun executeInput(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val clickResult = clickByAuto(task, step.copy(action = StepAction.CLICK, hint = step.hint), stepIndex)
        if (!clickResult.success) {
            // 点击输入框失败：降级——直接定位屏幕上的可编辑节点并点击
            Log.d(TAG, "点击输入框失败，尝试直接定位可编辑节点")
            val editable = AutoTaskAccessibilityService.findFirstEditableNode()
            if (editable != null) {
                val rect = android.graphics.Rect()
                editable.getBoundsInScreen(rect)
                editable.recycle()
                dispatchGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                delay(300)
            } else {
                return StepResult(false, step, "点击输入框失败")
            }
        }

        val success = inputText(step.inputText)
        return if (success) {
            StepResult(true, step, "输入成功: ${step.inputText}")
        } else {
            StepResult(false, step, "输入失败")
        }
    }

    /**
     * 滚动
     */
    private suspend fun executeScroll(task: AutomationTask, step: TaskStep): StepResult {
        val displayMetrics = context.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val scrollDistance = displayMetrics.heightPixels * 0.5f

        // AI 解析出的方向可能是 up/down，人工步骤可能是"向上/向下"
        val up = step.hint.contains("上") || step.hint.trim().equals("up", ignoreCase = true)
        val startY = if (up) centerY - scrollDistance / 2 else centerY + scrollDistance / 2
        val endY = if (up) centerY + scrollDistance / 2 else centerY - scrollDistance / 2

        AutoTaskAccessibilityService.swipe(centerX, startY, centerX, endY, 300)
        return StepResult(true, step, "滚动完成")
    }

    /**
     * 验证页面
     */
    private suspend fun executeVerify(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val screenshot = captureScreenshot()
            ?: return StepResult(false, step, "截图失败")

        try {
            val verified = try {
                visionClient.verifyPage(step.hint, screenshot)
            } catch (e: Exception) {
                Log.e(TAG, "视觉验证调用失败: ${e.message}")
                handleVisionModelFailure()
                return StepResult(false, step, "视觉模型调用失败: ${e.message}")
            }
            handleVisionModelSuccess()
            return StepResult(verified, step, if (verified) "验证通过" else "验证失败: ${step.hint}")
        } finally {
            if (!screenshot.isRecycled) screenshot.recycle()
        }
    }

    /**
     * 返回
     */
    private suspend fun executeBack(): StepResult {
        AutoTaskAccessibilityService.goBack()
        return StepResult(true, TaskStep(StepAction.BACK), "返回上一页")
    }

    /**
     * 滑动
     */
    private suspend fun executeSwipe(task: AutomationTask, step: TaskStep): StepResult {
        val coords = step.coordinates
        return if (coords != null) {
            AutoTaskAccessibilityService.swipe(coords.x, coords.y, coords.x, coords.y - 500)
            StepResult(true, step, "滑动完成")
        } else {
            StepResult(false, step, "未指定滑动坐标")
        }
    }

    // ==================== 辅助方法 ====================

    private suspend fun captureScreenshot(): Bitmap? {
        // 截图前临时隐藏悬浮窗：悬浮窗是系统级 overlay，会遮挡屏幕且文字会干扰视觉模型识别
        try {
            context.startService(
                Intent(context, FloatingWindowService::class.java)
                    .putExtra("action", "hide_for_screenshot")
            )
            delay(300) // 等窗口移除稳定
        } catch (e: Exception) {
            Log.w(TAG, "隐藏悬浮窗失败: ${e.message}")
        }

        var bmp = withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                AutoTaskAccessibilityService.takeScreenshot()
            } else {
                ScreenshotService.captureScreenshot()
            }
        }

        // 失败重试一次（隐藏/恢复悬浮窗的窗口操作可能干扰截图；重试时不隐藏直接截）
        if (bmp == null) {
            Log.w(TAG, "第一次截图失败(code=${AutoTaskAccessibilityService.lastScreenshotError})，重试")
            delay(400)
            bmp = withContext(Dispatchers.IO) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    AutoTaskAccessibilityService.takeScreenshot()
                } else {
                    ScreenshotService.captureScreenshot()
                }
            }
        }

        // 截图完成后恢复悬浮窗
        try {
            context.startService(
                Intent(context, FloatingWindowService::class.java)
                    .putExtra("action", "restore_execute")
            )
        } catch (e: Exception) {
            Log.w(TAG, "恢复悬浮窗失败: ${e.message}")
        }

        if (bmp == null && AutoTaskAccessibilityService.lastScreenshotError == 3) {
            Log.e(TAG, "截图被系统拦截：Android 14+ 需要用户确认截图权限，请留意系统弹窗并点击「允许」")
        }

        return bmp
    }

    /**
     * 目标是否是明确的数字/符号按钮（这类误点后果最直接，需要文本校验）
     */
    private fun isNumericOrSymbolTarget(step: TaskStep): Boolean {
        val text = step.targetText.ifEmpty { step.hint }
        return Regex("[0-9０-９+\\-=×÷*/.]|加号|减号|乘号|除号|等号|小数点|数字").containsMatchIn(text)
    }

    private suspend fun dispatchGestureClick(x: Float, y: Float) {
        withContext(Dispatchers.Main) {
            AutoTaskAccessibilityService.click(x, y)
        }
    }

    private fun inputText(text: String): Boolean {
        return try {
            // 获取当前焦点节点
            val root = AutoTaskAccessibilityService.instance?.rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

            // 焦点节点没有则兜底找屏幕上的可编辑节点（输入框）
            val target = focusedNode ?: AutoTaskAccessibilityService.findFirstEditableNode()

            if (target != null) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                target.recycle()
                root.recycle()
                if (!success) {
                    Log.w(TAG, "ACTION_SET_TEXT 失败: $text")
                }
                success
            } else {
                root.recycle()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getStepDescription(step: TaskStep): String {
        return when (step.action) {
            StepAction.LAUNCH -> "启动应用"
            StepAction.WAIT -> "等待 ${step.duration} 秒"
            StepAction.CLICK -> "点击: ${step.hint}"
            StepAction.INPUT -> "输入: ${step.inputText}"
            StepAction.SCROLL -> "滚动页面"
            StepAction.VERIFY -> "验证: ${step.hint}"
            StepAction.BACK -> "返回"
            StepAction.SWIPE -> "滑动"
        }
    }
}
