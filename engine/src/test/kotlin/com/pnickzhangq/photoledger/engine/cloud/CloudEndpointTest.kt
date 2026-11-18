package com.pnickzhangq.photoledger.engine.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudEndpointTest {

    @Test
    fun `裸 host 补 v1 chat completions`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            CloudEndpoint.chatCompletionsUrl("https://api.example.com"),
        )
    }

    @Test
    fun `以 v1 结尾只补 chat completions`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            CloudEndpoint.chatCompletionsUrl("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            CloudEndpoint.chatCompletionsUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun `完整端点原样使用`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            CloudEndpoint.chatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `拒绝明文 http`() {
        assertFailsWith<IllegalArgumentException> {
            CloudEndpoint.chatCompletionsUrl("http://api.example.com/v1")
        }
    }
}
