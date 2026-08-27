package com.autotask.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 适配 ScrollView 嵌套的 LinearLayoutManager：
 * 按内容高度测量（UNSPECIFIED），避免 wrap_content 高度 + item 复用
 * 导致的列表错乱（item 重复/消失、"点一下变三个"）。
 *
 * 使用场景：RecyclerView 放在 ScrollView / NestedScrollView 内，
 * 且 RecyclerView 高度为 wrap_content 时。
 */
class WrapContentLinearLayoutManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayoutManager(context, attrs, defStyleAttr, defStyleRes) {

    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        // 高度按内容自适应（解决 ScrollView 嵌套时 RecyclerView 高度只测一次、
        // 内容变化后不重新测量导致 item 复用错乱的问题）
        val realHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        super.onMeasure(recycler, state, widthSpec, realHeightSpec)
    }
}
