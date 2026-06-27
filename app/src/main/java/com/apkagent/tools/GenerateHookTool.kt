package com.apkagent.tools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Frida Hook 脚本生成工具
 * 根据类名/方法名自动生成 Hook 脚本
 */
class GenerateHookTool : Tool {
    override val name = "generate_hook"
    override val description = """生成 Frida Hook 脚本：根据类名和方法名自动生成 Java/Native 层 Hook 脚本。
参数：className（完整类名如 com.app.CheckUtils）、methodName（方法名）、hookType（java/native/both，默认 java）
返回可直接使用的 Frida JS 脚本。"""
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "className" to mapOf("type" to "string", "description" to "完整类名（如 com.app.CheckUtils）"),
            "methodName" to mapOf("type" to "string", "description" to "方法名（如 isRooted）"),
            "hookType" to mapOf("type" to "string", "description" to "Hook 类型：java/native/both", "default" to "java"),
            "returnValue" to mapOf("type" to "string", "description" to "强制返回值（如 true/false/0/null）", "default" to "false")
        ),
        "required" to listOf("className", "methodName")
    )
    override val sensitive = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val className = args["className"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 className")
        val methodName = args["methodName"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.err("缺少 methodName")
        val hookType = args["hookType"]?.jsonPrimitive?.contentOrNull ?: "java"
        val returnValue = args["returnValue"]?.jsonPrimitive?.contentOrNull ?: "false"

        val scripts = mutableListOf<String>()

        if (hookType in listOf("java", "both")) {
            scripts.add(generateJavaHook(className, methodName, returnValue))
        }
        if (hookType in listOf("native", "both")) {
            scripts.add(generateNativeHook(className, methodName))
        }

        val result = scripts.joinToString("\n\n// ═══════════════════════════════\n\n")
        
        // 保存到工作区
        val outputFile = File(ctx.workspace, "hooks/${className.substringAfterLast('.')}_hook.js")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(result)

        return ToolResult.ok("✅ Hook 脚本已生成：${outputFile.name}\n\n```javascript\n$result\n```")
    }

    private fun generateJavaHook(className: String, methodName: String, returnValue: String): String {
        val dotClass = className.replace("/", ".")
        val retType = when (returnValue) {
            "true", "false" -> "boolean"
            "null" -> "Object"
            else -> if (returnValue.all { it.isDigit() }) "int" else "String"
        }
        val retValue = when (returnValue) {
            "true" -> "true"
            "false" -> "false"
            "null" -> "null"
            else -> if (returnValue.all { it.isDigit() }) returnValue else "\"$returnValue\""
        }
        return """
// Java 层 Hook: $dotClass.$methodName
Java.perform(function() {
    var clazz = Java.use("$dotClass");
    clazz.$methodName.implementation = function() {
        console.log("[+] ${className.substringAfterLast('.')}.$methodName called");
        console.log("    Args: " + Array.from(arguments).join(", "));
        
        // 打印调用栈
        console.log("    Backtrace:\n" + 
            Java.use("android.util.Log").getStackTraceString(
                Java.use("java.lang.Exception").\$new()
            ).split("\n").slice(1, 6).join("\n    "));

        // 强制返回值
        var result = $retValue;
        console.log("    Return: " + result);
        return result;
    };
    console.log("[+] Hook installed: $dotClass.$methodName -> return $retValue");
});
"""
    }

    private fun generateNativeHook(className: String, methodName: String): String {
        return """
// Native 层 Hook (Interceptor): $className.$methodName
// 注意：需要知道 native 函数的地址或导出名
Interceptor.attach(Module.findExportByName(null, "$methodName"), {
    onEnter: function(args) {
        console.log("[+] Native $methodName called");
        console.log("    arg0: " + args[0]);
        console.log("    arg1: " + args[1]);
    },
    onLeave: function(retval) {
        console.log("    retval: " + retval);
        // retval.replace(ptr(0x0));  // 取消注释以强制返回 0
    }
});
"""
    }
}
