package com.apkagent.agent

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
        RECON("侦察", listOf("apk_apk_info", "apk_read_", "apk_list_", "risk_scan")),
        ANALYSIS("分析", listOf("dex_", "hex_", "apk_search_", "apk_extract_", "file_search",
            "smart_search", "analyze_obfuscation")),
        MODIFY("修改", listOf("smart_patch_", "auto_patch", "apk_unpack", "apk_patch_",
            "dex_edit", "apk_repack", "apk_sign")),
        SCRIPT("脚本", listOf("python_", "pip_", "run_python", "run_node", "generate_frida",
            "generate_hook", "create_script", "run_script")),
        FILE("文件", listOf("file_read", "file_write", "file_list", "ext_file_")),
        SYSTEM("系统", listOf("plugin_", "adb_exec", "shizuku"))
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
            ReversePhase.ANALYZE -> setOf(ToolGroup.ANALYSIS, ToolGroup.FILE, ToolGroup.RECON)
            ReversePhase.MODIFY -> setOf(ToolGroup.MODIFY, ToolGroup.SCRIPT, ToolGroup.FILE)
            ReversePhase.DELIVER -> setOf(ToolGroup.FILE, ToolGroup.SYSTEM)
            ReversePhase.DONE -> ToolGroup.entries.toSet()
        }

        // 基于用户意图的覆盖
        if (userIntent != null) {
            val lower = userIntent.lowercase()
            val extra = mutableSetOf<ToolGroup>()
            if (lower.contains("python") || lower.contains("脚本") || lower.contains("script")) {
                extra.add(ToolGroup.SCRIPT)
            }
            if (lower.contains("hook") || lower.contains("frida") || lower.contains("注入")) {
                extra.add(ToolGroup.SCRIPT)
                extra.add(ToolGroup.MODIFY)
            }
            if (lower.contains("安装") || lower.contains("install")) {
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
            appendLine("当前可用工具分组:")
            for (group in activeGroups) {
                appendLine("  【${group.displayName}】${group.toolPrefixes.joinToString(", ")}")
            }
        }
    }
}
