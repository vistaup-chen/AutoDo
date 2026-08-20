package com.autotask.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/**
 * 模型配置 - 支持 OpenAI 兼容格式，可切换不同后端
 */
data class ModelConfig(
    @SerializedName("provider") val provider: String = "openai_compatible",
    @SerializedName("api_base") val apiBase: String = "",
    @SerializedName("api_key") val apiKey: String = "EMPTY",
    @SerializedName("model_name") val modelName: String = "",
    @SerializedName("max_tokens") val maxTokens: Int = 512,
    @SerializedName("temperature") val temperature: Float = 0.1f
)

/**
 * 模型配置组 - 支持多模型自动切换
 */
data class ModelConfigGroup(
    @SerializedName("name") val name: String = "默认",
    @SerializedName("text_models") val textModels: List<ModelConfig> = listOf(ModelConfig()),
    @SerializedName("vision_models") val visionModels: List<ModelConfig> = listOf(ModelConfig()),
    @SerializedName("current_text_index") val currentTextIndex: Int = 0,
    @SerializedName("current_vision_index") val currentVisionIndex: Int = 0
) {
    fun getCurrentTextModel(): ModelConfig = textModels.getOrNull(currentTextIndex) ?: ModelConfig()
    fun getCurrentVisionModel(): ModelConfig = visionModels.getOrNull(currentVisionIndex) ?: ModelConfig()

    fun switchToNextTextModel(): ModelConfigGroup {
        if (textModels.size <= 1) return this
        val nextIndex = (currentTextIndex + 1) % textModels.size
        return copy(currentTextIndex = nextIndex)
    }

    fun switchToNextVisionModel(): ModelConfigGroup {
        if (visionModels.size <= 1) return this
        val nextIndex = (currentVisionIndex + 1) % visionModels.size
        return copy(currentVisionIndex = nextIndex)
    }
}

/**
 * 全局配置
 */
data class GlobalConfig(
    @SerializedName("model_group") val modelGroup: ModelConfigGroup = ModelConfigGroup(),
    @SerializedName("click_delay_ms") val clickDelayMs: Long = 500,
    @SerializedName("step_timeout_ms") val stepTimeoutMs: Long = 10000,
    @SerializedName("max_retries") val maxRetries: Int = 3,
    @SerializedName("wait_after_launch_ms") val waitAfterLaunchMs: Long = 3000,
    @SerializedName("strategy") val strategy: String = "auto", // auto, accessibility, vision
    @SerializedName("auto_switch_model") val autoSwitchModel: Boolean = true, // 自动切换模型
    @SerializedName("unified_model") val unifiedModel: Boolean = false // 文本/视觉共用同一模型（适合多模态模型）
) {
    // 向后兼容
    val textModel: ModelConfig get() = modelGroup.getCurrentTextModel()
    val visionModel: ModelConfig get() = modelGroup.getCurrentVisionModel()
    /** 实际用于视觉调用的模型：共用模式下走文本模型 */
    val effectiveVisionModel: ModelConfig get() = if (unifiedModel) textModel else visionModel
}

/**
 * 任务步骤类型
 */
enum class StepAction {
    @SerializedName("launch") LAUNCH,
    @SerializedName("click") CLICK,
    @SerializedName("wait") WAIT,
    @SerializedName("input") INPUT,
    @SerializedName("scroll") SCROLL,
    @SerializedName("verify") VERIFY,
    @SerializedName("back") BACK,
    @SerializedName("swipe") SWIPE
}

/**
 * 单个步骤
 */
data class TaskStep(
    @SerializedName("action") val action: StepAction,
    @SerializedName("hint") val hint: String = "",           // 视觉描述，用于识图模型
    @SerializedName("target_text") val targetText: String = "", // 无障碍查找用的文本
    @SerializedName("duration") val duration: Long = 0,       // wait 用
    @SerializedName("input_text") val inputText: String = "", // input 用
    @SerializedName("coordinates") val coordinates: Coordinates? = null, // 手动指定坐标
    @SerializedName("strategy") val strategy: String = "auto", // auto, accessibility, vision
    @SerializedName("optional") val optional: Boolean = false, // 失败不中断
    @SerializedName("repeat") val repeat: String = "once",     // once, until_no_more
    @SerializedName("screenshot") val screenshot: String = ""  // 教学时保存的截图文件名
)

data class Coordinates(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float
)

/**
 * 自动化任务
 */
data class AutomationTask(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("package_name") val packageName: String = "",
    @SerializedName("activity") val activity: String = "",
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("steps") val steps: List<TaskStep> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("last_executed") val lastExecuted: Long = 0,
    @SerializedName("last_result") val lastResult: String = "",
    @SerializedName("success_count") val successCount: Int = 0,
    @SerializedName("fail_count") val failCount: Int = 0
)

/**
 * 执行结果
 */
data class StepResult(
    val success: Boolean,
    val step: TaskStep,
    val message: String = "",
    val coordinates: Coordinates? = null,
    val screenshotPath: String = ""
)

data class TaskResult(
    val task: AutomationTask,
    val success: Boolean,
    val stepResults: List<StepResult> = emptyList(),
    val totalSteps: Int = 0,
    val completedSteps: Int = 0,
    val message: String = ""
)

/**
 * 配置管理器
 */
object ConfigManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun taskToJson(task: AutomationTask): String = gson.toJson(task)
    fun jsonToTask(json: String): AutomationTask = gson.fromJson(json, AutomationTask::class.java)
    fun configToJson(config: GlobalConfig): String = gson.toJson(config)
    fun jsonToConfig(json: String): GlobalConfig = gson.fromJson(json, GlobalConfig::class.java)
    fun tasksToJson(tasks: List<AutomationTask>): String = gson.toJson(tasks)
    fun jsonToTasks(json: String): List<AutomationTask> = gson.fromJson(json, Array<AutomationTask>::class.java).toList()
}
