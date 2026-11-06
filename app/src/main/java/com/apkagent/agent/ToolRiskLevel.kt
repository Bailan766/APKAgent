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
        /**
         * 精确的 DANGER 工具名集合。
         * 写入、签名、安装、重打包、patch、hook、导出等强副作用操作。
         */
        private val DANGER_TOOLS = setOf(
            "apk_repack", "apk_sign", "apk_install", "apk_export",
            "apk_write_file", "apk_edit_file", "apk_replace_file",
            "apk_delete_file", "apk_remove_file",
            "apk_patch", "apk_auto_patch", "smart_patch",
            "smali_assemble", "smali_edit", "smali_write",
            "generate_hook", "write_hook", "apply_hook",
            "python_exec", "shell_exec", "adb_exec",
            "file_write", "file_edit", "file_delete",
            "zip_align_write", "repack_apk",
            "frida_inject", "frida_spawn"
        )

        /**
         * 精确的 CAUTION 工具名集合。
         * 读取敏感内容、反汇编、解码等可能有副作用的只读操作。
         */
        private val CAUTION_TOOLS = setOf(
            "apk_read_file", "read_file", "search_strings",
            "search_code", "list_files", "file_info",
            "apk_info", "dex_disasm", "smali_disasm",
            "axml_decode", "arsc_decode", "manifest_decode",
            "apk_dump", "dump_dex", "dump_strings",
            "read_signer", "apk_read_signer",
            "scan_risk", "risk_scan", "check_root",
            "list_permissions", "view_config",
            "obfuscation_detect"
        )

        /** 根据工具名自动推断风险等级 */
        fun infer(toolName: String): ToolRiskLevel {
            val lower = toolName.lowercase().trim()

            // 先精确匹配
            if (lower in DANGER_TOOLS) return DANGER
            if (lower in CAUTION_TOOLS) return CAUTION

            // 关键字兜底（仅用于未在精确集合中注册的工具）
            return when {
                // 明确的写入/破坏性关键字 → DANGER
                lower.contains("delete") || lower.contains("remove") ||
                lower.contains("repack") || lower.contains("assemble") ||
                lower.contains("patch") || lower.contains("hook") ||
                lower.contains("inject") || lower.contains("export") ||
                lower.contains("install") || lower.contains("write") ||
                lower.contains("edit") || lower.contains("replace") ||
                lower.contains("sign") || lower.contains("exec") ||
                lower.contains("spawn") -> DANGER

                // 读取/分析类关键字 → CAUTION
                lower.contains("read") || lower.contains("search") ||
                lower.contains("list") || lower.contains("info") ||
                lower.contains("disasm") || lower.contains("decode") ||
                lower.contains("view") || lower.contains("dump") ||
                lower.contains("scan") || lower.contains("detect") -> CAUTION

                // 未知工具保守按 CAUTION，不再默认 SAFE
                else -> CAUTION
            }
        }
    }
}
