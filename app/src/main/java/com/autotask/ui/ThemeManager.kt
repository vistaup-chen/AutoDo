package com.autotask.ui

import android.app.Activity
import android.content.Context
import com.autotask.R

/**
 * 主题管理器 - 负责主题切换和持久化
 */
object ThemeManager {

    private const val PREF_NAME = "theme_prefs"
    private const val KEY_THEME = "current_theme"

    /**
     * 主题枚举
     */
    enum class Theme(
        val id: String,
        val label: String,
        val icon: String,
        val resId: Int,
        val primaryColor: String
    ) {
        PURPLE("purple", "绛紫", "🟣", R.style.Theme_AutoTask_Purple, "#6750A4"),
        BLUE("blue", "湛蓝", "🟦", R.style.Theme_AutoTask_Blue, "#1565C0"),
        SKY("sky", "天空蓝", "🩵", R.style.Theme_AutoTask_Sky, "#0288D1"),
        GREEN("green", "翠绿", "🟢", R.style.Theme_AutoTask_Green, "#2E7D32"),
        ORANGE("orange", "暖阳", "🟠", R.style.Theme_AutoTask_Orange, "#E65100"),
        RED("red", "赤焰", "🟥", R.style.Theme_AutoTask_Red, "#C62828"),
        PINK("pink", "樱花粉", "🩷", R.style.Theme_AutoTask_Pink, "#D81B60"),
        BROWN("brown", "奶茶棕", "🤎", R.style.Theme_AutoTask_Brown, "#795548"),
        DARK("dark", "墨夜", "⚫", R.style.Theme_AutoTask_Dark, "#CAC4D0");

        companion object {
            fun fromId(id: String): Theme = entries.find { it.id == id } ?: PURPLE
        }
    }

    /**
     * 获取当前主题（必须在 super.onCreate 之前调用 setTheme）
     */
    fun getCurrentTheme(context: Context): Theme {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_THEME, Theme.PURPLE.id) ?: Theme.PURPLE.id
        return Theme.fromId(id)
    }

    /**
     * 保存并应用主题
     */
    fun setTheme(context: Context, theme: Theme) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }

    /**
     * 在 Activity.onCreate 的 super.onCreate 之前调用
     */
    fun applyTheme(activity: Activity) {
        val theme = getCurrentTheme(activity)
        activity.setTheme(theme.resId)
    }

    /**
     * 获取 toolbar 标题颜色（深色主题用浅色字，浅色主题用白字）
     */
    fun getToolbarTitleColor(context: Context): Int {
        val theme = getCurrentTheme(context)
        return if (theme == Theme.DARK) {
            android.graphics.Color.parseColor("#E6E0E9")
        } else {
            android.graphics.Color.WHITE
        }
    }
}
