package com.coke.nestspace.agent.definitions

import com.coke.nestspace.agent.tools.screenReaderToolNames
import com.coke.nestspace.agent.sdk.AgentCatalog
import com.coke.nestspace.agent.sdk.AgentDefinition
import com.coke.nestspace.agent.sdk.AgentInstructionProvider
import com.coke.nestspace.agent.sdk.AgentKey
import com.coke.nestspace.agent.sdk.AgentLane
import com.coke.nestspace.agent.sdk.AgentRunContext
import com.coke.nestspace.agent.sdk.AgentToolPolicy
import com.coke.nestspace.core.i18n.AppLanguage

internal object NestSpaceAgents {
    val mainKey = AgentKey("main")
    val workerKey = AgentKey("worker")
    val screenReaderKey = AgentKey("screen-reader")

    private val mainLane = AgentLane("main")
    private val workerLane = AgentLane("worker")
    private val screenReaderLane = AgentLane("screen-reader")

    private val taskControlTools = setOf(
        "agent_task_list",
        "agent_task_status",
        "agent_task_supplement",
        "agent_task_pause",
        "agent_task_resume",
        "agent_task_cancel",
        "agent_task_cancel_queued"
    )

    val main = AgentDefinition(
        key = mainKey,
        lane = mainLane,
        displayName = "main",
        toolPolicy = AgentToolPolicy(
            denied = screenReaderToolNames
        )
    )

    val worker = AgentDefinition(
        key = workerKey,
        lane = workerLane,
        displayName = "worker",
        toolPolicy = AgentToolPolicy(
            denied = taskControlTools +
                screenReaderToolNames +
                "spawn_agent"
        )
    )

    val screenReader = AgentDefinition(
        key = screenReaderKey,
        lane = screenReaderLane,
        displayName = "screen-reader",
        toolPolicy = AgentToolPolicy(
            allowed = screenReaderToolNames + "spawn_agent"
        ),
        instructions = AgentInstructionProvider { _ ->
            screenReaderInstructions()
        }
    )

    private val catalog = AgentCatalog(listOf(main, worker, screenReader))

    fun require(key: AgentKey): AgentDefinition = catalog.require(key)

    private fun screenReaderInstructions(): String = AppLanguage.tr(
        "你是独立的读屏 Agent。读取当前容器页面并解释屏幕，" +
            "不要直接点击、输入或导航。先调用 screen_read。" +
            "根据页面和用户要求推断意图；意图不明确时给出 2 到 4 个选项询问。" +
            "需要定位时调用 screen_highlight。若用户明确要求执行耗时操作，" +
            "可以调用 spawn_agent 创建 Worker，并说明已交给后台 Worker。" +
            "分析完成后调用 screen_suggest 注册一个推荐动作和最多三个替代动作；" +
            "类型只能使用 explain、extract、compare、navigate、translate、" +
            "search、operate、open_app；" +
            "按钮文字可以具体到当前页面，但不能超出这些已实现能力。" +
            "必须根据页面内容判断下一步：有任务列表时推荐提取待办；" +
            "有多个选项时推荐比较；有问题时推荐回答或搜索；有外文时推荐翻译；" +
            "不要总是推荐“解释当前页面”。" +
            "\n\n用户请求：\n",
        "You are the independent screen-reader agent. Read and explain the " +
            "current container screen, but do not directly tap, type or navigate. " +
            "Call screen_read first. Infer intent from the screen and request; " +
            "offer two to four choices when ambiguous. Use screen_highlight when " +
            "locating content. If the user explicitly asks for a long-running page " +
            "operation, create a Worker with spawn_agent and report that it was " +
            "delegated. After the analysis, call screen_suggest with one " +
            "recommended action and up to three alternatives. Types must be " +
            "one of explain, extract, compare, navigate, translate, search, " +
            "operate or open_app. Infer the next useful action from page content: " +
            "recommend extracting a task list, comparing options, answering or " +
            "searching a question, or translating foreign text instead of always " +
            "recommending a generic explanation. Labels may " +
            "be specific to this screen but must stay within those implemented " +
            "capabilities.\n\nUser request:\n"
    )
}
