package com.coke.nestspace.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {

    @Test
    fun removesSpacesBetweenChineseTokens() {
        assertEquals(
            "打开应用把重要内容的标题发我",
            normalizeSpeechText("打 开 应 用 把 重 要 内 容 的 标 题 发 我")
        )
    }

    @Test
    fun removesSpacesAroundChinesePunctuationAndNumbers() {
        assertEquals(
            "打开应用，看看第1个结果",
            normalizeSpeechText("打 开 应 用 ， 看 看 第 1 个 结 果")
        )
    }

    @Test
    fun keepsNormalEnglishWordSpacing() {
        assertEquals(
            "open the app and search",
            normalizeSpeechText("open the  app and search")
        )
    }
}
