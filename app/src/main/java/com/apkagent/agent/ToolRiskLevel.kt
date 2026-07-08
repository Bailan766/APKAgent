package com.apkagent.agent

/**
 * 工具安全风险等级。
 * SAFE    — 只读操作，可自动执行
 * CAUTION — 可能访问敏感内容或产生中等副作用，建议确认
 * DANGER  — 写入、安装、签名、patch、重打包等强副作用操作，必须确认
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
