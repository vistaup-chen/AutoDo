package com.autotask.model

import android.graphics.Bitmap
import android.util.Log
import com.autotask.config.ModelConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型调用客户端 - 统一接口，支持 OpenAI 兼容格式
 * 可切换：本地 MNN 服务、通义千问、OpenAI、任意兼容 API
 */
class ModelClient(private val config: ModelConfig) {

    companion object {
        private const val TAG = "ModelClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // 失败计数，用于判断是否需要切换模型
    private var failureCount = 0
    private val maxFailuresBeforeSwitch = 3

    /**
     * 记录一次失败，返回是否需要切换模型
     */
    fun recordFailure(): Boolean {
        failureCount++
        Log.w(TAG, "模型调用失败 ($failureCount/$maxFailuresBeforeSwitch): ${config.modelName}")
        return failureCount >= maxFailuresBeforeSwitch
    }

    /**
     * 重置失败计数（成功时调用）
     */
    fun recordSuccess() {
        if (failureCount > 0) {
            Log.d(TAG, "模型调用成功，重置失败计数")
            failureCount = 0
        }
    }

    /**
     * 检查是否需要切换模型
     */
    fun shouldSwitchModel(): Boolean {
        return failureCount >= maxFailuresBeforeSwitch
    }

    /**
     * 重置失败计数（切换模型时调用）
     */
    fun resetFailureCount() {
        failureCount = 0
    }

    /**
     * 文本推理
     */
    suspend fun askText(
        prompt: String,
        systemPrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val messages = JsonArray().apply {
                if (systemPrompt.isNotEmpty()) {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                }
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                })
            }

            val body = JsonObject().apply {
                addProperty("model", config.modelName)
                add("messages", messages)
                addProperty("max_tokens", 4096)
                addProperty("temperature", config.temperature)
            }

