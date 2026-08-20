package com.autotask.ui

import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotask.config.GlobalConfig
import com.autotask.config.ModelConfig
import com.autotask.config.ModelConfigGroup
import com.autotask.config.TaskRepository
import com.autotask.databinding.ActivitySettingsBinding
import com.autotask.model.ModelClient
import kotlinx.coroutines.launch

/**
 * 预设 API 配置
 */
data class ApiPreset(
    val name: String,
    val baseUrl: String,
    val textModel: String,
    val visionModel: String,
    val requiresKey: Boolean = false
)

/**
 * 设置页面 - 配置模型参数和执行参数
 * 支持预设平台选择和自定义地址
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: TaskRepository

    // 预设配置列表（完整 API 地址，用户无需手动补全）
    private val textPresets = listOf(
        ApiPreset("V1 (本地默认)", "http://127.0.0.1:8080/v1", "Qwen2.5-3B-Instruct", "", false),
        ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-max", "qwen-vl-max", true),
        ApiPreset("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", "gpt-4o", true),
        ApiPreset("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat", "", true),
        ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4", "glm-4v", true),
        ApiPreset("Moonshot", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k", "", true),
        ApiPreset("字节豆包", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "ep-xxxxxxxx", "", true)
    )

    private val visionPresets = listOf(
        ApiPreset("V1 (本地默认)", "http://127.0.0.1:8081/v1", "Qwen2-VL-2B-Instruct", "", false),
        ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "", "qwen-vl-max", true),
        ApiPreset("OpenAI", "https://api.openai.com/v1/chat/completions", "", "gpt-4o", true),
        ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "", "glm-4v", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = TaskRepository(this)

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        // 文本模型测试按钮
        binding.btnTestText.setOnClickListener {
            testTextConnection()
        }

        // 视觉模型测试按钮
        binding.btnTestVision.setOnClickListener {
            testVisionConnection()
        }
    }

    private fun setupModelLists() {
        val config = repository.getGlobalConfig()

        // 文本模型列表
        val textModels = config.modelGroup.textModels.toMutableList()
        if (textModels.isEmpty()) textModels.add(ModelConfig())

        val textAdapter = ModelListAdapter(
            textModels,
            config.modelGroup.currentTextIndex,
            onDelete = { pos ->
                if (textModels.size > 1) {
                    textModels.removeAt(pos)
                    setupModelLists()
                }
            },
            onSelect = { pos ->
                // 加载选中模型的配置到编辑区
                val model = textModels[pos]
                binding.etTextApiBase.setText(model.apiBase)
                binding.etTextModelName.setText(model.modelName)
                binding.etTextApiKey.setText(model.apiKey)
            }
        )
        binding.rvTextModels.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvTextModels.adapter = textAdapter

        binding.btnAddTextModel.setOnClickListener {
            textModels.add(ModelConfig(apiBase = "https://api.openai.com/v1", modelName = "gpt-4o-mini"))
            setupModelLists()
        }

        // 视觉模型列表
        val visionModels = config.modelGroup.visionModels.toMutableList()
        if (visionModels.isEmpty()) visionModels.add(ModelConfig())

        val visionAdapter = ModelListAdapter(
            visionModels,
            config.modelGroup.currentVisionIndex,
            onDelete = { pos ->
                if (visionModels.size > 1) {
                    visionModels.removeAt(pos)
                    setupModelLists()
                }
            },
            onSelect = { pos ->
                val model = visionModels[pos]
                binding.etVisionApiBase.setText(model.apiBase)
                binding.etVisionModelName.setText(model.modelName)
                binding.etVisionApiKey.setText(model.apiKey)
            }
        )
        binding.rvVisionModels.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvVisionModels.adapter = visionAdapter

        binding.btnAddVisionModel.setOnClickListener {
            visionModels.add(ModelConfig(apiBase = "https://api.openai.com/v1", modelName = "gpt-4o"))
            setupModelLists()
        }

        // 加载当前模型配置到编辑区
        loadCurrentModelConfig(config)
    }

    private fun loadCurrentModelConfig(config: GlobalConfig) {
        val textModel = config.modelGroup.getCurrentTextModel()
        binding.etTextApiBase.setText(textModel.apiBase)
        binding.etTextModelName.setText(textModel.modelName)
        binding.etTextApiKey.setText(textModel.apiKey)

        val visionModel = config.modelGroup.getCurrentVisionModel()
        binding.etVisionApiBase.setText(visionModel.apiBase)
        binding.etVisionModelName.setText(visionModel.modelName)
        binding.etVisionApiKey.setText(visionModel.apiKey)
    }

    private fun loadSettings() {
        val config = repository.getGlobalConfig()

        setupModelLists()

        // 执行参数
        binding.etClickDelay.setText(config.clickDelayMs.toString())
        binding.etStepTimeout.setText(config.stepTimeoutMs.toString())
        binding.etMaxRetries.setText(config.maxRetries.toString())
        binding.etLaunchWait.setText(config.waitAfterLaunchMs.toString())

        // 策略
        when (config.strategy) {
            "auto" -> binding.rbAuto.isChecked = true
            "accessibility" -> binding.rbAccessibility.isChecked = true
            "vision" -> binding.rbVision.isChecked = true
        }
    }

    private fun saveSettings() {
        val textConfig = ModelConfig(
            provider = "openai_compatible",
            apiBase = binding.etTextApiBase.text.toString().trim(),
            apiKey = binding.etTextApiKey.text.toString().trim(),
            modelName = binding.etTextModelName.text.toString().trim()
        )

        val visionConfig = ModelConfig(
            provider = "openai_compatible",
            apiBase = binding.etVisionApiBase.text.toString().trim(),
            apiKey = binding.etVisionApiKey.text.toString().trim(),
            modelName = binding.etVisionModelName.text.toString().trim()
        )

        val strategy = when {
            binding.rbAuto.isChecked -> "auto"
            binding.rbAccessibility.isChecked -> "accessibility"
            binding.rbVision.isChecked -> "vision"
            else -> "auto"
        }

        val config = GlobalConfig(
            modelGroup = ModelConfigGroup(
                textModels = listOf(textConfig),
                visionModels = listOf(visionConfig)
            ),
            clickDelayMs = binding.etClickDelay.text.toString().toLongOrNull() ?: 500,
            stepTimeoutMs = binding.etStepTimeout.text.toString().toLongOrNull() ?: 10000,
            maxRetries = binding.etMaxRetries.text.toString().toIntOrNull() ?: 3,
            waitAfterLaunchMs = binding.etLaunchWait.text.toString().toLongOrNull() ?: 3000,
            strategy = strategy
        )

        repository.saveGlobalConfig(config)
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun testTextConnection() {
        val apiBase = binding.etTextApiBase.text.toString().trim()
        val modelName = binding.etTextModelName.text.toString().trim()
        val apiKey = binding.etTextApiKey.text.toString().trim()

        if (apiBase.isEmpty() || modelName.isEmpty()) {
            Toast.makeText(this, "请先填写文本模型的 API 地址和模型名称", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestText.isEnabled = false
        binding.btnTestText.text = "测试中..."

        lifecycleScope.launch {
            try {
                Log.d("SettingsActivity", "开始测试文本模型连接: apiBase=$apiBase, modelName=$modelName")
                val client = ModelClient(ModelConfig(apiBase = apiBase, apiKey = apiKey, modelName = modelName))
                Log.d("SettingsActivity", "ModelClient 创建完成，开始请求...")
                val response = client.askText("你好，请回复'连接成功'")
                Log.d("SettingsActivity", "文本模型响应: $response")
                Toast.makeText(this@SettingsActivity, "文本模型连接成功: $response", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "文本模型连接失败", e)
                Toast.makeText(this@SettingsActivity, "文本模型连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnTestText.isEnabled = true
                binding.btnTestText.text = "测试文本模型连接"
            }
        }
    }

    private fun testVisionConnection() {
        val apiBase = binding.etVisionApiBase.text.toString().trim()
        val modelName = binding.etVisionModelName.text.toString().trim()
        val apiKey = binding.etVisionApiKey.text.toString().trim()

        if (apiBase.isEmpty() || modelName.isEmpty()) {
            Toast.makeText(this, "请先填写视觉模型的 API 地址和模型名称", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestVision.isEnabled = false
        binding.btnTestVision.text = "测试中..."

        lifecycleScope.launch {
            try {
                val client = ModelClient(ModelConfig(apiBase = apiBase, apiKey = apiKey, modelName = modelName))
                // 视觉模型用简单的文本测试（不传图）
                val response = client.askText("你好，请回复'连接成功'")
                Toast.makeText(this@SettingsActivity, "视觉模型连接成功: $response", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "视觉模型连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnTestVision.isEnabled = true
                binding.btnTestVision.text = "测试视觉模型连接"
            }
        }
    }
}
