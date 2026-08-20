package com.autotask.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.autotask.R
import com.github.promeg.pinyinhelper.Pinyin
import java.text.Collator
import java.util.Locale

/**
 * 应用选择对话框 - 单选模式，带分组和 A-Z 侧边栏
 */
object AppChooserDialog {

    data class AppItem(
        val packageName: String,
        val appName: String,
        val icon: Drawable?
    )

    // 列表项类型
    private const val TYPE_HEADER = 0
    private const val TYPE_APP = 1

    private data class ListItem(
        val type: Int,
        val letter: Char = ' ',
        val app: AppItem? = null
    )

    fun show(
        context: Context,
        title: String = "选择应用",
        onSelected: (AppItem) -> Unit
    ) {
        val pm = context.packageManager

        // 获取所有有启动入口的应用 + 所有已安装应用（取并集，确保不遗漏）
        val launcherApps = try {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(mainIntent, 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }

        val allApps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }

        // 合并去重
        val mergedApps = (launcherApps + allApps)
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                AppItem(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                )
            }
            .sortedWith(
                compareBy(Collator.getInstance(Locale.CHINESE)) { it.appName }
            )

        // 创建对话框视图
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_app_chooser, null)
        val searchEditText = dialogView.findViewById<TextView>(R.id.et_search)
        val listView = dialogView.findViewById<ListView>(R.id.lv_apps)
        val sidebar = dialogView.findViewById<LinearLayout>(R.id.letter_sidebar)
        val switchShowSystem = dialogView.findViewById<android.widget.CompoundButton>(R.id.switch_show_system)

        // 默认不显示系统应用
        val userApps = mergedApps.filter { !isSystemApp(it.packageName, context) }
        val adapter = GroupedAppListAdapter(context, userApps)
        listView.adapter = adapter

        // 显示系统应用开关
        switchShowSystem.setOnCheckedChangeListener { _, isChecked ->
            val apps = if (isChecked) mergedApps else userApps
            adapter.updateApps(apps)
            buildLetterSidebar(context, sidebar, adapter, listView)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        // 搜索过滤
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter.filter(s?.toString() ?: "")
                buildLetterSidebar(context, sidebar, adapter, listView)
            }
        })

        // 点击选择
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            if (item.type == TYPE_APP && item.app != null) {
                onSelected(item.app)
                dialog.dismiss()
            }
        }

        // 构建 A-Z 侧边栏
        buildLetterSidebar(context, sidebar, adapter, listView)

        dialog.show()
    }

    private fun buildLetterSidebar(
        context: Context,
        sidebar: LinearLayout,
        adapter: GroupedAppListAdapter,
        listView: ListView
    ) {
        sidebar.removeAllViews()
        val available = adapter.getAvailableLetters()

        // # 分组（数字和非字母字符）
        if ('#' in available) {
            sidebar.addView(createLetterView(context, '#', adapter, listView))
        }

        // A-Z 全部字母（只要有应用就显示）
        for (c in 'A'..'Z') {
            sidebar.addView(createLetterView(context, c, adapter, listView))
        }
    }

    private fun createLetterView(
        context: Context,
        letter: Char,
        adapter: GroupedAppListAdapter,
        listView: ListView
    ): TextView {
        return TextView(context).apply {
            text = letter.toString()
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 4, 0, 4)
            setTextColor(0xFFFFFFFF.toInt())
            isClickable = true
            setOnClickListener {
                val pos = adapter.getLetterPosition(letter)
                if (pos >= 0) listView.smoothScrollToPosition(pos)
            }
        }
    }

    /**
     * 判断是否为系统应用
     */
    private fun isSystemApp(packageName: String, context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            // FLAG_SYSTEM 表示系统应用，FLAG_UPDATED_SYSTEM_APP 表示升级过的系统应用
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            isSystem && !isUpdatedSystem
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取首字母用于分组
     * - 英文直接返回大写首字母
     * - 数字和非字母字符归为 '#'
     * - 中文使用 pinyin4j 转拼音首字母
     */
    private fun getFirstLetter(text: String): Char {
        if (text.isEmpty()) return '#'
        val first = text[0]
        // 英文大写
        if (first in 'A'..'Z') return first
        // 英文小写转大写
        if (first in 'a'..'z') return first.uppercaseChar()
        // 数字
        if (first in '0'..'9') return '#'
        // 中文用 TinyPinyin 转拼音首字母
        try {
            val pinyin = Pinyin.toPinyin(first)
            if (pinyin.isNotEmpty()) {
                val c = pinyin[0].uppercaseChar()
                if (c in 'A'..'Z') return c
            }
        } catch (e: Exception) {
            // 忽略异常，返回 '#'
        }
        return '#'
    }

    /**
     * 分组应用列表适配器
     */
    private class GroupedAppListAdapter(
        private val context: Context,
        initialApps: List<AppItem>
    ) : BaseAdapter(), Filterable {

        private var allApps: List<AppItem> = initialApps
        private var allItems: List<ListItem> = buildGroupedList(allApps)
        private var filteredItems: List<ListItem> = allItems
        private val filter = AppFilter()

        fun updateApps(apps: List<AppItem>) {
            allApps = apps
            filteredItems = buildGroupedList(apps)
            notifyDataSetChanged()
        }

        private fun buildGroupedList(apps: List<AppItem>): List<ListItem> {
            val items = mutableListOf<ListItem>()
            var lastLetter = ' '
            for (app in apps) {
                val letter = getFirstLetter(app.appName)
                if (letter != lastLetter) {
                    items.add(ListItem(TYPE_HEADER, letter))
                    lastLetter = letter
                }
                items.add(ListItem(TYPE_APP, app = app))
            }
            return items
        }

        fun getAvailableLetters(): Set<Char> {
            return filteredItems.filter { it.type == TYPE_HEADER }.map { it.letter }.toSet()
        }

        fun getLetterPosition(letter: Char): Int {
            return filteredItems.indexOfFirst { it.type == TYPE_HEADER && it.letter == letter }
        }

        override fun getCount(): Int = filteredItems.size
        override fun getItem(position: Int): ListItem = filteredItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = filteredItems[position].type

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = filteredItems[position]
            return when (item.type) {
                TYPE_HEADER -> getHeaderView(item, convertView, parent)
                else -> getAppView(item, convertView, parent)
            }
        }

        private fun getHeaderView(item: ListItem, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_app_header, parent, false)
            val tv = view.findViewById<TextView>(R.id.tv_header)
            tv.text = "── ${item.letter} ──"
            return view
        }

        private fun getAppView(item: ListItem, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_app, parent, false)

            val app = item.app!!
            val ivIcon = view.findViewById<ImageView>(R.id.iv_app_icon)
            val tvName = view.findViewById<TextView>(R.id.tv_app_name)
            val tvPackage = view.findViewById<TextView>(R.id.tv_app_package)

            ivIcon.setImageDrawable(app.icon)
            tvName.text = app.appName
            tvPackage.text = app.packageName

            return view
        }

        override fun getFilter(): Filter = filter

        private inner class AppFilter : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.lowercase()?.trim() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.appName.lowercase().contains(query) ||
                        it.packageName.lowercase().contains(query)
                    }
                }
                return FilterResults().apply { values = filtered; count = filtered.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults) {
                val apps = results.values as List<AppItem>
                filteredItems = buildGroupedList(apps)
                notifyDataSetChanged()
            }
        }
    }
}
