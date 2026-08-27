package com.autotask.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autotask.config.GlobalConfig
import com.autotask.config.ModelConfig
import com.autotask.config.ModelConfigGroup
import com.autotask.config.TaskRepository
import com.autotask.databinding.ActivitySettingsBinding
import com.autotask.model.ModelClient
import com.autotask.ui.ThemeManager
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

    companion object {
        private const val TAG = "AT-Settings"
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: TaskRepository

    // 模型列表（类级别变量，避免每次 setupModelLists 重新加载）
    private var textModels = mutableListOf<ModelConfig>()
    private var visionModels = mutableListOf<ModelConfig>()

    // 预设配置列表（完整 API 地址，用户无需手动补全）
    private val textPresets = listOf(
        ApiPreset("V1 (本地默认)", "http://127.0.0.1:8080/v1", "Qwen2.5-3B-Instruct", "", false),
        ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-max", "qwen-vl-max", true),
        ApiPreset("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini", "gpt-4o", true),
        ApiPreset("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat", "", true),
        ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.7-flash", "GLM-4.1V-Thinking-Flash", true),
        ApiPreset("Moonshot", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k", "", true),
        ApiPreset("字节豆包", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "ep-xxxxxxxx", "", true),
        ApiPreset("SenseNova", "https://token.sensenova.cn/v1/chat/completions", "sensenova-6.8-flash-lite", "sensenova-6.8-flash-lite", true)
    )

    private val visionPresets = listOf(
        ApiPreset("V1 (本地默认)", "http://127.0.0.1:8081/v1", "Qwen2-VL-2B-Instruct", "", false),
        ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "", "qwen-vl-max", true),
        ApiPreset("OpenAI", "https://api.openai.com/v1/chat/completions", "", "gpt-4o", true),
        ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "", "GLM-4.1V-Thinking-Flash", true),
        ApiPreset("SenseNova", "https://token.sensenova.cn/v1/chat/completions", "", "sensenova-6.8-flash-lite", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
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

        // 文本模型测试按钮
        binding.btnTestText.setOnClickListener {
            testTextConnection()
        }

        // 视觉模型测试按钮
        binding.btnTestVision.setOnClickListener {
            testVisionConnection()
        }

        // 统一模型（文本/视觉共用）开关：开启后隐藏视觉模型卡片，并立即保存
        binding.switchUnifiedModel.setOnCheckedChangeListener { _, isChecked ->
            updateVisionCardVisibility(isChecked)
            onSettingsChanged()
        }

        // 所有输入/选择改动立即保存（去掉"保存设置"按钮）
        setupAutoSaveListeners()

        // 主题选择器
        setupThemeSelector()

        // API 地址下拉预设
        setupApiDropdowns()
    }

    /**
     * 为所有设置控件绑定"改动即保存"监听
     */
    private fun setupAutoSaveListeners() {
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onSettingsChanged()
            }
        }

        // 模型表单
        binding.etTextApiBase.addTextChangedListener(textWatcher)
        binding.etTextModelName.addTextChangedListener(textWatcher)
        binding.etTextApiKey.addTextChangedListener(textWatcher)
        binding.etVisionApiBase.addTextChangedListener(textWatcher)
        binding.etVisionModelName.addTextChangedListener(textWatcher)
        binding.etVisionApiKey.addTextChangedListener(textWatcher)
        // 执行参数
        binding.etClickDelay.addTextChangedListener(textWatcher)
        binding.etStepTimeout.addTextChangedListener(textWatcher)
        binding.etMaxRetries.addTextChangedListener(textWatcher)
        binding.etLaunchWait.addTextChangedListener(textWatcher)

        // 策略单选
        binding.rbAuto.setOnCheckedChangeListener { _, isChecked -> if (isChecked) onSettingsChanged() }
        binding.rbAccessibility.setOnCheckedChangeListener { _, isChecked -> if (isChecked) onSettingsChanged() }
        binding.rbVision.setOnCheckedChangeListener { _, isChecked -> if (isChecked) onSettingsChanged() }
    }

    /**
     * 设置 API 地址下拉预设
     */
    private fun setupApiDropdowns() {
        // 文本模型 API 下拉
        binding.tilTextApiBase.setEndIconOnClickListener {
            showPresetMenu(textPresets) { preset ->
                binding.etTextApiBase.setText(preset.baseUrl)
                binding.etTextModelName.setText(preset.textModel)
                if (preset.requiresKey && binding.etTextApiKey.text.isNullOrEmpty()) {
                    binding.etTextApiKey.requestFocus()
                }
            }
        }

        // 视觉模型 API 下拉
        binding.tilVisionApiBase.setEndIconOnClickListener {
            showPresetMenu(visionPresets) { preset ->
                binding.etVisionApiBase.setText(preset.baseUrl)
                binding.etVisionModelName.setText(preset.visionModel)
                if (preset.requiresKey && binding.etVisionApiKey.text.isNullOrEmpty()) {
                    binding.etVisionApiKey.requestFocus()
                }
            }
        }
    }

    /**
     * 显示预设选择菜单
     */
    private fun showPresetMenu(presets: List<ApiPreset>, onSelect: (ApiPreset) -> Unit) {
        val popup = android.widget.PopupMenu(this, binding.tilTextApiBase)
        presets.forEachIndexed { index, preset ->
            popup.menu.add(0, index, index, preset.name)
        }
        popup.setOnMenuItemClickListener { item ->
            val preset = presets[item.itemId]
            onSelect(preset)
            true
        }
        popup.show()
    }

    /**
     * 设置主题选择器
     */
    private fun setupThemeSelector() {
        val currentTheme = ThemeManager.getCurrentTheme(this)
        val container = binding.themeSelector
        container.removeAllViews()

        val dp = resources.displayMetrics.density

        for (theme in ThemeManager.Theme.entries) {
            val itemView = layoutInflater.inflate(
                com.autotask.R.layout.item_theme_option, container, false
            )

            val colorCircle = itemView.findViewById<android.view.View>(com.autotask.R.id.theme_color_circle)
            val label = itemView.findViewById<android.widget.TextView>(com.autotask.R.id.theme_label)
            val checkMark = itemView.findViewById<android.view.View>(com.autotask.R.id.theme_check_mark)

            // 设置颜色
            colorCircle.background.setTint(android.graphics.Color.parseColor(theme.primaryColor))

            // 设置标签
            label.text = "${theme.icon} ${theme.label}"

            // 选中状态
            val isSelected = theme == currentTheme
            checkMark.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.GONE
            itemView.alpha = if (isSelected) 1.0f else 0.6f

            // 点击切换主题
            itemView.setOnClickListener {
                ThemeManager.setTheme(this, theme)
                // 重建 Activity 以应用新主题
                recreate()
            }

            val params = android.widget.LinearLayout.LayoutParams(
                (72 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = (8 * dp).toInt()
            itemView.layoutParams = params

            container.addView(itemView)
        }
    }

    private var textAdapter: ModelListAdapter? = null
    private var visionAdapter: ModelListAdapter? = null
    private var currentTextIndex = 0
    private var currentVisionIndex = 0

    private fun setupModelLists() {
        val config = repository.getGlobalConfig()

        // 文本模型列表 - 只在首次加载时从 config 初始化
        if (textModels.isEmpty()) {
            textModels = config.modelGroup.textModels.toMutableList()
            if (textModels.isEmpty()) textModels.add(ModelConfig())
            currentTextIndex = config.modelGroup.currentTextIndex.coerceIn(0, textModels.size - 1)
            // 进入页面自动清理历史重复项（名称+地址+密钥全等）
            dedupeTextModels()
        }

        // 只在首次创建 adapter
        if (textAdapter == null) {
            textAdapter = ModelListAdapter(
                textModels,
                { currentTextIndex },
                onDelete = { pos ->
                    android.util.Log.d(TAG, ">>> 触发删除: pos=$pos 模型=${textModels.getOrNull(pos)?.modelName} 当前数量=${textModels.size}")
                    if (textModels.size > 1) {
                        // 删除前确认，防止误删（点击行只会切换选中，删除按钮才到这里）
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ 删除模型")
                            .setMessage("确定删除「${textModels[pos].modelName.ifEmpty { "未命名" }}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                android.util.Log.d(TAG, ">>> 确认删除: pos=$pos 模型=${textModels.getOrNull(pos)?.modelName} 删除前列表=${textModels.map { it.modelName }} size=${textModels.size}")
                                textModels.removeAt(pos)
                                if (currentTextIndex >= textModels.size) {
                                    currentTextIndex = textModels.size - 1
                                }
                                textAdapter?.notifyDataSetChanged()
                                loadModelToEditArea()
                                persistTextModels()
                                binding.rvTextModels.post { binding.rvTextModels.requestLayout() }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        Toast.makeText(this, "至少保留一个模型", Toast.LENGTH_SHORT).show()
                    }
                },
                onSelect = { pos ->
                    android.util.Log.d(TAG, ">>> 切换当前: pos=$pos 模型=${textModels.getOrNull(pos)?.modelName} 列表=${textModels.map { it.modelName }} size=${textModels.size}")
                    currentTextIndex = pos
                    // 只切换"当前"标记，表单保持不动（表单是独立编辑区，与列表解耦）
                    textAdapter?.notifyDataSetChanged()
                    persistTextModels()
                    // 强制重测：ScrollView 嵌套 RecyclerView 时切换后可能不重新测量高度，导致部分 item 显示不出来
                    binding.rvTextModels.post { binding.rvTextModels.requestLayout() }
                },
                onMove = { from, to ->
                    // 拖拽排序后，当前选中项跟随移动
                    val idx = currentTextIndex
                    currentTextIndex = when {
                        idx == from -> to
                        from < to && idx in (from + 1)..to -> idx - 1
                        from > to && idx in to until from -> idx + 1
                        else -> idx
                    }
                    textAdapter?.notifyDataSetChanged()
                    persistTextModels()
                }
            )
            binding.rvTextModels.layoutManager = WrapContentLinearLayoutManager(this)
            binding.rvTextModels.adapter = textAdapter
            textAdapter!!.createItemTouchHelper().attachToRecyclerView(binding.rvTextModels)

            binding.btnAddTextModel.setOnClickListener {
                // 直接用当前表单内容创建备用模型（无弹窗）。
                // 规则：完全一致（名称+地址+密钥都相同）才提示已存在；
                // 名称相同但密钥不同 → 允许添加（同一个免费模型可以配多个 key 轮流用）
                val name = binding.etTextModelName.text.toString().trim()
                    .ifEmpty { "text-backup-${textModels.size + 1}" }
                val base = binding.etTextApiBase.text.toString().trim().ifEmpty { "https://api.openai.com/v1" }
                val key = binding.etTextApiKey.text.toString().trim()

                // 完全一致检测（不区分 provider，按三个关键字段）
                val duplicate = textModels.any {
                    it.modelName == name && it.apiBase == base && it.apiKey == key
                }
                if (duplicate) {
                    Toast.makeText(this, "模型已存在：$name（名称/地址/密钥完全相同）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val newModel = ModelConfig(
                    provider = "openai_compatible",
                    apiBase = base,
                    apiKey = key,
                    modelName = name
                )
                textModels.add(newModel)
                currentTextIndex = textModels.size - 1
                textAdapter?.notifyDataSetChanged()
                loadModelToEditArea()
                persistTextModels()
                binding.rvTextModels.post { binding.rvTextModels.requestLayout() }
                android.util.Log.d(TAG, ">>> 添加备用文本模型: $name 添加后列表=${textModels.map { it.modelName }} size=${textModels.size}")
                Toast.makeText(this, "已添加备用文本模型：$name", Toast.LENGTH_SHORT).show()
            }
        }

        // 视觉模型列表 - 只在首次加载时从 config 初始化
        if (visionModels.isEmpty()) {
            visionModels = config.modelGroup.visionModels.toMutableList()
            if (visionModels.isEmpty()) visionModels.add(ModelConfig())
            currentVisionIndex = config.modelGroup.currentVisionIndex.coerceIn(0, visionModels.size - 1)
            // 进入页面自动清理历史重复项
            dedupeVisionModels()
        }

        // 只在首次创建 adapter
        if (visionAdapter == null) {
            visionAdapter = ModelListAdapter(
                visionModels,
                { currentVisionIndex },
                onDelete = { pos ->
                    android.util.Log.d(TAG, ">>> [视觉] 触发删除: pos=$pos 模型=${visionModels.getOrNull(pos)?.modelName} 当前数量=${visionModels.size}")
                    if (visionModels.size > 1) {
                        // 删除前确认，防止误删
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ 删除模型")
                            .setMessage("确定删除「${visionModels[pos].modelName.ifEmpty { "未命名" }}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                android.util.Log.d(TAG, ">>> [视觉] 确认删除: pos=$pos 模型=${visionModels.getOrNull(pos)?.modelName} 删除前列表=${visionModels.map { it.modelName }} size=${visionModels.size}")
                                visionModels.removeAt(pos)
                                if (currentVisionIndex >= visionModels.size) {
                                    currentVisionIndex = visionModels.size - 1
                                }
                                visionAdapter?.notifyDataSetChanged()
                                loadModelToEditArea()
                                persistVisionModels()
                                binding.rvVisionModels.post { binding.rvVisionModels.requestLayout() }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    } else {
                        Toast.makeText(this, "至少保留一个模型", Toast.LENGTH_SHORT).show()
                    }
                },
                onSelect = { pos ->
                    android.util.Log.d(TAG, ">>> [视觉] 切换当前: pos=$pos 模型=${visionModels.getOrNull(pos)?.modelName} 列表=${visionModels.map { it.modelName }} size=${visionModels.size}")
                    currentVisionIndex = pos
                    // 只切换"当前"标记，表单保持不动（与列表解耦）
                    visionAdapter?.notifyDataSetChanged()
                    persistVisionModels()
                    binding.rvVisionModels.post { binding.rvVisionModels.requestLayout() }
                },
                onMove = { from, to ->
                    // 拖拽排序后，当前选中项跟随移动
                    val idx = currentVisionIndex
                    currentVisionIndex = when {
                        idx == from -> to
                        from < to && idx in (from + 1)..to -> idx - 1
                        from > to && idx in to until from -> idx + 1
                        else -> idx
                    }
                    visionAdapter?.notifyDataSetChanged()
                    persistVisionModels()
                }
            )
            binding.rvVisionModels.layoutManager = WrapContentLinearLayoutManager(this)
            binding.rvVisionModels.adapter = visionAdapter
            visionAdapter!!.createItemTouchHelper().attachToRecyclerView(binding.rvVisionModels)

            binding.btnAddVisionModel.setOnClickListener {
                // 直接用当前表单内容创建备用视觉模型（无弹窗）。
                // 规则：完全一致（名称+地址+密钥都相同）才提示已存在；密钥不同允许添加
                val name = binding.etVisionModelName.text.toString().trim()
                    .ifEmpty { "vision-backup-${visionModels.size + 1}" }
                val base = binding.etVisionApiBase.text.toString().trim().ifEmpty { "https://api.openai.com/v1" }
                val key = binding.etVisionApiKey.text.toString().trim()

                val duplicate = visionModels.any {
                    it.modelName == name && it.apiBase == base && it.apiKey == key
                }
                if (duplicate) {
                    Toast.makeText(this, "模型已存在：$name（名称/地址/密钥完全相同）", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val newModel = ModelConfig(
                    provider = "openai_compatible",
                    apiBase = base,
                    apiKey = key,
                    modelName = name
                )
                visionModels.add(newModel)
                currentVisionIndex = visionModels.size - 1
                visionAdapter?.notifyDataSetChanged()
                loadModelToEditArea()
                persistVisionModels()
                binding.rvVisionModels.post { binding.rvVisionModels.requestLayout() }
                android.util.Log.d(TAG, ">>> [视觉] 添加备用模型: $name 添加后列表=${visionModels.map { it.modelName }} size=${visionModels.size}")
                Toast.makeText(this, "已添加备用视觉模型：$name", Toast.LENGTH_SHORT).show()
            }
        }

        // 加载当前模型配置到编辑区
        loadCurrentModelConfig(config)
    }

    private fun loadModelToEditArea() {
        // 填充期间屏蔽"改完即存"：setText 会逐个触发 TextWatcher，
        // 若不禁用会把表单"半填状态"（新地址+旧名字）写回当前模型并落盘，产生中间态污染
        isApplyingSettings = true
        if (currentTextIndex in textModels.indices) {
            val model = textModels[currentTextIndex]
            binding.etTextApiBase.setText(model.apiBase)
            binding.etTextModelName.setText(model.modelName)
            binding.etTextApiKey.setText(model.apiKey)
        }
        if (currentVisionIndex in visionModels.indices) {
            val model = visionModels[currentVisionIndex]
            binding.etVisionApiBase.setText(model.apiBase)
            binding.etVisionModelName.setText(model.modelName)
            binding.etVisionApiKey.setText(model.apiKey)
        }
        isApplyingSettings = false
    }

    private fun loadCurrentModelConfig(config: GlobalConfig) {
        // 用内存列表的当前选中项填充表单（与列表"当前"标记严格一致）：
        // 不能用 config.getCurrentTextModel()——磁盘 currentTextIndex 可能越界或与列表不同步，
        // 那会让表单显示与列表选中不一致，用户编辑表单时会覆盖错误的模型
        val textModel = if (currentTextIndex in textModels.indices) {
            textModels[currentTextIndex]
        } else {
            config.modelGroup.getCurrentTextModel()
        }
        binding.etTextApiBase.setText(textModel.apiBase)
        binding.etTextModelName.setText(textModel.modelName)
        binding.etTextApiKey.setText(textModel.apiKey)

        val visionModel = if (currentVisionIndex in visionModels.indices) {
            visionModels[currentVisionIndex]
        } else {
            config.modelGroup.getCurrentVisionModel()
        }
        binding.etVisionApiBase.setText(visionModel.apiBase)
        binding.etVisionModelName.setText(visionModel.modelName)
        binding.etVisionApiKey.setText(visionModel.apiKey)
    }

    /**
     * 模型列表变更后立即持久化（添加/删除/排序/切换），
     * 避免用户添加模型后未点"保存"就退出导致模型丢失
     */
    private fun persistTextModels() {
        android.util.Log.d(TAG, ">>> 保存文本模型: 列表=${textModels.map { "${it.modelName}(key=${it.apiKey.take(6)})" }} size=${textModels.size} current=$currentTextIndex")
        val config = repository.getGlobalConfig()
        repository.saveGlobalConfig(
            config.copy(
                modelGroup = config.modelGroup.copy(
                    textModels = textModels.toList(),
                    currentTextIndex = currentTextIndex
                )
            )
        )
    }

    private fun persistVisionModels() {
        android.util.Log.d(TAG, ">>> [视觉] 保存模型: 列表=${visionModels.map { "${it.modelName}(key=${it.apiKey.take(6)})" }} size=${visionModels.size} current=$currentVisionIndex")
        val config = repository.getGlobalConfig()
        repository.saveGlobalConfig(
            config.copy(
                modelGroup = config.modelGroup.copy(
                    visionModels = visionModels.toList(),
                    currentVisionIndex = currentVisionIndex
                )
            )
        )
    }

    /**
     * 进入设置页时规范化文本模型列表：按(名称,地址,密钥)全等去重 + 修正 currentIndex + 持久化。
     * 防止历史 bug 产生的重复项堆积（如某模型被表单覆盖成另一个后出现多个相同模型）。
     */
    private fun dedupeTextModels() {
        val before = textModels.size
        val seen = mutableSetOf<String>()
        val deduped = textModels.filter {
            val key = "${it.modelName}|${it.apiBase}|${it.apiKey}"
            seen.add(key)
        }
        if (deduped.size == before) return
        textModels = deduped.toMutableList()
        if (textModels.isEmpty()) textModels.add(ModelConfig())
        currentTextIndex = currentTextIndex.coerceIn(0, textModels.size - 1)
        textAdapter?.notifyDataSetChanged()
        persistTextModels()
        android.util.Log.d(TAG, ">>> 文本模型去重: $before 个 → ${textModels.size} 个")
    }

    /**
     * 视觉模型列表去重（同上）
     */
    private fun dedupeVisionModels() {
        val before = visionModels.size
        val seen = mutableSetOf<String>()
        val deduped = visionModels.filter {
            val key = "${it.modelName}|${it.apiBase}|${it.apiKey}"
            seen.add(key)
        }
        if (deduped.size == before) return
        visionModels = deduped.toMutableList()
        if (visionModels.isEmpty()) visionModels.add(ModelConfig())
        currentVisionIndex = currentVisionIndex.coerceIn(0, visionModels.size - 1)
        visionAdapter?.notifyDataSetChanged()
        persistVisionModels()
        android.util.Log.d(TAG, ">>> [视觉] 模型去重: $before 个 → ${visionModels.size} 个")
    }

    // 加载设置时的标志：填充控件期间不触发"改动即存"
    private var isApplyingSettings = false

    private fun loadSettings() {
        val config = repository.getGlobalConfig()

        // 填充控件期间不触发"改动即存"——必须在 setupModelLists() 之前设置：
        // setupModelLists 内部会 setText 填充表单，而 EditText 有状态恢复（上次会话残留值），
        // 若不提前屏蔽，加载时会触发 TextWatcher 把残留值写回模型列表并落盘（数据污染的根源）
        isApplyingSettings = true

        setupModelLists()

        // 填充控件期间不触发自动保存
        isApplyingSettings = true

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

        // 统一模型开关
        binding.switchUnifiedModel.isChecked = config.unifiedModel
        updateVisionCardVisibility(config.unifiedModel)

        isApplyingSettings = false
    }

    private fun updateVisionCardVisibility(unified: Boolean) {
        binding.cardVisionModel.visibility = if (unified) View.GONE else View.VISIBLE
    }

    /**
     * 任何设置项改动后立即保存（去掉"保存设置"按钮，改完即生效）
     */
    private fun onSettingsChanged() {
        if (isApplyingSettings) return

        // 模型表单是独立编辑区，不再自动写回列表（点列表切换"当前"、点添加提交为新模型），
        // 这里只保存执行参数/策略/开关等全局设置
        android.util.Log.d(TAG, "onSettingsChanged: 保存全局设置 文本列表=${textModels.map { it.modelName }} size=${textModels.size} current=$currentTextIndex")

        val strategy = when {
            binding.rbAuto.isChecked -> "auto"
            binding.rbAccessibility.isChecked -> "accessibility"
            binding.rbVision.isChecked -> "vision"
            else -> "auto"
        }

        val newConfig = GlobalConfig(
            modelGroup = ModelConfigGroup(
                textModels = textModels.toList(),
                visionModels = visionModels.toList(),
                currentTextIndex = currentTextIndex,
                currentVisionIndex = currentVisionIndex
            ),
            clickDelayMs = binding.etClickDelay.text.toString().toLongOrNull() ?: 500,
            stepTimeoutMs = binding.etStepTimeout.text.toString().toLongOrNull() ?: 10000,
            maxRetries = binding.etMaxRetries.text.toString().toIntOrNull() ?: 3,
            waitAfterLaunchMs = binding.etLaunchWait.text.toString().toLongOrNull() ?: 3000,
            strategy = strategy,
            unifiedModel = binding.switchUnifiedModel.isChecked
        )

        repository.saveGlobalConfig(newConfig)
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
        val unified = binding.switchUnifiedModel.isChecked
        val apiBase = (if (unified) binding.etTextApiBase else binding.etVisionApiBase).text.toString().trim()
        val modelName = (if (unified) binding.etTextModelName else binding.etVisionModelName).text.toString().trim()
        val apiKey = (if (unified) binding.etTextApiKey else binding.etVisionApiKey).text.toString().trim()

        if (apiBase.isEmpty() || modelName.isEmpty()) {
            Toast.makeText(this, if (unified) "共用模式下请填写文本模型的 API 地址和模型名称" else "请先填写视觉模型的 API 地址和模型名称", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestVision.isEnabled = false
        binding.btnTestVision.text = "测试中..."

        lifecycleScope.launch {
            try {
                val client = ModelClient(ModelConfig(apiBase = apiBase, apiKey = apiKey, modelName = modelName))
                // 视觉模型用简单的文本测试（不传图）
                val response = client.askText("你好，请回复'连接成功'")
                Toast.makeText(this@SettingsActivity, (if (unified) "共用模型连接成功: " else "视觉模型连接成功: ") + response, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, (if (unified) "共用模型连接失败: " else "视觉模型连接失败: ") + (e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                binding.btnTestVision.isEnabled = true
                binding.btnTestVision.text = "测试视觉模型连接"
            }
        }
    }
}
