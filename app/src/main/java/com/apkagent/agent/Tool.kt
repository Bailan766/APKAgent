package com.apkagent.agent

import android.content.Context
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 工具执行上下文：提供给工具所需的运行环境。
 * - appContext：Android Context
 * - workspace：工作区目录（沙箱根），所有可写文件操作必须在此目录内
 * - openApk：当前用户导入的 APK 文件（可空，动态更新不依赖创建时快照）
 */
class ToolContext(
    val appContext: Context,
    val workspace: File,
    openApk: File?
) {
    @Volatile var openApk: File? = openApk
        private set

    fun updateOpenApk(file: File?) { openApk = file }
}

/**
 * Agent 工具接口。每个工具描述自己 + 执行逻辑。
 * sensitive=true 的工具在执行前需用户在 UI 确认。
 */
interface Tool {
    val name: String
    val description: String
    /** JSON Schema（OpenAI function parameters 格式） */
    val parameters: JsonObject
    /** 是否为敏感操作（写/改/删除），需用户确认 */
    val sensitive: Boolean get() = false
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * 工具调用请求（AI 发起，待执行/待确认）。
 */
data class PendingToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    /** 解析后的参数 JsonObject（解析失败为 null） */
    val parsedArgs: JsonObject?
)

/**
 * 一次已执行的工具调用记录（用于 UI 展示）。
 */
data class ExecutedToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String,
    val success: Boolean,
    val confirmed: Boolean = true
)
