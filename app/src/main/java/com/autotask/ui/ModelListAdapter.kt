package com.autotask.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.autotask.R
import com.autotask.config.ModelConfig

/**
 * 模型列表适配器 - 支持多模型管理和切换
 */
class ModelListAdapter(
    private val models: MutableList<ModelConfig>,
    private val getCurrentIndex: () -> Int,
    private val onDelete: (Int) -> Unit,
    private val onSelect: (Int) -> Unit
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

        holder.btnDelete.setOnClickListener { onDelete(position) }
        holder.itemView.setOnClickListener { onSelect(position) }
    }

    override fun getItemCount(): Int = models.size
}
