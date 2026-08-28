package com.autotask.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.autotask.R
import com.autotask.config.ModelConfig

/**
 * 模型列表适配器 - 支持多模型管理和切换
 * 交互：点击行 = 切换当前；点击右侧删除按钮 = 删除（带确认）；长按 = 拖动排序
 */
class ModelListAdapter(
    private val models: MutableList<ModelConfig>,
    private val getCurrentIndex: () -> Int,
    private val onDelete: (Int) -> Unit,
    private val onSelect: (Int) -> Unit,
    private val onMove: (Int, Int) -> Unit = { _, _ -> },
    private val label: String = "" // 日志标识：文本/视觉，便于区分两个列表
) : RecyclerView.Adapter<ModelListAdapter.ModelViewHolder>() {

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvModelName: TextView = view.findViewById(R.id.tv_model_name)
        val tvModelUrl: TextView = view.findViewById(R.id.tv_model_url)
        val tvCurrent: TextView = view.findViewById(R.id.tv_current)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_model)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = models[position]
        holder.tvModelName.text = model.modelName.ifEmpty { "未命名" }
        holder.tvModelUrl.text = model.apiBase

        // 标记当前使用的模型（动态获取）
        val currentIndex = getCurrentIndex()
        if (position == currentIndex) {
            holder.tvCurrent.visibility = View.VISIBLE
            holder.tvCurrent.text = "当前"
        } else {
            holder.tvCurrent.visibility = View.GONE
        }

        // 点击右侧删除按钮 = 删除（调用方会弹确认对话框）
        holder.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
        // 点击行其他区域 = 切换当前模型
        holder.itemView.setOnClickListener { onSelect(holder.bindingAdapterPosition) }
        android.util.Log.d("AT-Settings", "[$label] 绑定列表项: pos=$position 模型=${model.modelName} 总数=${models.size}")
    }

    override fun getItemCount(): Int = models.size

    /**
     * 拖拽排序（长按 item 拖动）
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
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from in models.indices && to in models.indices && from != to) {
                    val item = models.removeAt(from)
                    models.add(to, item)
                    notifyItemMoved(from, to)
                    onMove(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true
        }
        return ItemTouchHelper(callback)
    }
}
