package com.coke.nestspace.features.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookStoreTest {

    @Test
    fun codecPreservesTextImagesAndOrder() {
        val notes = listOf(
            NotebookNote(
                id = "new",
                title = "新笔记",
                body = "可以放文字和图片",
                images = listOf(NotebookImage("new/image-1.jpg", "示例图片")),
                createdAt = 20,
                updatedAt = 30
            ),
            NotebookNote(
                id = "old",
                title = "旧笔记",
                body = "正文",
                createdAt = 10,
                updatedAt = 15
            )
        )

        val decoded = NotebookCodec.decode(NotebookCodec.encode(notes))

        assertEquals(2, decoded.size)
        assertEquals("新笔记", decoded[0].title)
        assertEquals("示例图片", decoded[0].images.single().label)
        assertEquals("正文", decoded[1].body)
    }

    @Test
    fun malformedJsonFallsBackToEmptyList() {
        assertTrue(NotebookCodec.decode("not json").isEmpty())
    }
}
