package com.autotask.util

/**
 * 把 AI/用户描述转换成无障碍节点的查找候选词。
 * 独立成纯 Kotlin 工具，便于单元测试。
 */
object SearchTerms {

    private val SUFFIX_REGEX = Regex("(按钮|图标|输入框|输入|链接|选项|文字|区域|入口|标签|卡片|菜单|列表|标题|Tab|tab|页签)$")

    // "数字1按钮"这类描述去掉前缀后才能对上节点文本 "1"
    private val PREFIX_REGEX = Regex("^(数字|文字|按键|按钮|图标|选项|菜单|标签|输入框)")

    // AI 常把符号按钮描述成"加号/等号"，节点上却是"加/等于"或符号本身
    private val SYMBOL_ALIASES = mapOf(
        "加号" to listOf("加", "+"),
        "等号" to listOf("等于", "="),
        "减号" to listOf("减", "-"),
        "乘号" to listOf("乘", "×", "*"),
        "除号" to listOf("除", "÷"),
        "小数点" to listOf(".", "点")
    )

    /**
     * 查找候选顺序：原文 → 去通用后缀 → 去"数字/按钮"前缀 → 常见符号同义词
     */
    fun stripSuffix(text: String): String = text.replace(SUFFIX_REGEX, "").trim()

    fun build(text: String): List<String> {
        val terms = LinkedHashSet<String>()
        terms.add(text)

        val stripped = text.replace(SUFFIX_REGEX, "").trim()
        if (stripped.isNotEmpty()) terms.add(stripped)

        val noPrefix = stripped.replace(PREFIX_REGEX, "").trim()
        if (noPrefix.isNotEmpty()) terms.add(noPrefix)

        SYMBOL_ALIASES[stripped]?.let { terms.addAll(it) }
        SYMBOL_ALIASES[noPrefix]?.let { terms.addAll(it) }
        return terms.toList()
    }
}
