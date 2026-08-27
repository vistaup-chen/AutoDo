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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 任务执行引擎 - 双策略执行 + 调试支持
 */
class TaskExecutor(
    private val context: Context,
    private val repository: TaskRepository
) {
    companion object {
        private const val TAG = "TaskExecutor"

        @Volatile
        var instance: TaskExecutor? = null
            private set
    }

    init {
        instance = this
    }

    private val config: GlobalConfig = repository.getGlobalConfig()
    private var textClient = ModelClient(config.textModel)
    private var visionClient = ModelClient(if (config.unifiedModel) config.textModel else config.visionModel)

    // 模型切换锁，防止并发切换
    private val modelSwitchLock = Any()

    /**
     * 切换文本模型
     */
    private fun switchTextModel() {
        synchronized(modelSwitchLock) {
            val newConfig = config.modelGroup.switchToNextTextModel()
            textClient.resetFailureCount()
            textClient = ModelClient(newConfig.getCurrentTextModel())
            if (config.unifiedModel) {
                // 共用模式下视觉客户端即文本客户端
                visionClient = textClient
            }
            Log.i(TAG, "切换到文本模型: ${newConfig.getCurrentTextModel().modelName}")
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
            val newConfig = config.modelGroup.switchToNextVisionModel()
            visionClient.resetFailureCount()
            visionClient = ModelClient(newConfig.getCurrentVisionModel())
            Log.i(TAG, "切换到视觉模型: ${newConfig.getCurrentVisionModel().modelName}")
        }
    }

    /**
     * 处理文本模型调用失败
     */
    private fun handleTextModelFailure() {
        if (config.autoSwitchModel && textClient.recordFailure()) {
            switchTextModel()
        }
    }

    /**
     * 处理视觉模型调用失败
     */
    private fun handleVisionModelFailure() {
        if (config.autoSwitchModel && visionClient.recordFailure()) {
            switchVisionModel()
        }
    }

    /**
     * 处理模型调用成功
     */
    private fun handleModelSuccess(isTextModel: Boolean) {
        if (isTextModel) {
            textClient.recordSuccess()
        } else {
            visionClient.recordSuccess()
        }
    }

    // 执行状态回调
    var progressCallback: ((current: Int, total: Int, message: String) -> Unit)? = null
    var stepCallback: ((stepIndex: Int, step: TaskStep, success: Boolean, message: String) -> Unit)? = null
    var debugCallback: ((stepIndex: Int, step: TaskStep, reason: String, screenshot: Bitmap?) -> DebugAction)? = null
    var completionCallback: ((TaskResult) -> Unit)? = null

    // 悬浮窗回调
    var floatingWindowCallback: ((stepIndex: Int, totalSteps: Int, stepDescription: String, success: Boolean?, isFirst: Boolean) -> Unit)? = null

    // 悬浮窗开关
    var floatingWindowEnabled: Boolean = true

    // 调试动作
    enum class DebugAction {
        RETRY,           // 重试当前步骤
        MODIFY_AND_RETRY,// 修改描述后重试
        SKIP,            // 跳过当前步骤
        MANUAL_CLICK,    // 手动点击（用户指定坐标）
        ABORT            // 终止任务
    }

    private var isExecuting = false
    private var shouldStop = false

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
                val autoLaunched = AutoTaskAccessibilityService.instance?.launchApp(task.packageName) ?: false
                if (autoLaunched) {
                    Log.i(TAG, "已自动启动目标应用: ${task.packageName}")
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

                // 处理模型调用结果
                if (result.success) {
                    handleModelSuccess(isTextModel = true)
                } else {
                    handleTextModelFailure()
                }

                stepCallback?.invoke(index, step, result.success, result.message)
                if (floatingWindowEnabled) {
                    val statusMsg = if (result.success) "完成：$stepDesc" else "失败：$stepDesc（${result.message}）"
                    floatingWindowCallback?.invoke(index, totalSteps, statusMsg, result.success, false)
                }

                if (!result.success && !step.optional) {
                    // 非可选步骤失败，进入调试流程
                    val screenshot = captureScreenshot()
                    val action = debugCallback?.invoke(index, step, result.message, screenshot)
                        ?: DebugAction.ABORT

                    when (action) {
                        DebugAction.RETRY -> {
                            val retryResult = executeStep(task, step, index)
                            stepResults[stepResults.size - 1] = retryResult
                            if (!retryResult.success && !step.optional) {
                                isExecuting = false
                                return finish(TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 重试失败"))
                            }
                        }
                        DebugAction.MODIFY_AND_RETRY -> {
                            val retryResult = executeStep(task, step, index)
                            stepResults[stepResults.size - 1] = retryResult
                            if (!retryResult.success && !step.optional) {
                                isExecuting = false
                                return finish(TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 修改后重试失败"))
                            }
                        }
                        DebugAction.SKIP -> {
                            continue
                        }
                        DebugAction.MANUAL_CLICK -> {
                            continue
                        }
                        DebugAction.ABORT -> {
                            isExecuting = false
                            return finish(TaskResult(task, false, stepResults, totalSteps, index, "用户终止"))
                        }
                    }
                }

                // 步骤间延迟（由配置控制，新默认 1000ms，用户可自行调整）
                delay(config.clickDelayMs)
            }

            val success = stepResults.all { it.success || task.steps[stepResults.indexOf(it)].optional }
            progressCallback?.invoke(totalSteps, totalSteps, if (success) "任务完成" else "任务部分失败")

            isExecuting = false
            return finish(TaskResult(task, success, stepResults, totalSteps, currentStep + 1,
                if (success) "全部步骤执行成功" else "部分步骤失败"))

        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}", e)
            isExecuting = false
            return finish(TaskResult(task, false, stepResults, totalSteps, currentStep, "执行异常: ${e.message}"))
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
     * 启动应用
     */
    private suspend fun executeLaunch(task: AutomationTask, stepIndex: Int): StepResult {
        val step = task.steps.getOrNull(stepIndex) ?: TaskStep(StepAction.LAUNCH)
        val launched = AutoTaskAccessibilityService.instance?.launchApp(task.packageName) ?: false

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

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isPageReady()) {
                val snapshot = takeRawScreenshot()
                if (snapshot != null) {
                    if (lastSnapshot != null) {
                        val diff = bitmapDiffRatio(lastSnapshot, snapshot)
                        lastSnapshot.recycle()
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

        // 1. 立即查找 + 等待重试（元素可能异步加载/延迟出现）
        var node = AutoTaskAccessibilityService.findNodeByText(targetText)
        var retry = 0
        while (node == null && retry < 3) {
            delay(600)
            node = AutoTaskAccessibilityService.findNodeByText(targetText)
            retry++
        }
        if (node != null) {
            return clickFoundNode(node, step, "无障碍点击成功")
        }

        // 2. 目标可能在屏幕外：上下交替滚动查找
        Log.d(TAG, "未找到「$targetText」，尝试滚动查找")
        var scrollCount = 0
        while (scrollCount < 4) {
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

        val screenshotPath = AutoTaskAccessibilityService.instance?.saveScreenshotToFile(screenshot, task.id, stepIndex) ?: ""

        if (step.repeat == "until_no_more") {
            return clickMultipleByVision(task, step, stepIndex, screenshot)
        }

        val location = visionClient.locateElement(step.hint, screenshot)

        return if (location.isValid()) {
            // 视觉粗定位 + 无障碍精校准：
            // 视觉模型单点坐标误差较大，若视觉坐标落在真实可点击节点上，改用节点中心点击（更准）
            val calibrated = AutoTaskAccessibilityService.findClickableNear(location.x, location.y)
            val clickX = calibrated?.first ?: location.x.toFloat()
            val clickY = calibrated?.second ?: location.y.toFloat()
            Log.i(
                TAG,
                "视觉点击: 模型(${location.x}, ${location.y})" +
                    if (calibrated != null) " → 无障碍校准($clickX, $clickY)" else " → 无校准节点，用模型坐标"
            )
            dispatchGestureClick(clickX, clickY)
            StepResult(
                true, step, "视觉点击成功: ($clickX, $clickY)",
                Coordinates(clickX, clickY), screenshotPath
            )
        } else {
            StepResult(false, step, "视觉未找到: ${step.hint}", screenshotPath = screenshotPath)
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
        var clickCount = 0
        val maxAttempts = 20

        repeat(maxAttempts) {
            val locations = visionClient.locateAllElements(step.hint, screenshot)
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
                val remaining = visionClient.locateAllElements(step.hint, newScreenshot)
                if (remaining.isEmpty()) return@repeat
                screenshot = newScreenshot
            }
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

        val startY = if (step.hint.contains("上")) centerY - scrollDistance / 2 else centerY + scrollDistance / 2
        val endY = if (step.hint.contains("上")) centerY + scrollDistance / 2 else centerY - scrollDistance / 2

        AutoTaskAccessibilityService.swipe(centerX, startY, centerX, endY, 300)
        return StepResult(true, step, "滚动完成")
    }

    /**
     * 验证页面
     */
    private suspend fun executeVerify(task: AutomationTask, step: TaskStep, stepIndex: Int): StepResult {
        val screenshot = captureScreenshot()
            ?: return StepResult(false, step, "截图失败")

        val verified = visionClient.verifyPage(step.hint, screenshot)
        return StepResult(verified, step, if (verified) "验证通过" else "验证失败: ${step.hint}")
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
