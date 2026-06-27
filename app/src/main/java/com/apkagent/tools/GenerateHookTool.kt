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
        val outputFile = File(ctx.workspace, "hooks/${className.substringAfterLast('.')}_hook.js")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(result)

        return ToolResult.ok("✅ Hook 脚本已生成：${outputFile.name}\n\n\`\`\`javascript\n$result\n\`\`\`")
    }

    private fun genJavaHook(cls: String, method: String, ret: String): String {
        val dotCls = cls.replace("/", ".")
        return """
// Java Hook: $dotCls.$method
Java.perform(function() {
    var clazz = Java.use("$dotCls");
    clazz.$method.overloads.forEach(function(overload) {
        overload.implementation = function() {
            console.log("[+] $dotCls.$method called");
            for (var i = 0; i < arguments.length; i++)
                console.log("    arg[" + i + "]: " + arguments[i]);
            console.log("    Stack:\n" + Java.use("android.util.Log").getStackTraceString(
                Java.use("java.lang.Exception").\$new()).split("\n").slice(1,6).join("\n    "));
            var result = this.$method.apply(this, arguments);
            console.log("    Original: " + result);
            return $ret;
        };
    });
    console.log("[+] Hook: $dotCls.$method -> $ret");
});
"""
    }

    private fun genNativeHook(cls: String, method: String): String {
        return """
// Native Hook: $method
Interceptor.attach(Module.findExportByName(null, "$method"), {
    onEnter: function(args) { console.log("[+] Native $method called, arg0=" + args[0]); },
    onLeave: function(retval) { console.log("    retval=" + retval); }
});
"""
    }
}
