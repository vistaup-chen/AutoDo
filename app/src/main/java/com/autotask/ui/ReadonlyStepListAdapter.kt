package com.autotask.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.autotask.R
import com.autotask.model.StepInfo

/**
 * 只读步骤列表适配器 - 仅显示步骤，不可编辑
 */
class ReadonlyStepListAdapter(
    private val steps: List<StepInfo>
) : RecyclerView.Adapter<ReadonlyStepListAdapter.StepViewHolder>() {

    class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStepNumber: TextView = view.findViewById(R.id.tv_step_number)
        val tvStepAction: TextView = view.findViewById(R.id.tv_step_action)
        val tvStepDesc: TextView = view.findViewById(R.id.tv_step_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_step_readonly, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = steps[position]
        holder.tvStepNumber.text = "${position + 1}"

        val (actionLabel, actionColor) = when (step.action) {
            "click" -> "点击" to "#2196F3"
            "wait" -> "等待" to "#FF9800"
            "input" -> "输入" to "#4CAF50"
            "scroll" -> "滚动" to "#9C27B0"
            "back" -> "返回" to "#F44336"
            "launch" -> "启动" to "#607D8B"
            else -> "其他" to "#757575"
        }

        holder.tvStepAction.text = actionLabel
        holder.tvStepAction.setTextColor(Color.parseColor(actionColor))

        val desc = when (step.action) {
            "click" -> step.hint
            "wait" -> "${step.duration} 秒"
            "input" -> step.inputText
            "scroll" -> step.scrollDirection
            "back" -> ""
            else -> step.hint
        }
        holder.tvStepDesc.text = desc
    }

    override fun getItemCount(): Int = steps.size
}
