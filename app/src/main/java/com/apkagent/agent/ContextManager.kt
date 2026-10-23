package com.apkagent.agent

import com.apkagent.i18n.L

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具分组与上下文感知管理。
 * 根据当前逆向阶段自动选择最相关的工具子集，
 * 减少 token 消耗，提高工具调用准确率。
 */
object ContextManager {
    private const val TAG = "ContextMgr"

    /**
     * 工具分组定义
     */
    enum class ToolGroup(val displayName: String, val toolPrefixes: List<String>) {
        RECON(L("侦察", "Recon"), listOf("apk_apk_info", "apk_read_", "apk_list_", "risk_scan")),
        ANALYSIS(L("分析", "Analyze"), listOf("dex_", "hex_", "apk_search_", "apk_extract_", "file_search",
            "smart_search", "analyze_obfuscation")),
        MODIFY(L("修改", "Modify"), listOf("smart_patch_", "auto_patch", "apk_unpack", "apk_patch_",
            "dex_edit", "apk_repack", "apk_sign")),
        SCRIPT(L("脚本", "Script"), listOf("python_", "pip_", "run_python", "run_node", "generate_frida",
            "generate_hook", "create_script", "run_script")),
        FILE(L("文件", "File"), listOf("file_read", "file_write", "file_list", "ext_file_")),
        SYSTEM(L("系统", "System"), listOf("plugin_", "adb_exec", "shizuku"))
    }

    /**
     * 根据当前阶段和用户意图，返回应启用的工具分组。
     */
    fun selectToolGroups(
        phase: ReversePhase,
        userIntent: String? = null
    ): Set<ToolGroup> {
        // 基于阶段的默认分组
        val base = when (phase) {
            ReversePhase.PREPARE -> setOf(ToolGroup.RECON, ToolGroup.FILE)
            ReversePhase.STATIC_ANALYSIS -> setOf(ToolGroup.ANALYSIS, ToolGroup.FILE, ToolGroup.RECON)
            ReversePhase.DYNAMIC -> setOf(ToolGroup.SCRIPT, ToolGroup.ANALYSIS)
            ReversePhase.MODIFICATION -> setOf(ToolGroup.MODIFY, ToolGroup.SCRIPT, ToolGroup.FILE)
            ReversePhase.REPACK -> setOf(ToolGroup.MODIFY, ToolGroup.SYSTEM)
            ReversePhase.DONE -> ToolGroup.entries.toSet()
        }

        // 基于用户意图的覆盖
        if (userIntent != null) {
            val lower = userIntent.lowercase()
            val extra = mutableSetOf<ToolGroup>()
            if (lower.contains("python") || lower.contains(L("脚本", "Script")) || lower.contains("script")) {
                extra.add(ToolGroup.SCRIPT)
            }
            if (lower.contains("hook") || lower.contains("frida") || lower.contains(L("注入", "Injection"))) {
                extra.add(ToolGroup.SCRIPT)
                extra.add(ToolGroup.MODIFY)
            }
            if (lower.contains(L("安装", "Install")) || lower.contains("install")) {
                extra.add(ToolGroup.SYSTEM)
            }
            return base + extra
        }

        return base
    }

    /**
     * 过滤工具列表，只保留当前分组内的工具。
     */
    fun filterTools(
        allTools: Map<String, ToolDef>,
        activeGroups: Set<ToolGroup>
    ): Map<String, ToolDef> {
        val prefixes = activeGroups.flatMap { it.toolPrefixes }
        return allTools.filter { (name, _) ->
            prefixes.any { prefix -> name.startsWith(prefix) }
        }
    }

    /**
     * 为 AgentLoop 生成精简的工具描述（只包含活跃分组的工具）。
     */
    fun buildToolSummary(activeGroups: Set<ToolGroup>): String {
        return buildString {
            appendLine(L("当前可用工具分组:", "Grouping of currently available tools:"))
            for (group in activeGroups) {
                appendLine("  【${group.displayName}】${group.toolPrefixes.joinToString(", ")}")
            }
        }
    }
}
