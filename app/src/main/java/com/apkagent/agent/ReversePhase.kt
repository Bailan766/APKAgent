package com.apkagent.agent

/**
 * 逆向分析阶段状态机
 * 严格按顺序执行，不可跳步
 */
enum class ReversePhase(val label: String, val icon: String) {
    PREPARE("侦察准备", "🔍"),
    STATIC_ANALYSIS("深度静态分析", "🔬"),
    DYNAMIC("动态验证", "🎯"),
    MODIFICATION("智能自动修改", "🔧"),
    REPACK("重打包签名", "📦"),
    DONE("完成", "✅");

    fun next(): ReversePhase = when (this) {
        PREPARE -> STATIC_ANALYSIS
        STATIC_ANALYSIS -> DYNAMIC
        DYNAMIC -> MODIFICATION
        MODIFICATION -> REPACK
        REPACK -> DONE
        DONE -> DONE
    }

    fun displayText(): String = "$icon $label"

    companion object {
        fun fromToolsUsed(toolNames: Set<String>): ReversePhase {
            val hasRepack = toolNames.any { it in listOf("apk_repack", "apk_sign") }
            val hasModify = toolNames.any { it in listOf("smart_patch_apply", "dex_edit", "apk_patch_manifest", "auto_patch") }
            val hasAnalysis = toolNames.any { it in listOf("dex_disasm", "apk_read_manifest", "apk_search_strings", "file_search", "smart_search") }
            val hasInfo = toolNames.any { it in listOf("apk_apk_info", "apk_read_signer", "apk_list_files") }
            return when {
                hasRepack -> DONE
                hasModify -> REPACK
                hasAnalysis -> MODIFICATION
                hasInfo -> STATIC_ANALYSIS
                else -> PREPARE
            }
        }
    }
}
