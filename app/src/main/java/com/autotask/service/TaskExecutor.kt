package com.autotask.service

import android.content.Context
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
    private var visionClient = ModelClient(config.visionModel)

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
            Log.i(TAG, "切换到文本模型: ${newConfig.getCurrentTextModel().modelName}")
        }
    }

    /**
     * 切换视觉模型
     */
    private fun switchVisionModel() {
        synchronized(modelSwitchLock) {
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
        if (isExecuting) return TaskResult(task, false, message = "已有任务在执行中")

        isExecuting = true
        shouldStop = false

        val stepResults = mutableListOf<StepResult>()
        var currentStep = 0
        val totalSteps = task.steps.size

        try {
            progressCallback?.invoke(0, totalSteps, "开始执行: ${task.name}")
            if (floatingWindowEnabled) {
                floatingWindowCallback?.invoke(0, totalSteps, "准备开始执行「${task.name}」", null, true)
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
                                return TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 重试失败")
                            }
                        }
                        DebugAction.MODIFY_AND_RETRY -> {
                            val retryResult = executeStep(task, step, index)
                            stepResults[stepResults.size - 1] = retryResult
                            if (!retryResult.success && !step.optional) {
                                isExecuting = false
                                return TaskResult(task, false, stepResults, totalSteps, index, "步骤 ${index + 1} 修改后重试失败")
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
                            return TaskResult(task, false, stepResults, totalSteps, index, "用户终止")
                        }
                    }
                }

                // 步骤间延迟
                delay(config.clickDelayMs)
            }

            val success = stepResults.all { it.success || task.steps[stepResults.indexOf(it)].optional }
            progressCallback?.invoke(totalSteps, totalSteps, if (success) "任务完成" else "任务部分失败")

            isExecuting = false
            return TaskResult(task, success, stepResults, totalSteps, currentStep + 1,
                if (success) "全部步骤执行成功" else "部分步骤失败")

        } catch (e: Exception) {
            Log.e(TAG, "任务执行异常: ${e.message}", e)
            isExecuting = false
            return TaskResult(task, false, stepResults, totalSteps, currentStep, "执行异常: ${e.message}")
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
            StepAction.LAUNCH -> executeLaunch(task)
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
    private suspend fun executeLaunch(task: AutomationTask): StepResult {
        val step = task.steps.first()
        val launched = AutoTaskAccessibilityService.instance?.launchApp(task.packageName) ?: false

        return if (launched) {
            delay(config.waitAfterLaunchMs)
            StepResult(true, step, "应用已启动")
        } else {
            StepResult(false, step, "启动应用失败: ${task.packageName}")
        }
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
     */
    private suspend fun clickByAccessibility(step: TaskStep): StepResult {
        val targetText = step.targetText.ifEmpty { step.hint }

        val node = AutoTaskAccessibilityService.findNodeByText(targetText)
        if (node != null) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            // 优先尝试无障碍 ACTION_CLICK
            val success = try {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                false
            }

            if (!success) {
                // 失败则用手势点击
                dispatchGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
            }

            node.recycle()
            return StepResult(true, step, if (success) "无障碍点击成功" else "无障碍手势点击成功")
        }

        return StepResult(false, step, "无障碍未找到: $targetText")
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
            dispatchGestureClick(location.x.toFloat(), location.y.toFloat())
            StepResult(true, step, "视觉点击成功: (${location.x}, ${location.y})",
                Coordinates(location.x.toFloat(), location.y.toFloat()), screenshotPath)
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
        if (!clickResult.success) return StepResult(false, step, "点击输入框失败")

        delay(300)

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
        return withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                AutoTaskAccessibilityService.takeScreenshot()
            } else {
                ScreenshotService.captureScreenshot()
            }
        }
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

            if (focusedNode != null) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                focusedNode.recycle()
                root.recycle()
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
