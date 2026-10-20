package com.autotask.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTermsTest {

    @Test
    fun `strips button suffix and number prefix`() {
        assertEquals(listOf("数字1按钮", "数字1", "1"), SearchTerms.build("数字1按钮"))
    }

    @Test
    fun `maps plus button to symbol aliases`() {
        val terms = SearchTerms.build("加号按钮")
        assertTrue(terms.contains("加号"))
        assertTrue(terms.contains("加"))
        assertTrue(terms.contains("+"))
    }

    @Test
    fun `maps equals button to chinese and symbol`() {
        val terms = SearchTerms.build("等号按钮")
        assertTrue(terms.contains("等于"))
        assertTrue(terms.contains("="))
    }

    @Test
    fun `keeps plain text unchanged`() {
        assertEquals(listOf("设置"), SearchTerms.build("设置"))
    }

    @Test
    fun `does not strip prefix from normal words`() {
        // "数值"不能被误剪成"值"
        assertEquals(listOf("数值"), SearchTerms.build("数值"))
    }

    @Test
    fun `decimal point aliases`() {
        val terms = SearchTerms.build("小数点按钮")
        assertTrue(terms.contains("小数点"))
        assertTrue(terms.contains("."))
    }
}
