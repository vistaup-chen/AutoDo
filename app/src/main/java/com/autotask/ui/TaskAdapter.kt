package com.autotask.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autotask.config.AutomationTask
import com.autotask.databinding.ItemTaskBinding

/**
 * 任务列表适配器
 */
class TaskAdapter(
    private val onToggleEnabled: (AutomationTask, Boolean) -> Unit,
    private val onDelete: (AutomationTask) -> Unit,
    private val onExecute: (AutomationTask) -> Unit,
    private val onEdit: (AutomationTask) -> Unit,
    private val onEditActions: (AutomationTask) -> Unit
) : ListAdapter<AutomationTask, TaskAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(
        private val binding: ItemTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: AutomationTask) {
            binding.tvTaskName.text = task.name
            binding.tvTaskPackage.text = task.packageName

            // 状态信息
            val statusText = buildString {
                append("步骤: ${task.steps.size} | ")
                append("成功: ${task.successCount} | ")
                append("失败: ${task.failCount}")
                if (task.lastExecuted > 0) {
                    append("\n上次: ${formatTime(task.lastExecuted)}")
                    append(" (${if (task.lastResult == "success") "✓" else "✗"})")
                }
            }
            binding.tvTaskStatus.text = statusText

            // 设置颜色
            val statusColor = when {
                task.lastResult == "fail" -> Color.parseColor("#F44336")
                task.lastResult == "success" -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#757575")
            }
            binding.tvTaskStatus.setTextColor(statusColor)

            // 开关状态
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = task.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(task, isChecked)
            }

            // 编辑按钮
            binding.btnEdit.setOnClickListener {
                onEdit(task)
            }

            // 编辑动作按钮
            binding.btnEditActions.setOnClickListener {
                android.util.Log.d("TaskAdapter", "编辑动作按钮被点击: ${task.name}")
                onEditActions(task)
            }

            // 执行按钮
            binding.btnExecute.setOnClickListener {
                onExecute(task)
            }

            // 删除按钮
            binding.btnDelete.setOnClickListener {
                onDelete(task)
            }
        }

        private fun formatTime(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            val minutes = diff / 60000
            val hours = minutes / 60
            val days = hours / 24

            return when {
                minutes < 1 -> "刚刚"
                minutes < 60 -> "${minutes}分钟前"
                hours < 24 -> "${hours}小时前"
                else -> "${days}天前"
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<AutomationTask>() {
        override fun areItemsTheSame(oldItem: AutomationTask, newItem: AutomationTask): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AutomationTask, newItem: AutomationTask): Boolean {
            return oldItem == newItem
        }
    }
}
