package com.coke.nestspace.agent.sdk

object TokenEstimator {
    fun estimateRequest(request: AgentChatInput): Int =
        estimateText(request.system) +
            request.tools.sumOf { tool ->
                estimateText(tool.name) +
                    estimateText(tool.description) +
                    estimateText(tool.inputSchema.toString())
            } +
            estimateMessages(request.messages)

    fun estimateResponse(response: AgentChatResponse): Int =
        response.blocks.sumOf { block ->
            when (block) {
                is AgentChatBlock.Text -> estimateText(block.text)
                is AgentChatBlock.ToolUse ->
                    estimateText(block.name) + estimateText(block.input.toString())
                is AgentChatBlock.Thinking -> estimateText(block.thinking)
                is AgentChatBlock.RedactedThinking -> estimateText(block.data)
                is AgentChatBlock.ToolResult, is AgentChatBlock.Image -> 0
            }
        }

    fun estimateMessages(messages: List<AgentChatMessage>): Int =
        messages.sumOf { message ->
            message.blocks.sumOf { block ->
                when (block) {
                    is AgentChatBlock.Text -> 4 + estimateText(block.text)
                    is AgentChatBlock.ToolUse ->
                        8 + estimateText(block.name) + estimateText(block.id) +
                            estimateText(block.input.toString())
                    is AgentChatBlock.ToolResult ->
                        8 + estimateText(block.content)
                    is AgentChatBlock.Thinking -> 8 + estimateText(block.thinking)
                    is AgentChatBlock.RedactedThinking -> 8 + estimateText(block.data)
                    is AgentChatBlock.Image -> 8 + (block.data.length / 4)
                }
            } + 4
        }

    /**
     * Conservative estimate: CJK and other wide characters count as one
     * token, ASCII counts as roughly four characters per token.
     */
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            if (isWide(ch)) wide++ else narrow++
        }
        return wide + (narrow + 3) / 4 + 1
    }

    private fun isWide(ch: Char): Boolean =
        ch.code in 0x2E80..0x9FFF ||
            ch.code in 0xAC00..0xD7AF ||
            ch.code in 0xF900..0xFAFF ||
            ch.code in 0xFF00..0xFFEF
}
