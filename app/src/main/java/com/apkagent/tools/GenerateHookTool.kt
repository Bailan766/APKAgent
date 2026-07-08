package com.apkagent.tools

import com.apkagent.agent.*
import kotlinx.serialization.json.*
import java.io.File

class GenerateHookTool : Tool {
    override val name = "generate_hook"
    override val description = "生成 Frida Hook 脚本：根据类名和方法名自动生成 Java/Native 层 Hook 脚本。参数：className（完整类名）、methodName（方法名）、hookType（java/native/both）、returnValue（强制返回值）。"
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "className" to strProp("完整类名（如 com.app.CheckUtils）"),
            "methodName" to strProp("方法名（如 isRooted）"),
            "hookType" to strProp("Hook 类型", listOf("java", "native", "both")),
            "returnValue" to strProp("强制返回值（如 true/false/0/null）")
        ),
        required = listOf("className", "methodName")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val className = args["className"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.err("缺少 className")
        val methodName = args["methodName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.err("缺少 methodName")
        val hookType = args["hookType"]?.jsonPrimitive?.contentOrNull ?: "java"
        val returnValue = args["returnValue"]?.jsonPrimitive?.contentOrNull ?: "false"

        val scripts = mutableListOf<String>()
        if (hookType in listOf("java", "both")) scripts.add(genJavaHook(className, methodName, returnValue))
        if (hookType in listOf("native", "both")) scripts.add(genNativeHook(className, methodName))

        val result = scripts.joinToString("\n\n// ═══════════════════════════════\n\n")
        val simpleName = className.substringAfterLast(".")
        val outputFile = File(ctx.workspace, "hooks/${simpleName}_hook.js")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(result)

        return ToolResult.ok("✅ Hook 脚本已生成：${outputFile.name}\n\n```javascript\n$result\n```")
    }

    private fun genJavaHook(cls: String, method: String, ret: String): String {
        val dotCls = cls.replace("/", ".")
        val d = "$" // literal dollar sign for JavaScript
        val sb = StringBuilder()
        sb.appendLine("// Java Hook: $dotCls.$method")
        sb.appendLine("Java.perform(function() {")
        sb.appendLine("    var clazz = Java.use(\"$dotCls\");")
        sb.appendLine("    clazz.${d}${method}.overloads.forEach(function(overload) {")
        sb.appendLine("        overload.implementation = function() {")
        sb.appendLine("            console.log(\"[+] $dotCls.${d}${method} called\");")
        sb.appendLine("            for (var i = 0; i < arguments.length; i++)")
        sb.appendLine("                console.log(\"    arg[\" + i + \"]: \" + arguments[i]);")
        sb.appendLine("            console.log(\"    Stack:\\n\" + Java.use(\"android.util.Log\").getStackTraceString(")
        sb.appendLine("                Java.use(\"java.lang.Exception\").${d}new()).split(\"\\n\").slice(1,6).join(\"\\n    \"));")
        sb.appendLine("            var result = this.${d}${method}.apply(this, arguments);")
        sb.appendLine("            console.log(\"    Original: \" + result);")
        sb.appendLine("            return $ret;")
        sb.appendLine("        };")
        sb.appendLine("    });")
        sb.appendLine("    console.log(\"[+] Hook: $dotCls.${d}${method} -> $ret\");")
        sb.appendLine("});")
        return sb.toString()
    }

    private fun genNativeHook(cls: String, method: String): String {
        val sb = StringBuilder()
        sb.appendLine("// Native Hook: $method")
        sb.appendLine("Interceptor.attach(Module.findExportByName(null, \"$method\"), {")
        sb.appendLine("    onEnter: function(args) { console.log(\"[+] Native $method called, arg0=\" + args[0]); },")
        sb.appendLine("    onLeave: function(retval) { console.log(\"    retval=\" + retval); }")
        sb.appendLine("});")
        return sb.toString()
    }
}
