package com.autotask.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 任务存储仓库 - 使用 SharedPreferences + 文件存储
 */
class TaskRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_task", Context.MODE_PRIVATE)
    private val tasksDir = File(context.filesDir, "tasks").apply { mkdirs() }
    private val screenshotsDir = File(context.filesDir, "screenshots").apply { mkdirs() }

    companion object {
        private const val KEY_TASK_IDS = "task_ids"
        private const val KEY_GLOBAL_CONFIG = "global_config"
        private const val KEY_TASKS_DATA = "tasks_data"
    }

    /**
     * 保存所有任务到单个 JSON 文件（更可靠）
     */
    suspend fun saveAllTasks(tasks: List<AutomationTask>) = withContext(Dispatchers.IO) {
        val json = ConfigManager.tasksToJson(tasks)
        prefs.edit().putString(KEY_TASKS_DATA, json).apply()
    }

    /**
     * 加载所有任务
     */
    suspend fun loadAllTasks(): List<AutomationTask> = withContext(Dispatchers.IO) {
        val json = prefs.getString(KEY_TASKS_DATA, null)
        if (json != null) {
            try {
                ConfigManager.jsonToTasks(json)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /**
     * 保存任务（使用新的持久化方式）
     */
    suspend fun saveTask(task: AutomationTask): AutomationTask = withContext(Dispatchers.IO) {
        val taskToSave = if (task.id.isEmpty()) task.copy(id = UUID.randomUUID().toString()) else task
        val tasks = loadAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskToSave.id }
        if (index >= 0) {
            tasks[index] = taskToSave
        } else {
            tasks.add(taskToSave)
        }
        saveAllTasks(tasks)
        taskToSave
    }

    /**
     * 删除任务
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        val tasks = loadAllTasks().toMutableList()
        tasks.removeAll { it.id == taskId }
        saveAllTasks(tasks)
    }

    /**
     * 获取所有任务
     */
    suspend fun getAllTasks(): List<AutomationTask> = loadAllTasks()

    /**
     * 获取所有启用的任务
     */
    suspend fun getEnabledTasks(): List<AutomationTask> = loadAllTasks().filter { it.enabled }

    /**
     * 更新任务
     */
    suspend fun updateTask(task: AutomationTask) = saveTask(task)

    /**
     * 更新任务执行结果
     */
    suspend fun updateTaskResult(taskId: String, success: Boolean) = withContext(Dispatchers.IO) {
        val tasks = loadAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val task = tasks[index]
            tasks[index] = task.copy(
                lastExecuted = System.currentTimeMillis(),
                lastResult = if (success) "success" else "fail",
                successCount = if (success) task.successCount + 1 else task.successCount,
                failCount = if (!success) task.failCount + 1 else task.failCount
            )
            saveAllTasks(tasks)
        }
    }

    /**
     * 保存截图
     */
    fun saveScreenshot(bitmapBytes: ByteArray, taskId: String, stepIndex: Int): String {
        val fileName = "${taskId}_step${stepIndex}_${System.currentTimeMillis()}.png"
        val file = File(screenshotsDir, fileName)
        file.writeBytes(bitmapBytes)
        return file.absolutePath
    }

    /**
     * 保存全局配置
     */
    fun saveGlobalConfig(config: GlobalConfig) {
        prefs.edit().putString(KEY_GLOBAL_CONFIG, ConfigManager.configToJson(config)).apply()
    }

    /**
     * 获取全局配置
     */
    fun getGlobalConfig(): GlobalConfig {
        val json = prefs.getString(KEY_GLOBAL_CONFIG, null)
        return if (json != null) ConfigManager.jsonToConfig(json) else GlobalConfig()
    }

    private fun getTaskIds(): Set<String> {
        return prefs.getStringSet(KEY_TASK_IDS, emptySet()) ?: emptySet()
    }

    private fun saveTaskIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_TASK_IDS, ids).apply()
    }
}
