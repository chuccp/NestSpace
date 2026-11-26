package com.coke.nestspace.agent.sdk.knowledge

import com.coke.nestspace.agent.sdk.AgentCapability
import com.coke.nestspace.agent.sdk.AgentTool

sealed interface KnowledgeTarget {
    data class App(val packageName: String) : KnowledgeTarget
    data class Website(val host: String) : KnowledgeTarget
}

class KnowledgeCapability(
    private val index: KnowledgeIndex
) : AgentCapability {
    override val id: String = "knowledge"
    override val description: String =
        "Match app or website guides and attach them when a tool first reaches a target."

    override fun tools(): List<AgentTool> = emptyList()

    fun resolve(target: KnowledgeTarget): KnowledgeEntry? = when (target) {
        is KnowledgeTarget.App -> index.forPackage(target.packageName)
        is KnowledgeTarget.Website -> index.forHost(target.host)
    }

    fun attachGuide(
        resultText: String,
        target: KnowledgeTarget,
        sourceLabel: String = "Knowledge guide"
    ): String = resolve(target)
        ?.withGuide(resultText, sourceLabel)
        ?: resultText

    fun all(): List<KnowledgeEntry> = index.all()
}
