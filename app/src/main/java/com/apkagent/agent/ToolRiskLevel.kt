package com.apkagent.agent

/**
 * 工具安全风险等级。
 * SAFE    — 只读操作，可自动执行
 * CAUTION — 写操作，需要用户确认（可通过设置跳过）
 * DANGER  — 不可逆操作（删除、签名、安装），始终确认
 */
enum class ToolRiskLevel {
    SAFE, CAUTION, DANGER;

    companion object {
        /** 根据工具名自动推断风险等级 */
        fun infer(toolName: String): ToolRiskLevel {
            val lower = toolName.lowercase()
            return when {
                // 危险：删除、签名、安装、重打包
                lower.contains("delete") || lower.contains("remove") ||
                lower.contains("sign") || lower.contains("install") ||
                lower.contains("repack") || lower.contains("write") ||
                lower.contains("edit") || lower.contains("replace") ||
                lower.contains("patch") || lower.contains("hook") ||
                lower.contains("assemble") -> DANGER

                // 谨慎：读取文件、搜索（可能读敏感内容）
                lower.contains("read") || lower.contains("search") ||
                lower.contains("list") || lower.contains("info") ||
                lower.contains("disasm") || lower.contains("decode") ||
                lower.contains("view") || lower.contains("dump") ||
                lower.contains("search_strings") ||
                lower.contains("obfuscation") -> CAUTION

                // 安全：其他（纯分析、无副作用）
                else -> SAFE
            }
        }
    }
}