            val response = callApi("/chat/completions", body.toString())
            val result = parseTextResponse(response)
            recordSuccess()
            result
        } catch (e: Exception) {
            Log.e(TAG, "文本模型调用失败: ${e.message}")
            throw e
        }
    }

    /**
     * 视觉理解 - 传入 Bitmap，返回文本结果
     */
    suspend fun askVision(
        prompt: String,
        bitmap: Bitmap
    ): String = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)

        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt)
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:image/png;base64,$base64Image")
                })
            })
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }

        val body = JsonObject().apply {
            addProperty("model", config.modelName)
            add("messages", messages)
            addProperty("max_tokens", 4096)
            addProperty("temperature", config.temperature)
        }

        val response = callApi("/chat/completions", body.toString())
        parseTextResponse(response)
    }

    /**
     * 视觉定位 - 传入 Bitmap 和描述，返回坐标 JSON
     * 预期返回格式: {"x": 540, "y": 1200, "confidence": 0.95}
     */
    suspend fun locateElement(
        description: String,
        bitmap: Bitmap
    ): ElementLocation = withContext(Dispatchers.IO) {
        // 必须用截图的实际像素尺寸，不能用 Resources.getSystem()：
        // 截图是全屏（含状态栏/导航栏），尺寸与窗口可见区域不一致会导致坐标偏移
        val screenWidth = bitmap.width
        val screenHeight = bitmap.height

        val prompt = """
            分析这张安卓屏幕截图，精确定位"$description"的位置。

            屏幕分辨率: ${screenWidth}x${screenHeight}

            返回JSON: {"x1": 元素左上角x, "y1": 元素左上角y, "x2": 元素右下角x, "y2": 元素右下角y, "confidence": 0.0-1.0}

            规则：
            - 坐标原点在左上角（图片最左上角），x范围0-$screenWidth，y范围0-$screenHeight
            - x1/y1/x2/y2 必须框住整个目标元素的完整矩形（不要只框文字，要框住整个可点击区域）
            - 坐标必须与图片像素一一对应，不要做任何缩放或换算
            - confidence表示确定程度，不确定时降低confidence
            - 找不到返回 {"x1": -1, "y1": -1, "x2": -1, "y2": -1, "confidence": 0}
            - 只返回JSON，不要其他文字
        """.trimIndent()

        val result = askVision(prompt, bitmap)
        Log.d(TAG, "视觉定位原始返回: ${result.take(800)}")
        val loc = parseLocationResponse(result, screenWidth, screenHeight)
        Log.d(TAG, "视觉定位结果: \"$description\" → 中心(${loc.x}, ${loc.y}) 截图=${screenWidth}x${screenHeight}")
        loc
    }

    /**
     * 批量定位 - 找到所有匹配的元素
     */
    suspend fun locateAllElements(
        description: String,
        bitmap: Bitmap
    ): List<ElementLocation> = withContext(Dispatchers.IO) {
        // 同样以截图实际像素尺寸为准
        val screenWidth = bitmap.width
        val screenHeight = bitmap.height

        val prompt = """
            分析这张安卓屏幕截图，找到所有"$description"的位置。

            屏幕分辨率: ${screenWidth}x${screenHeight}

            返回JSON数组: [{"x1": 左上角x, "y1": 左上角y, "x2": 右下角x, "y2": 右下角y, "confidence": 0.0-1.0}, ...]

            规则：
            - 坐标原点在左上角（图片最左上角），坐标必须与图片像素一一对应，不要缩放或换算
            - x1/y1/x2/y2 必须框住每个目标元素的完整矩形
            - 每个元素都要有confidence
            - 找不到返回 []
            - 只返回JSON
        """.trimIndent()

        val result = askVision(prompt, bitmap)
        Log.d(TAG, "批量视觉定位原始返回: ${result.take(800)}")
        val locs = parseLocationArrayResponse(result, screenWidth, screenHeight)
        Log.d(TAG, "批量视觉定位结果: \"$description\" → ${locs.size} 个")
        locs
    }

    /**
     * 验证页面状态 - 问模型当前页面是否符合预期
     */
    suspend fun verifyPage(
        expectedDescription: String,
        bitmap: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        val prompt = """
            分析这张安卓屏幕截图，判断是否符合以下描述。

            预期描述: "$expectedDescription"

            考虑：
            - 页面主要功能区域
            - 关键元素（标题、按钮等）
            - 整体布局

            符合返回: "是" 或 "yes"
            不符合返回: "否" 或 "no"
            不确定返回: "不确定"
        """.trimIndent()

        val result = askVision(prompt, bitmap)
        val trimmed = result.trim().lowercase()
        trimmed.startsWith("是") || trimmed.startsWith("yes") || trimmed == "true"
    }

    /**
     * 文本描述转任务步骤 - 纯文本创建任务
     */
    suspend fun parseTextToSteps(
        description: String,
        packageName: String
    ): List<StepInfo> = withContext(Dispatchers.IO) {
        val systemPrompt = """
            你是一个安卓手机自动化助手。请将用户的自然语言描述转换为结构化的操作步骤。

            ## 支持的步骤类型

            1. **click** - 点击页面上的元素
               - 用途：点击按钮、链接、图标、菜单项等
               - hint 描述：描述元素的视觉特征（位置、颜色、文字、图标形状）
               - 示例：{"action": "click", "hint": "底部导航栏最右侧的个人中心图标"}

            2. **wait** - 等待若干秒
               - 用途：等待页面加载、动画完成、延迟操作
               - duration：等待秒数，默认 3 秒
               - 示例：{"action": "wait", "duration": 5}

            3. **input** - 在输入框中填写文字
               - 用途：输入用户名、密码、验证码、搜索关键词等
               - hint：输入框的描述（如"手机号输入框"）
               - inputText：要输入的内容
               - 示例：{"action": "input", "hint": "顶部搜索框", "inputText": "火车票"}

            4. **scroll** - 滚动页面
               - 用途：向下/向上滚动、滚动到底部/顶部
               - direction：up（向上）或 down（向下），默认 down
               - 示例：{"action": "scroll", "direction": "down"}

            5. **back** - 返回上一页
               - 用途：点击返回按钮、关闭弹窗、返回上级页面
               - 示例：{"action": "back"}

            6. **launch** - 启动应用
               - 用途：打开/启动某个应用（如"打开微信"、"启动支付宝"、"进入抖音"）
               - 注意：launch 步骤不带 hint，直接输出 {"action": "launch"}
               - 示例：{"action": "launch"}

            ## 重要规则
            - 描述中只要包含"打开/启动/进入/点开某个应用"，必须使用 **launch** 步骤，绝对禁止生成"点击APP图标"、"点击应用图标"这类 click 步骤
            - 应用启动只有一次，且必须在步骤列表的最前面

            ## 输出格式要求

            **严格返回 JSON 数组，不要返回其他任何内容（不要解释、不要代码块、不要额外文字）**

            输出格式：
            ```json
            [
              {"action": "click", "hint": "元素描述"},
              {"action": "wait", "duration": 3},
              {"action": "input", "hint": "输入框描述", "inputText": "输入内容"},
              {"action": "scroll", "direction": "down"},
              {"action": "back"}
            ]
            ```

            ## 分解规则

            1. 每个原子操作分为一步（点击、等待、输入、滚动、返回、启动都是独立的一步）
            2. 如果描述中有"打开XX"、"启动XX"、"进入XX应用"，第一步必须是 launch 步骤（不要用 click 代替）
            2. 如果描述中有"等X秒"、"等待X秒"，使用 wait 步骤
            3. 如果描述中有"点击X"、"点X"、"按X"，使用 click 步骤
            4. 如果描述中有"输入X"、"填写X"，使用 input 步骤
            5. 如果描述中有"滚动"、"滑动"、"上滑"、"下滑"，使用 scroll 步骤
            6. 如果描述中有"返回"、"后退"、"关闭"，使用 back 步骤
            7. hint 要具体描述元素的视觉特征，方便后续识别

            ## 示例

            输入："等3秒，点击我的，再点击设置按钮，输入手机号13800138000"
            输出：
            ```json
            [
              {"action": "wait", "duration": 3},
              {"action": "click", "hint": "我的按钮"},
              {"action": "click", "hint": "设置按钮"},
              {"action": "input", "hint": "手机号输入框", "inputText": "13800138000"}
            ]
            ```
        """.trimIndent()

        val prompt = "请分解以下操作流程为执行步骤：\n$description"

        Log.d(TAG, "===== AI 解析开始 =====")
        Log.d(TAG, "描述: $description | 包名: $packageName")
        Log.d(TAG, "使用模型: ${config.modelName} (apiBase=${config.apiBase})")

        val result = askText(prompt, systemPrompt)
        Log.d(TAG, "模型原始返回: ${result.take(1500)}")

        // 清理响应：去掉代码块包裹，只保留 JSON 数组
        val cleaned = cleanJsonResponse(result)
        Log.d(TAG, "清理后的 JSON: ${cleaned.take(1500)}")

        val steps = parseStepsResponse(cleaned)
        Log.d(TAG, "===== AI 解析结果（${steps.size} 步）=====")
        steps.forEachIndexed { index, s ->
            val detail = buildString {
                append("步骤 ${index + 1}: [${s.action}]")
                if (s.hint.isNotEmpty()) append(" hint=\"${s.hint}\"")
                if (s.inputText.isNotEmpty()) append(" input=\"${s.inputText}\"")
                if (s.duration > 0) append(" 等${s.duration}s")
                if (s.scrollDirection != "down") append(" 方向=${s.scrollDirection}")
            }
            Log.d(TAG, detail)
        }
        Log.d(TAG, "===== AI 解析结束 =====")
        steps
    }

    private fun callApi(endpoint: String, body: String): String {
        // 如果 apiBase 已经包含完整路径（如以 /chat/completions 结尾），不再拼接
        val base = config.apiBase.trimEnd('/')
        val url = if (base.endsWith(endpoint)) base else base + endpoint
        val requestBody = body.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply {
                if (config.apiKey.isNotEmpty() && config.apiKey != "EMPTY") {
                    addHeader("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .build()

        // 重试机制：处理 429 频率限制
        var retryCount = 0
        val maxRetries = 3

        while (retryCount <= maxRetries) {
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                return response.body?.string() ?: throw Exception("Empty response")
            }

            // 429 频率限制，等待后重试
            if (response.code == 429 && retryCount < maxRetries) {
                retryCount++
                val waitTime = 2000L * retryCount // 等待 2秒、4秒、6秒
                Log.w(TAG, "429 频率限制，等待 ${waitTime}ms 后重试 ($retryCount/$maxRetries)")
                Thread.sleep(waitTime)
                continue
            }

            // 其他错误或非 429 错误
            throw Exception("API call failed: ${response.code} - ${response.message}")
        }

        throw Exception("API call failed after $maxRetries retries")
    }

    private fun parseTextResponse(response: String): String {
        Log.d(TAG, "parseTextResponse 原始响应: ${response.take(500)}")
        return try {
            val json = gson.fromJson(response, JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            val first = choices.first().asJsonObject
            val finishReason = first.get("finish_reason")?.asString
            Log.d(TAG, "finish_reason: $finishReason")
            val message = first.getAsJsonObject("message")

            // 优先使用 content，如果为空则使用 reasoning_content
            var content = message.get("content")?.asString ?: ""
            if (content.isEmpty()) {
                content = message.get("reasoning_content")?.asString ?: ""
                Log.d(TAG, "content 为空，使用 reasoning_content")
            }
            Log.d(TAG, "提取的content: ${content.take(200)}")
            content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}", e)
            response
        }
    }

    private fun parseLocationResponse(response: String, maxX: Int, maxY: Int): ElementLocation {
        return try {
            val json = extractJsonFromResponse(response)
            val obj = gson.fromJson(json, JsonObject::class.java)
            // 优先解析 bbox（x1,y1,x2,y2）取中心点，兼容旧的 x/y 单点格式
            val x1 = obj.get("x1")?.asInt
            val y1 = obj.get("y1")?.asInt
            val x2 = obj.get("x2")?.asInt
            val y2 = obj.get("y2")?.asInt

            val (x, y) = if (x1 != null && y1 != null && x2 != null && y2 != null) {
                ((x1 + x2) / 2) to ((y1 + y2) / 2)
            } else {
                (obj.get("x")?.asInt ?: -1) to (obj.get("y")?.asInt ?: -1)
            }

            // 验证坐标在截图范围内
            if (x < 0 || y < 0 || x > maxX || y > maxY) {
                Log.w(TAG, "坐标超出范围: ($x, $y), 截图: ${maxX}x${maxY}, 原始: $json")
                ElementLocation(-1, -1)
            } else {
                ElementLocation(x, y)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse location: ${e.message}")
            ElementLocation(-1, -1)
        }
    }

    private fun parseLocationArrayResponse(response: String, maxX: Int, maxY: Int): List<ElementLocation> {
        return try {
            val json = extractJsonFromResponse(response)
            val array = gson.fromJson(json, JsonArray::class.java)
            array.map { elem ->
                val obj = elem.asJsonObject
                // bbox 优先，兼容单点
                val x1 = obj.get("x1")?.asInt
                val y1 = obj.get("y1")?.asInt
                val x2 = obj.get("x2")?.asInt
                val y2 = obj.get("y2")?.asInt
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    ElementLocation((x1 + x2) / 2, (y1 + y2) / 2)
                } else {
                    ElementLocation(
                        obj.get("x")?.asInt ?: -1,
                        obj.get("y")?.asInt ?: -1
                    )
                }
            }.filter { it.x >= 0 && it.y >= 0 && it.x <= maxX && it.y <= maxY }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse location array: ${e.message}")
            emptyList()
        }
    }

    /**
     * 清理 AI 响应，提取 JSON 数组
     */
    private fun cleanJsonResponse(response: String): String {
        var content = response.trim()

        // 去掉 ```json ... ``` 或 ``` ... ``` 代码块
        if (content.contains("```")) {
            val codeBlockRegex = "```(?:json)?\\s*([\\s\\S]*?)\\s*```".toRegex()
            val match = codeBlockRegex.find(content)
            if (match != null) {
                content = match.groupValues[1].trim()
            }
        }

        // 提取 [ ... ] 部分
        val startIdx = content.indexOf('[')
        val endIdx = content.lastIndexOf(']')
        if (startIdx >= 0 && endIdx > startIdx) {
            content = content.substring(startIdx, endIdx + 1)
        }

        Log.d(TAG, "清理后的响应: ${content.take(200)}")
        return content
    }

    private fun parseStepsResponse(response: String): List<StepInfo> {
        Log.d(TAG, "parseStepsResponse 原始输入: $response")
        return try {
            var content = response.trim()
            Log.d(TAG, "去掉空白后: $content")

            // 去掉可能的代码块包裹 ```json ... ```
            if (content.contains("```")) {
                val codeBlockRegex = "```(?:json)?\\s*([\\s\\S]*?)\\s*```".toRegex()
                val match = codeBlockRegex.find(content)
                if (match != null) {
                    content = match.groupValues[1].trim()
                    Log.d(TAG, "去掉代码块后: $content")
                } else {
                    Log.d(TAG, "检测到```但未匹配到代码块")
                }
            }

            // 提取 JSON 数组
            val json = extractJsonFromResponse(content)
            Log.d(TAG, "提取的JSON: $json")

            val array = gson.fromJson(json, JsonArray::class.java)
            Log.d(TAG, "解析出 ${array.size()} 个步骤")

            val steps = array.map { elem ->
                val obj = elem.asJsonObject
                val step = StepInfo(
                    action = obj.get("action")?.asString ?: "click",
                    hint = obj.get("hint")?.asString ?: "",
                    duration = obj.get("duration")?.asLong ?: 3,
                    inputText = obj.get("input_text")?.asString ?: obj.get("hint")?.asString ?: "",
                    scrollDirection = obj.get("scroll_direction")?.asString ?: "down"
                )
                Log.d(TAG, "步骤: action=${step.action}, hint=${step.hint}, duration=${step.duration}")
                step
            }
            steps
        } catch (e: Exception) {
            Log.e(TAG, "解析步骤失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 从响应中提取 JSON（处理模型可能返回的额外文字）
     */
    private fun extractJsonFromResponse(response: String): String {
        val trimmed = response.trim()
        return try {
            gson.fromJson(trimmed, JsonObject::class.java)
            trimmed
        } catch (e: Exception) {
            try {
                gson.fromJson(trimmed, JsonArray::class.java)
                trimmed
            } catch (e2: Exception) {
                val codeBlockRegex = "```(?:json)?\\s*([\\s\\S]*?)\\s*```".toRegex()
                val match = codeBlockRegex.find(trimmed)
                if (match != null) {
                    match.groupValues[1].trim()
                } else {
                    val jsonStart = trimmed.indexOfFirst { it == '{' || it == '[' }
                    val jsonEnd = trimmed.indexOfLast { it == '}' || it == ']' }
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        trimmed.substring(jsonStart, jsonEnd + 1)
                    } else {
                        throw Exception("No JSON found in response")
                    }
                }
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}

/**
 * 元素坐标
 */
data class ElementLocation(val x: Int, val y: Int) {
    fun isValid(): Boolean = x >= 0 && y >= 0
}

/**
 * 步骤信息 - 文本解析结果
 */
data class StepInfo(
    val action: String,
    val hint: String = "",
    val duration: Long = 0,
    val inputText: String = "",
    val scrollDirection: String = "down"
)
