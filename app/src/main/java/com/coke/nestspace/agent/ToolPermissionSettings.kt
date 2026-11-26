package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.agent.sdk.AgentApprovalCategory
import com.coke.nestspace.agent.sdk.AgentApprovalLevel
import com.coke.nestspace.agent.sdk.AgentApprovalSettings
import com.coke.nestspace.core.i18n.AppLanguage

typealias ToolPermissionLevel = AgentApprovalLevel
typealias ToolConfirmationCategory = AgentApprovalCategory

val ToolPermissionLevel.label: String
    get() = when (this) {
        ToolPermissionLevel.STRICT -> AppLanguage.tr("严格", "Strict")
        ToolPermissionLevel.STANDARD -> AppLanguage.tr("标准", "Standard")
        ToolPermissionLevel.LOOSE -> AppLanguage.tr("宽松", "Loose")
        ToolPermissionLevel.CUSTOM -> AppLanguage.tr("自定义", "Custom")
        ToolPermissionLevel.DEVELOPER -> AppLanguage.tr("开发", "Developer")
    }

val ToolPermissionLevel.description: String
    get() = when (this) {
        ToolPermissionLevel.STRICT -> AppLanguage.tr(
            "所有敏感操作都需要确认：安装/卸载、权限变更、风险点击和下载等。",
            "Confirm every sensitive action: install/uninstall, permission changes, " +
                "risky taps and downloads."
        )
        ToolPermissionLevel.STANDARD -> AppLanguage.tr(
            "在宽松模式基础上增加应用安装/卸载确认，其他操作直接执行。",
            "Loose mode plus confirmation for app install/uninstall; other actions run directly."
        )
        ToolPermissionLevel.LOOSE -> AppLanguage.tr(
            "只有支付、下单、转账、红包等涉及金钱的操作才提示。",
            "Only prompt for money-related actions such as payment, checkout, " +
                "transfers and red packets."
        )
        ToolPermissionLevel.CUSTOM -> AppLanguage.tr(
            "由你选择哪些操作需要确认。",
            "Choose which actions need confirmation."
        )
        ToolPermissionLevel.DEVELOPER -> AppLanguage.tr(
            "所有确认均关闭，适合临时调试和自动化开发。",
            "Disable every confirmation for temporary debugging and automation development."
        )
    }

val ToolConfirmationCategory.label: String
    get() = when (this) {
        ToolConfirmationCategory.INSTALL -> AppLanguage.tr("安装/卸载软件", "Install/uninstall")
        ToolConfirmationCategory.PERMISSION -> AppLanguage.tr("权限变更", "Permission changes")
        ToolConfirmationCategory.DOWNLOAD -> AppLanguage.tr("文件下载", "File downloads")
        ToolConfirmationCategory.RISKY -> AppLanguage.tr("风险点击", "Risky taps")
        ToolConfirmationCategory.MONEY -> AppLanguage.tr("金钱操作", "Money actions")
        ToolConfirmationCategory.EXTERNAL -> AppLanguage.tr("外部 MCP 工具", "External MCP tools")
        ToolConfirmationCategory.PLUGIN -> AppLanguage.tr("插件工具", "Plugin tools")
    }

val ToolConfirmationCategory.description: String
    get() = when (this) {
        ToolConfirmationCategory.INSTALL -> AppLanguage.tr(
            "安装或卸载虚拟应用，以及点击界面中的安装/卸载按钮。",
            "Install or uninstall virtual apps and tap install/uninstall buttons in apps."
        )
        ToolConfirmationCategory.PERMISSION -> AppLanguage.tr(
            "授予或撤销虚拟应用的运行时权限。",
            "Grant or revoke runtime permissions for virtual apps."
        )
        ToolConfirmationCategory.DOWNLOAD -> AppLanguage.tr(
            "浏览器下载文件。",
            "Download files from the browser."
        )
        ToolConfirmationCategory.RISKY -> AppLanguage.tr(
            "发送、删除等不可逆的界面操作。",
            "Irreversible UI actions such as send or delete."
        )
        ToolConfirmationCategory.MONEY -> AppLanguage.tr(
            "支付、下单、转账、红包、打赏、充值等操作。",
            "Payment, checkout, transfers, red packets, tips and top-ups."
        )
        ToolConfirmationCategory.EXTERNAL -> AppLanguage.tr(
            "调用你配置的外部 MCP Server 工具；这些工具可能执行真实操作。",
            "Call tools from your external MCP servers; these tools may perform real actions."
        )
        ToolConfirmationCategory.PLUGIN -> AppLanguage.tr(
            "调用插件提供的工具；插件可能访问网络、文件或执行真实操作。",
            "Call tools provided by plugins; plugins may access the network, files or " +
                "perform real actions."
        )
    }

object ToolPermissionSettings {
    private const val PREFS = "tool_permissions"
    private const val KEY_LEVEL = "level"
    private const val KEY_CUSTOM = "custom_categories"

    fun load(context: Context): ToolPermissionLevel =
        AgentApprovalLevel.from(prefs(context).getString(KEY_LEVEL, null))

    fun save(context: Context, level: ToolPermissionLevel) {
        prefs(context).edit().putString(KEY_LEVEL, level.storageValue).apply()
    }

    fun customCategories(context: Context): Set<ToolConfirmationCategory> {
        val stored = prefs(context).getString(KEY_CUSTOM, null) ?: return allCategories()
        if (stored.isBlank()) return emptySet()
        return stored.split(',').mapNotNull(AgentApprovalCategory::from).toSet()
    }

    fun saveCustomCategories(
        context: Context,
        categories: Set<ToolConfirmationCategory>
    ) {
        val value = ToolConfirmationCategory.entries
            .filter { it in categories }
            .joinToString(",") { it.api }
        prefs(context).edit().putString(KEY_CUSTOM, value).apply()
    }

    fun enabledCategories(context: Context): Set<ToolConfirmationCategory> =
        settings(context).enabledCategories()

    fun shouldConfirm(
        context: Context,
        category: ToolConfirmationCategory
    ): Boolean = settings(context).requiresConfirmation(category)

    fun requiresReviewForLearning(context: Context): Boolean =
        settings(context).requiresLearningReview()

    fun parse(value: String): ToolPermissionLevel? =
        ToolPermissionLevel.entries.firstOrNull {
            it.storageValue == value.trim().lowercase()
        }

    fun parseCategory(value: String): ToolConfirmationCategory? =
        AgentApprovalCategory.from(value)

    fun allCategories(): Set<ToolConfirmationCategory> =
        ToolConfirmationCategory.entries.toSet()

    private fun settings(context: Context): AgentApprovalSettings =
        AgentApprovalSettings(
            level = load(context),
            customCategories = customCategories(context)
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
