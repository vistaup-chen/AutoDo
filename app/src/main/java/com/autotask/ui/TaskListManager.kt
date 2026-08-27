package com.autotask.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.autotask.R
import com.autotask.config.AutomationTask
import com.autotask.config.StepAction
import com.autotask.config.TaskRepository
import com.autotask.databinding.DialogAddTaskBinding
import com.autotask.model.ModelClient
import com.autotask.model.StepInfo
import com.autotask.service.AutoTaskAccessibilityService
import com.autotask.service.FloatingWindowService
import com.autotask.service.TeachCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 任务列表管理器 - 处理添加任务的各种模式
 */
class TaskListManager(
    private val repository: TaskRepository
) {
    companion object {
        private const val TAG = "TaskListManager"
    }

    // 当前上下文（用于对话框）
    var currentContext: Context? = null

    // 当前选中的应用
    private var selectedApp: AppChooserDialog.AppItem? = null

    /**
     * 显示编辑任务对话框
     */
    fun showEditTaskDialog(context: Context, task: AutomationTask, onTaskUpdated: (AutomationTask) -> Unit) {
        currentContext = context // 保存上下文
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_task, null)
        val etTaskName = dialogView.findViewById<android.widget.EditText>(R.id.et_task_name)
        val etPackageName = dialogView.findViewById<android.widget.EditText>(R.id.et_package_name)
        val etDescription = dialogView.findViewById<android.widget.EditText>(R.id.et_description)
        val btnAiParse = dialogView.findViewById<android.widget.Button>(R.id.btn_ai_parse)
        val layoutLoading = dialogView.findViewById<android.view.View>(R.id.layout_loading)
        val tvLoadingText = dialogView.findViewById<android.widget.TextView>(R.id.tv_loading_text)
        val layoutStepPreview = dialogView.findViewById<android.view.View>(R.id.layout_step_preview)
        val rvStepsReadonly = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_steps_readonly)
        val btnEditActions = dialogView.findViewById<android.widget.Button>(R.id.btn_edit_actions)

        etTaskName.setText(task.name)
        etPackageName.setText(task.packageName)

        // 将步骤反向解析为描述文本
        val description = stepsToDescription(task.steps)
        etDescription.setText(description)

        // 加载现有步骤到只读列表
        Log.d(TAG, "加载现有步骤: ${task.steps.size} 步")
        if (task.steps.isNotEmpty()) {
            loadStepsToReadonlyList(task, rvStepsReadonly, btnEditActions)
        } else {
            // 没有步骤时隐藏列表区域，但显示编辑动作按钮
            btnEditActions.visibility = android.view.View.VISIBLE
        }

        // AI 解析按钮
        btnAiParse.setOnClickListener {
            val inputDesc = etDescription.text.toString().trim()
            if (inputDesc.isEmpty()) {
                Toast.makeText(context, "请先输入描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 显示加载状态
            layoutLoading.visibility = android.view.View.VISIBLE
            tvLoadingText.text = "正在解析..."
            layoutStepPreview.visibility = android.view.View.GONE
            btnEditActions.visibility = android.view.View.GONE
            btnAiParse.isEnabled = false

            val scope = CoroutineScope(Dispatchers.Main)
            scope.launch {
                try {
                    tvLoadingText.text = "正在调用 AI 接口..."
                    val config = repository.getGlobalConfig()
                    tvLoadingText.text = "正在解析步骤..."
                    val textClient = ModelClient(config.textModel)
                    val steps = textClient.parseTextToSteps(inputDesc, etPackageName.text.toString().trim())

                    layoutLoading.visibility = android.view.View.GONE
                    btnAiParse.isEnabled = true

                    if (steps.isEmpty()) {
                        Toast.makeText(context, "解析失败，请尝试更详细的描述", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    currentParsedSteps = steps
                    Log.d(TAG, "解析完成，显示只读步骤列表: ${steps.size} 步")
                    showReadonlyStepList(steps, rvStepsReadonly, btnEditActions)
                    Toast.makeText(context, "解析完成，共 ${steps.size} 步", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    layoutLoading.visibility = android.view.View.GONE
                    btnAiParse.isEnabled = true
                    Log.e(TAG, "解析失败: ${e.message}")
                    Toast.makeText(context, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("编辑任务")
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        // 编辑动作按钮 - 在 setOnShowListener 外面也设置一次
        btnEditActions.setOnClickListener {
            Log.d(TAG, "编辑动作按钮被点击")
            val ctx = currentContext // 提前保存上下文
            dialog.dismiss()
            Log.d(TAG, "准备调用 showStepEditorDialog, ctx=$ctx")
            // 使用保存的上下文
            currentContext = ctx
            showStepEditorDialog(task) { updatedTask ->
                Log.d(TAG, "showStepEditorDialog 保存回调")
                onTaskUpdated(updatedTask)
            }
        }

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val name = etTaskName.text.toString().trim()
                val packageName = etPackageName.text.toString().trim()
                val newDescription = etDescription.text.toString().trim()

                if (name.isEmpty() || packageName.isEmpty() || newDescription.isEmpty()) {
                    Toast.makeText(context, "请填写完整信息", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val steps = normalizeSteps(parseDescriptionManually(newDescription, packageName))
                val updatedTask = task.copy(name = name, packageName = packageName, steps = steps)
                onTaskUpdated(updatedTask)
                dialog.dismiss()
            }
        }


        dialog.show()
    }

    /**
     * 规范化步骤列表（所有创建/编辑任务入口统一调用）：
     * 1. 把"点击APP图标/打开XX应用"这类启动应用步骤转为 LAUNCH（执行时引擎会拉起应用）
     * 2. 开头多余的启动类步骤删除（只保留一个）
     */
    private fun normalizeSteps(steps: List<com.autotask.config.TaskStep>): List<com.autotask.config.TaskStep> {
        val result = steps.toMutableList()
        var hasLaunch = result.any { it.action == StepAction.LAUNCH }
        // 只检查开头连续的前 3 步，避免误伤应用内部的点击操作
        var i = 0
        while (i < result.size && i < 3) {
            val step = result[i]
            if (step.action == StepAction.LAUNCH) {
                hasLaunch = true
                i++
                continue
            }
            if (step.action == StepAction.CLICK && isLaunchIntent(step.hint)) {
                if (!hasLaunch) {
                    // 第一个启动类步骤转为 LAUNCH
                    result[i] = com.autotask.config.TaskStep(StepAction.LAUNCH)
                    hasLaunch = true
                } else {
                    // 已有启动步骤，删除多余的"点击APP图标"
                    result.removeAt(i)
                    i--
                }
            }
            i++
        }
        return result
    }

    /**
     * 判断点击步骤是否是"启动应用"意图（如"点击APP图标"、"打开微信"）
     */
    private fun isLaunchIntent(hint: String): Boolean {
        val h = hint.trim()
        if (h.isEmpty()) return false
        // 规则1：明确启动动作 + 短文本（打开/启动/进入 + 应用名），且不是应用内的功能入口
        if (Regex("^(打开|点开|启动|进入|开启)").containsMatchIn(h) && h.length <= 10 &&
            !Regex("设置|详情|消息|首页|我的|个人|登录|注册|搜索|列表|菜单|页面|界面|中心|帮助|关于").containsMatchIn(h)
        ) {
            return true
        }
        // 规则2：XX APP 图标 / 应用图标 / 软件图标 / 点击APP 这类描述
        return Regex("""^(点击|点一下)?\s*((app|APP|应用|程序|软件)(图标)?|图标)$""").matches(h)
    }

    /**
     * 将步骤列表反向解析为描述文本
     */
    private fun stepsToDescription(steps: List<Any>): String {
        val sb = StringBuilder()
        for (step in steps) {
            when (step) {
                is com.autotask.config.TaskStep -> {
                    when (step.action) {
                        StepAction.LAUNCH -> {} // 启动步骤不显示
                        StepAction.WAIT -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("等${step.duration}秒") }
                        StepAction.CLICK -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("点击${step.hint}") }
                        StepAction.INPUT -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("输入${step.inputText}") }
                        StepAction.SCROLL -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("滚动页面") }
                        StepAction.BACK -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("返回上一页") }
                        else -> {}
                    }
                }
                is StepInfo -> {
                    when (step.action) {
                        "wait" -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("等${step.duration}秒") }
                        "click" -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("点击${step.hint}") }
                        "input" -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("输入${step.inputText}") }
                        "scroll" -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("滚动页面") }
                        "back" -> { if (sb.isNotEmpty()) sb.append("，"); sb.append("返回上一页") }
                        else -> {}
                    }
                }
            }
        }
        return sb.toString()
    }

    // 当前解析后的步骤列表（用于创建任务时使用）
    private var currentParsedSteps: List<StepInfo> = emptyList()

    /**
     * 加载步骤到只读列表
     */
    private fun loadStepsToReadonlyList(task: AutomationTask, rvSteps: RecyclerView, btnEditActions: android.widget.Button) {
        Log.d(TAG, "loadStepsToReadonlyList: ${task.steps.size} 步")
        val steps = task.steps.map { step ->
            StepInfo(
                action = when (step.action) {
                    StepAction.LAUNCH -> "launch"
                    StepAction.CLICK -> "click"
                    StepAction.WAIT -> "wait"
                    StepAction.INPUT -> "input"
                    StepAction.SCROLL -> "scroll"
                    StepAction.VERIFY -> "verify"
                    StepAction.BACK -> "back"
                    StepAction.SWIPE -> "swipe"
                },
                hint = step.hint,
                duration = step.duration,
                inputText = step.inputText,
                scrollDirection = step.hint
            )
        }
        Log.d(TAG, "转换后: ${steps.size} 步")
        showReadonlyStepList(steps, rvSteps, btnEditActions)
    }

    /**
     * 显示只读步骤列表
     */
    private fun showReadonlyStepList(steps: List<StepInfo>, rvSteps: RecyclerView, btnEditActions: android.widget.Button) {
        Log.d(TAG, "showReadonlyStepList: ${steps.size} 步, rvSteps=${rvSteps != null}, context=${rvSteps.context}")

        if (rvSteps.context == null) {
            Log.e(TAG, "rvSteps.context 为 null")
            return
        }

        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(rvSteps.context)
        val adapter = ReadonlyStepListAdapter(steps)

        rvSteps.layoutManager = layoutManager
        rvSteps.adapter = adapter
        rvSteps.visibility = View.VISIBLE

        // 同时显示外层容器
        val parent = rvSteps.parent as? View
        parent?.visibility = View.VISIBLE

        btnEditActions.visibility = View.VISIBLE
        btnEditActions.text = "编辑动作"
        btnEditActions.isEnabled = true

        Log.d(TAG, "步骤列表已显示")
    }

    /**
     * 加载现有任务的步骤列表
     */
    private fun loadExistingSteps(task: AutomationTask, dialogView: android.view.View) {
        // 将 TaskStep 转换为 StepInfo
        val steps = task.steps.map { step ->
            StepInfo(
                action = when (step.action) {
                    StepAction.LAUNCH -> "launch"
                    StepAction.CLICK -> "click"
                    StepAction.WAIT -> "wait"
                    StepAction.INPUT -> "input"
                    StepAction.SCROLL -> "scroll"
                    StepAction.VERIFY -> "verify"
                    StepAction.BACK -> "back"
                    StepAction.SWIPE -> "swipe"
                },
                hint = step.hint,
                duration = step.duration,
                inputText = step.inputText,
                scrollDirection = step.hint
            )
        }
        currentParsedSteps = steps

        // 显示步骤预览
        val rvSteps = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_steps)
        val layoutStepPreview = dialogView.findViewById<android.view.View>(R.id.layout_step_preview)
        if (steps.isNotEmpty()) {

            val stepList = steps.toMutableList()
            lateinit var adapter: StepListAdapter
            adapter = StepListAdapter(stepList,
                onDelete = { pos ->
                    if (pos in stepList.indices) {
                        stepList.removeAt(pos)
                        currentParsedSteps = stepList.toList()
                        adapter.notifyDataSetChanged()
                    }
                },
                onEdit = { pos, step ->
                    showEditStepDialog(step) { updated ->
                        if (pos in stepList.indices) {
                            stepList[pos] = updated
                            currentParsedSteps = stepList.toList()
                            adapter.notifyDataSetChanged()
                        }
                    }
                },
                onAdd = { pos ->
                    showAddStepDialog { newStep ->
                        stepList.add(pos + 1, newStep)
                        currentParsedSteps = stepList.toList()
                        adapter.notifyDataSetChanged()
                    }
                },
                onMove = { from, to ->
                    if (from in stepList.indices && to in stepList.indices) {
                        val item = stepList.removeAt(from)
                        stepList.add(to, item)
                        currentParsedSteps = stepList.toList()
                        adapter.notifyDataSetChanged()
                    }
                }
            )
            rvSteps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(dialogView.context)
            rvSteps.adapter = adapter
            layoutStepPreview.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * 显示步骤编辑器对话框
     */
    fun showStepEditorDialog(task: AutomationTask, onSave: (AutomationTask) -> Unit) {
        Log.d(TAG, "showStepEditorDialog 开始: ${task.name}, currentContext=$currentContext")
        val ctx = currentContext
        if (ctx == null) {
            Log.e(TAG, "currentContext 为 null，无法显示对话框")
            return
        }
        val steps = task.steps.map { step ->
            StepInfo(
                action = when (step.action) {
                    StepAction.LAUNCH -> "launch"
                    StepAction.CLICK -> "click"
                    StepAction.WAIT -> "wait"
                    StepAction.INPUT -> "input"
                    StepAction.SCROLL -> "scroll"
                    StepAction.VERIFY -> "verify"
                    StepAction.BACK -> "back"
                    StepAction.SWIPE -> "swipe"
                },
                hint = step.hint,
                duration = step.duration,
                inputText = step.inputText,
                scrollDirection = step.hint
            )
        }.toMutableList()

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_step_editor, null)
        val rvSteps = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_editor_steps)
        val btnAddStep = dialogView.findViewById<android.widget.Button>(R.id.btn_add_step)
        val btnParseAI = dialogView.findViewById<android.widget.Button>(R.id.btn_parse_ai)

        lateinit var adapter: StepListAdapter
        adapter = StepListAdapter(steps,
            onDelete = { pos ->
                if (pos in steps.indices) {
                    steps.removeAt(pos)
                    adapter.notifyDataSetChanged()
                }
            },
            onEdit = { pos, step ->
                showEditStepDialog(step) { updated ->
                    if (pos in steps.indices) {
                        steps[pos] = updated
                        adapter.notifyDataSetChanged()
                    }
                }
            },
            onAdd = { pos ->
                showAddStepDialog { newStep ->
                    steps.add(pos + 1, newStep)
                    adapter.notifyDataSetChanged()
                }
            },
            onMove = { from, to ->
                if (from in steps.indices && to in steps.indices) {
                    val item = steps.removeAt(from)
                    steps.add(to, item)
                    adapter.notifyDataSetChanged()
                }
            }
        )

        rvSteps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(currentContext)
        rvSteps.adapter = adapter
        adapter.createItemTouchHelper().attachToRecyclerView(rvSteps)

        btnAddStep.setOnClickListener {
            showAddStepDialog { newStep ->
                steps.add(newStep)
                adapter.notifyDataSetChanged()
            }
        }

        btnParseAI.setOnClickListener {
            // AI 解析当前描述
            val etDescription = dialogView.findViewById<android.widget.EditText>(R.id.et_editor_description)
            val description = etDescription.text.toString().trim()
            if (description.isEmpty()) {
                Toast.makeText(currentContext, "请先输入描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val scope = CoroutineScope(Dispatchers.Main)
            scope.launch {
                try {
                    val config = repository.getGlobalConfig()
                    val textClient = ModelClient(config.textModel)
                    val parsedSteps = textClient.parseTextToSteps(description, task.packageName)
                    if (parsedSteps.isNotEmpty()) {
                        steps.clear()
                        steps.addAll(parsedSteps)
                        adapter.notifyDataSetChanged()
                        Toast.makeText(currentContext, "解析完成，共 ${parsedSteps.size} 步", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(currentContext, "解析失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(currentContext, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Log.d(TAG, "准备显示步骤编辑器对话框，currentContext=$currentContext, steps=${steps.size}")
        val builder = AlertDialog.Builder(currentContext ?: run {
            Log.e(TAG, "currentContext 为 null，无法显示对话框")
            return
        })
            .setTitle("编辑步骤")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val taskSteps = steps.map { stepInfo ->
                    when (stepInfo.action) {
                        "launch" -> com.autotask.config.TaskStep(StepAction.LAUNCH)
                        "wait" -> com.autotask.config.TaskStep(StepAction.WAIT, duration = stepInfo.duration)
                        "click" -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                        "input" -> com.autotask.config.TaskStep(StepAction.INPUT, hint = stepInfo.hint, inputText = stepInfo.inputText)
                        "scroll" -> com.autotask.config.TaskStep(StepAction.SCROLL, hint = stepInfo.scrollDirection)
                        "back" -> com.autotask.config.TaskStep(StepAction.BACK)
                        else -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                    }
                }
                val updatedTask = task.copy(steps = normalizeSteps(taskSteps))
                onSave(updatedTask)
            }
            .setNegativeButton("取消", null)

        Log.d(TAG, "显示步骤编辑器对话框")
        builder.show()
        Log.d(TAG, "步骤编辑器对话框已显示")
    }

    /**
     * 显示步骤列表
     */
    private fun showStepList(steps: List<StepInfo>, binding: DialogAddTaskBinding) {
        Log.d(TAG, "showStepList: ${steps.size} 步")
        val stepList = steps.toMutableList()
        currentParsedSteps = stepList.toList()

        lateinit var adapter: StepListAdapter
        adapter = StepListAdapter(
            stepList,
            onDelete = { position ->
                if (position in stepList.indices) {
                    stepList.removeAt(position)
                    currentParsedSteps = stepList.toList()
                    adapter.notifyDataSetChanged()
                }
            },
            onEdit = { position, step ->
                showEditStepDialog(step) { updatedStep ->
                    if (position in stepList.indices) {
                        stepList[position] = updatedStep
                        currentParsedSteps = stepList.toList()
                        adapter.notifyDataSetChanged()
                    }
                }
            },
            onAdd = { position ->
                showAddStepDialog { newStep ->
                    stepList.add(position + 1, newStep)
                    currentParsedSteps = stepList.toList()
                    adapter.notifyDataSetChanged()
                }
            },
            onMove = { from, to ->
                if (from in stepList.indices && to in stepList.indices) {
                    val item = stepList.removeAt(from)
                    stepList.add(to, item)
                    currentParsedSteps = stepList.toList()
                    adapter.notifyDataSetChanged()
                }
            }
        )

        binding.rvSteps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(binding.root.context)
        binding.rvSteps.adapter = adapter
        binding.layoutStepPreview.visibility = android.view.View.VISIBLE

        // 启用拖拽排序
        adapter.createItemTouchHelper().attachToRecyclerView(binding.rvSteps)
    }

    /**
     * 显示编辑步骤对话框
     */
    private fun showEditStepDialog(step: StepInfo, onSave: (StepInfo) -> Unit) {
        val ctx = currentContext ?: return
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_step, null)
        val spinnerActionType = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_action_type)
        val tilDuration = dialogView.findViewById<android.view.View>(R.id.til_duration) as com.google.android.material.textfield.TextInputLayout
        val etDuration = dialogView.findViewById<android.widget.EditText>(R.id.et_duration)
        val tilDirection = dialogView.findViewById<android.view.View>(R.id.til_direction) as com.google.android.material.textfield.TextInputLayout
        val spinnerDirection = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.spinner_direction)
        val etHint = dialogView.findViewById<android.widget.EditText>(R.id.et_hint)
        val tilInputText = dialogView.findViewById<android.view.View>(R.id.til_input_text) as com.google.android.material.textfield.TextInputLayout
        val etInputText = dialogView.findViewById<android.widget.EditText>(R.id.et_input_text)

        // 设置动作类型选项
        val actionTypes = listOf("点击", "等待", "输入", "滚动", "返回", "启动")
        val actionTypeAdapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, actionTypes)
        spinnerActionType.setAdapter(actionTypeAdapter)

        // 设置滚动方向选项
        val directions = listOf("向下", "向上")
        val directionAdapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, directions)
        spinnerDirection.setAdapter(directionAdapter)

        // 填充现有数据
        when (step.action) {
            "click" -> spinnerActionType.setText("点击", false)
            "wait" -> {
                spinnerActionType.setText("等待", false)
                tilDuration.visibility = android.view.View.VISIBLE
                etDuration.setText(step.duration.toString())
            }
            "input" -> {
                spinnerActionType.setText("输入", false)
                tilInputText.visibility = android.view.View.VISIBLE
                etInputText.setText(step.inputText)
            }
            "scroll" -> {
                spinnerActionType.setText("滚动", false)
                tilDirection.visibility = android.view.View.VISIBLE
                spinnerDirection.setText(step.scrollDirection, false)
            }
            "back" -> spinnerActionType.setText("返回", false)
            "launch" -> spinnerActionType.setText("启动", false)
        }
        etHint.setText(step.hint)

        // 动作类型切换时显示/隐藏对应字段
        spinnerActionType.setOnItemClickListener { _, _, position, _ ->
            val action = actionTypes[position]
            tilDuration.visibility = if (action == "等待") android.view.View.VISIBLE else android.view.View.GONE
            tilDirection.visibility = if (action == "滚动") android.view.View.VISIBLE else android.view.View.GONE
            tilInputText.visibility = if (action == "输入") android.view.View.VISIBLE else android.view.View.GONE
        }

        AlertDialog.Builder(ctx)
            .setTitle("编辑步骤")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val actionType = spinnerActionType.text.toString()
                val action = when (actionType) {
                    "点击" -> "click"
                    "等待" -> "wait"
                    "输入" -> "input"
                    "滚动" -> "scroll"
                    "返回" -> "back"
                    "启动" -> "launch"
                    else -> "click"
                }

                val newStep = StepInfo(
                    action = action,
                    hint = etHint.text.toString().trim(),
                    duration = etDuration.text.toString().toLongOrNull() ?: 3,
                    inputText = etInputText.text.toString().trim(),
                    scrollDirection = spinnerDirection.text.toString()
                )
                onSave(newStep)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示添加步骤对话框
     */
    private fun showAddStepDialog(onAdd: (StepInfo) -> Unit) {
        val newStep = StepInfo(action = "click", hint = "新步骤")
        showEditStepDialog(newStep) { onAdd(it) }
    }

    /**
     * 显示添加任务对话框
     */
    fun showAddTaskDialog(context: Context, onTaskCreated: (AutomationTask) -> Unit) {
        currentContext = context
        val binding = DialogAddTaskBinding.inflate(android.view.LayoutInflater.from(context))
        currentParsedSteps = emptyList()

        val dialog = AlertDialog.Builder(context)
            .setTitle("添加自动化任务")
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.tilDescription.visibility = android.view.View.VISIBLE
                        binding.tvTeachHint.visibility = android.view.View.GONE
                        binding.btnAiParse.visibility = android.view.View.VISIBLE
                    }
                    1 -> {
                        binding.tilDescription.visibility = android.view.View.GONE
                        binding.tvTeachHint.visibility = android.view.View.VISIBLE
                        binding.btnAiParse.visibility = android.view.View.GONE
                        binding.layoutStepPreview.visibility = android.view.View.GONE
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // 应用选择
        selectedApp = null
        binding.layoutSelectedApp.setOnClickListener {
            AppChooserDialog.show(context, "选择要操作的应用") { app ->
                selectedApp = app
                binding.tvSelectedName.text = app.appName
                binding.tvSelectedPackage.text = app.packageName
                binding.ivSelectedIcon.setImageDrawable(app.icon)
            }
        }

        // AI 解析按钮
        binding.btnAiParse.setOnClickListener {
            val description = binding.etDescription.text.toString().trim()
            if (description.isEmpty()) {
                Toast.makeText(context, "请先输入描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val packageName = selectedApp?.packageName ?: ""

            // 显示加载状态
            binding.layoutLoading.visibility = android.view.View.VISIBLE
            binding.tvLoadingText.text = "正在解析..."
            binding.layoutStepPreview.visibility = android.view.View.GONE
            binding.btnAiParse.isEnabled = false
            binding.btnConfirm.isEnabled = false

            val scope = CoroutineScope(Dispatchers.Main)
            scope.launch {
                try {
                    // 更新加载提示
                    binding.tvLoadingText.text = "正在调用 AI 接口..."

                    val config = repository.getGlobalConfig()
                    binding.tvLoadingText.text = "正在解析步骤..."

                    val textClient = ModelClient(config.textModel)
                    val steps = textClient.parseTextToSteps(description, packageName)

                    // 隐藏加载状态
                    binding.layoutLoading.visibility = android.view.View.GONE
                    binding.btnAiParse.isEnabled = true
                    binding.btnConfirm.isEnabled = true

                    if (steps.isEmpty()) {
                        Toast.makeText(context, "解析失败，请尝试更详细的描述", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // 保存步骤列表并显示
                    currentParsedSteps = steps
                    showStepList(steps, binding)

                    Toast.makeText(context, "解析完成，共 ${steps.size} 步", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    // 隐藏加载状态
                    binding.layoutLoading.visibility = android.view.View.GONE
                    binding.btnAiParse.isEnabled = true
                    binding.btnConfirm.isEnabled = true

                    Log.e(TAG, "解析失败: ${e.message}")
                    Toast.makeText(context, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val name = binding.etTaskName.text.toString().trim()
            val app = selectedApp

            if (name.isEmpty()) {
                Toast.makeText(context, "请输入任务名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (app == null) {
                Toast.makeText(context, "请选择应用", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedTab = binding.tabLayout.selectedTabPosition

            if (selectedTab == 0) {
                val description = binding.etDescription.text.toString().trim()
                if (description.isEmpty()) {
                    Toast.makeText(context, "请输入操作流程描述", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 如果有解析后的步骤列表，直接使用；否则重新解析
                if (currentParsedSteps.isNotEmpty()) {
                    // 使用已解析的步骤
                    val taskSteps = currentParsedSteps.map { stepInfo ->
                        when (stepInfo.action) {
                            "launch" -> com.autotask.config.TaskStep(StepAction.LAUNCH)
                            "wait" -> com.autotask.config.TaskStep(StepAction.WAIT, duration = stepInfo.duration)
                            "click" -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                            "input" -> com.autotask.config.TaskStep(StepAction.INPUT, hint = stepInfo.hint, inputText = stepInfo.inputText)
                            "scroll" -> com.autotask.config.TaskStep(StepAction.SCROLL, hint = stepInfo.scrollDirection)
                            "back" -> com.autotask.config.TaskStep(StepAction.BACK)
                            else -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                        }
                    }
                    val task = AutomationTask(name = name, packageName = app.packageName, steps = normalizeSteps(taskSteps))
                    onTaskCreated(task)
                    dialog.dismiss()
                } else {
                    // 没有解析过，走原来的流程
                    createTaskFromDescription(context, name, app.packageName, description, dialog, onTaskCreated)
                }
            } else {
                startTeachMode(context, name, app.packageName, dialog, onTaskCreated)
            }
        }

        dialog.show()
    }

    /**
     * 从文本描述创建任务
     */
    private fun createTaskFromDescription(
        context: Context,
        name: String,
        packageName: String,
        description: String,
        dialog: AlertDialog,
        onTaskCreated: (AutomationTask) -> Unit
    ) {
        val config = repository.getGlobalConfig()
        val textClient = ModelClient(config.textModel)

        Toast.makeText(context, "正在解析描述...", Toast.LENGTH_SHORT).show()

        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                val steps = textClient.parseTextToSteps(description, packageName)
                if (steps.isEmpty()) {
                    Toast.makeText(context, "AI 解析失败，请尝试更详细的描述", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 显示步骤预览对话框
                val stepPreview = steps.mapIndexed { index, stepInfo ->
                    val desc = when (stepInfo.action) {
                        "wait" -> "等待 ${stepInfo.duration} 秒"
                        "click" -> "点击 ${stepInfo.hint}"
                        "input" -> "输入 ${stepInfo.inputText}"
                        "scroll" -> "滚动页面"
                        "back" -> "返回上一页"
                        "launch" -> "启动应用"
                        else -> stepInfo.hint
                    }
                    "${index + 1}. $desc"
                }.joinToString("\n")

                AlertDialog.Builder(context)
                    .setTitle("解析结果（共 ${steps.size} 步）")
                    .setMessage(stepPreview)
                    .setPositiveButton("确认保存") { _, _ ->
                        val taskSteps = steps.map { stepInfo ->
                            when (stepInfo.action) {
                                "launch" -> com.autotask.config.TaskStep(StepAction.LAUNCH)
                                "wait" -> com.autotask.config.TaskStep(StepAction.WAIT, duration = stepInfo.duration)
                                "click" -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                                "input" -> com.autotask.config.TaskStep(StepAction.INPUT, hint = stepInfo.hint, inputText = stepInfo.inputText)
                                "scroll" -> com.autotask.config.TaskStep(StepAction.SCROLL, hint = stepInfo.scrollDirection)
                                "back" -> com.autotask.config.TaskStep(StepAction.BACK)
                                else -> com.autotask.config.TaskStep(StepAction.CLICK, hint = stepInfo.hint)
                            }
                        }
                        val task = AutomationTask(
                            name = name,
                            packageName = packageName,
                            steps = normalizeSteps(taskSteps)
                        )
                        onTaskCreated(task)
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()

            } catch (e: Exception) {
                Log.e(TAG, "解析描述失败: ${e.message}")
                Toast.makeText(context, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 手动解析描述（兜底方案，不依赖模型）
     */
    private fun parseDescriptionManually(description: String, packageName: String): List<com.autotask.config.TaskStep> {
        val steps = mutableListOf<com.autotask.config.TaskStep>()
        steps.add(com.autotask.config.TaskStep(StepAction.LAUNCH))

        // 按常见分隔符拆分步骤
        val parts = description.split("[，,、；;。\n]+".toRegex()).map { it.trim() }.filter { it.isNotBlank() }

        for (part in parts) {
            when {
                part.contains("等") || part.contains("等待") || part.contains("sleep") -> {
                    val seconds = Regex("\\d+").find(part)?.value?.toLongOrNull() ?: 3
                    steps.add(com.autotask.config.TaskStep(StepAction.WAIT, duration = seconds))
                }
                part.contains("点击") || part.contains("点一下") -> {
                    val hint = part.replace("点击|点一下|一下".toRegex(), "").trim()
                    if (hint.isNotEmpty()) {
                        steps.add(com.autotask.config.TaskStep(StepAction.CLICK, hint = hint))
                    }
                }
                part.startsWith("点") || part.startsWith("按") || part.contains("tap") -> {
                    val hint = part.replaceFirst("点|按|tap".toRegex(), "").trim()
                    if (hint.isNotEmpty()) {
                        steps.add(com.autotask.config.TaskStep(StepAction.CLICK, hint = hint))
                    }
                }
                part.contains("输入") || part.contains("填写") || part.contains("input") -> {
                    val hint = part.replace("输入|填写|input".toRegex(), "").trim()
                    steps.add(com.autotask.config.TaskStep(StepAction.INPUT, inputText = hint))
                }
                part.contains("滚动") || part.contains("滑") || part.contains("scroll") -> {
                    steps.add(com.autotask.config.TaskStep(StepAction.SCROLL, hint = part))
                }
                part.contains("返回") || part.contains("后退") || part.contains("back") -> {
                    steps.add(com.autotask.config.TaskStep(StepAction.BACK))
                }
                else -> {
                    steps.add(com.autotask.config.TaskStep(StepAction.CLICK, hint = part))
                }
            }
        }

        return steps
    }

    /**
     * 启动引导教学模式
     */
    private fun startTeachMode(
        context: Context,
        name: String,
        packageName: String,
        dialog: AlertDialog,
        onTaskCreated: (AutomationTask) -> Unit
    ) {
        if (!AutoTaskAccessibilityService.isAvailable()) {
            Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            return
        }

        dialog.dismiss()

        val intent = Intent(context, FloatingWindowService::class.java).apply {
            putExtra("action", "show_teach")
            putExtra("step_index", 0)
            putExtra("total_steps", 10)
        }
        context.startService(intent)

        AutoTaskAccessibilityService.instance?.launchApp(packageName)

        val teachSteps = mutableListOf<String>()
        FloatingWindowService.teachCallback = object : TeachCallback {
            override fun onStepConfirmed(hint: String) {
                teachSteps.add(hint)
                val serviceIntent = Intent(context, FloatingWindowService::class.java).apply {
                    putExtra("action", "show_teach")
                    putExtra("step_index", teachSteps.size)
                    putExtra("total_steps", 10)
                }
                context.startService(serviceIntent)
            }

            override fun onStepSkipped() {
                val serviceIntent = Intent(context, FloatingWindowService::class.java).apply {
                    putExtra("action", "show_teach")
                    putExtra("step_index", teachSteps.size)
                    putExtra("total_steps", 10)
                }
                context.startService(serviceIntent)
            }

            override fun onTeachFinished() {
                val steps = mutableListOf<com.autotask.config.TaskStep>()
                steps.add(com.autotask.config.TaskStep(StepAction.LAUNCH))
                for (hint in teachSteps) {
                    steps.add(com.autotask.config.TaskStep(StepAction.CLICK, hint = hint))
                }
                val task = AutomationTask(
                    name = name,
                    packageName = packageName,
                    steps = steps
                )
                onTaskCreated(task)

                val hideIntent = Intent(context, FloatingWindowService::class.java)
                hideIntent.putExtra("action", "hide")
                context.startService(hideIntent)
            }
        }
    }
}
