package com.autotask.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.autotask.R
import com.autotask.model.StepInfo

/**
 * 步骤列表适配器 - 支持增删改、拖拽排序
 */
class StepListAdapter(
    private val steps: MutableList<StepInfo>,
    private val onDelete: (Int) -> Unit,
    private val onEdit: (Int, StepInfo) -> Unit,
    private val onAdd: (Int) -> Unit,
    private val onMove: (Int, Int) -> Unit
) : RecyclerView.Adapter<StepListAdapter.StepViewHolder>() {

    private var itemTouchHelper: ItemTouchHelper? = null

    class StepViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStepNumber: TextView = view.findViewById(R.id.tv_step_number)
        val tvStepAction: TextView = view.findViewById(R.id.tv_step_action)
        val tvStepDesc: TextView = view.findViewById(R.id.tv_step_desc)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_step)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit_step)
        val btnAdd: ImageButton = view.findViewById(R.id.btn_add_step)
        val btnDrag: ImageButton = view.findViewById(R.id.btn_drag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_step, parent, false)
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

        holder.btnDelete.setOnClickListener { onDelete(position) }
        holder.btnEdit.setOnClickListener { onEdit(position, step) }
        holder.btnAdd.setOnClickListener { onAdd(position) }
        holder.btnDrag.setOnClickListener { itemTouchHelper?.startDrag(holder) }

        // 拖拽手柄触摸事件
        holder.btnDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = steps.size

    /**
     * 设置 ItemTouchHelper
     */
    fun setItemTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    /**
     * 创建拖拽排序回调
     */
    fun createItemTouchHelper(): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                onMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false // 使用按钮拖拽

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.8f
                    viewHolder?.itemView?.scaleX = 1.05f
                    viewHolder?.itemView?.scaleY = 1.05f
                    viewHolder?.itemView?.elevation = 8f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                viewHolder.itemView.elevation = 0f
            }
        }
        val helper = ItemTouchHelper(callback)
        itemTouchHelper = helper
        return helper
    }
}
