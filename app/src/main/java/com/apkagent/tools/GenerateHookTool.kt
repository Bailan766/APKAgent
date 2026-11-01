package com.apkagent.tools

import com.apkagent.i18n.L
import com.apkagent.agent.*
import kotlinx.serialization.json.*

/**
 * Frida Hook 脚本生成器 — 自动生成针对特定方法或类的 Frida Java/Native Hook 代码
 *
 * 修复说明：
 * 修复原实现在普通方法名前错误添加 `$` 前缀（如 `clazz.$isRooted`）导致 Frida 运行时报 TypeError 的 Bug。
 * 仅对构造函数 `<init>` 映射为 `$init`，其余方法统一使用 `clazz["methodName"]` 安全索引语法。
 */
object GenerateHookTool : Tool {
    override val name = "generate_hook"
    override val description get() = L(
        "自动生成 Frida Hook 脚本（打印参数/返回值、修改返回值、绕过检测）",
        "Automatically generate Frida Hook scripts (log arguments/return values, modify return values, bypass checks)"
    )

    override val parameters get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target_class") {
                put("type", "string")
                put("description", L("目标类名（完整包名，如 com.example.Auth）", "Target class name (full package name, e.g. com.example.Auth)"))
            }
            putJsonObject("target_method") {
                put("type", "string")
                put("description", L("目标方法名（可选，留空则 Hook 所有方法）", "Target method name (optional; leave empty to hook all methods)"))
            }
            putJsonObject("hook_type") {
                put("type", "string")
                put("description", L("Hook 类型", "Hook type"))
                putJsonArray("enum") {
                    add("log")
                    add("modify_return")
                    add("bypass")
                    add("trace_all")
                }
            }
            putJsonObject("return_value") {
                put("type", "string")
                put("description", L("修改后的返回值（hook_type=modify_return 时必填，如 true, false, 1, \"vip\"）", "Modified return value (required when hook_type=modify_return, e.g., true, false, 1, \"vip\")"))
            }
        }
        putJsonArray("required") {
            add("target_class")
            add("hook_type")
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val targetClass = args["target_class"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult(false, L("缺少 target_class 参数", "Missing target_class parameter"))
        val rawMethod = args["target_method"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val hookType = args["hook_type"]?.jsonPrimitive?.contentOrNull ?: "log"
        val returnValue = args["return_value"]?.jsonPrimitive?.contentOrNull

        val fridaMethod = when (rawMethod) {
            "<init>", "init" -> "\$init"
            else -> rawMethod
        }

        val script = when (hookType) {
            "log" -> if (fridaMethod != null) {
                """
                Java.perform(function() {
                    var clazz = Java.use("$targetClass");
                    var methodName = "$fridaMethod";
                    var target = clazz[methodName];
                    if (!target) {
                        console.log("[!] 未找到方法: $targetClass." + methodName);
                        return;
                    }
                    target.overloads.forEach(function(overload) {
                        overload.implementation = function() {
                            console.log("\n[+] 调用 $targetClass." + methodName);
                            for (var i = 0; i < arguments.length; i++) {
                                console.log("  参数[" + i + "]: " + arguments[i]);
                            }
                            var retval = this[methodName].apply(this, arguments);
                            console.log("  返回值: " + retval);
                            return retval;
                        };
                    });
                    console.log("[*] Hook 成功: $targetClass." + methodName);
                });
                """.trimIndent()
            } else {
                generateTraceAll(targetClass)
            }

            "modify_return" -> {
                if (fridaMethod == null || returnValue == null) {
                    return ToolResult(false, L("modify_return 需要 target_method 和 return_value 参数", "modify_return requires target_method and return_value parameters"))
                }
                """
                Java.perform(function() {
                    var clazz = Java.use("$targetClass");
                    var methodName = "$fridaMethod";
                    var target = clazz[methodName];
                    if (!target) {
                        console.log("[!] 未找到方法: $targetClass." + methodName);
                        return;
                    }
                    target.overloads.forEach(function(overload) {
                        overload.implementation = function() {
                            console.log("[+] 拦截 $targetClass." + methodName);
                            var orig = this[methodName].apply(this, arguments);
                            console.log("  原返回值: " + orig + " -> 篡改为: $returnValue");
                            return $returnValue;
                        };
                    });
                    console.log("[*] 返回值篡改 Hook 已生效: $targetClass." + methodName + " -> $returnValue");
                });
                """.trimIndent()
            }

            "bypass" -> {
                if (fridaMethod == null) {
                    return ToolResult(false, L("bypass 需要 target_method 参数", "bypass requires target_method parameter"))
                }
                """
                Java.perform(function() {
                    var clazz = Java.use("$targetClass");
                    var methodName = "$fridaMethod";
                    var target = clazz[methodName];
                    if (!target) {
                        console.log("[!] 未找到方法: $targetClass." + methodName);
                        return;
                    }
                    target.overloads.forEach(function(overload) {
                        overload.implementation = function() {
                            console.log("[+] 绕过检测方法: $targetClass." + methodName);
                            var retType = overload.returnType ? overload.returnType.className : "void";
                            if (retType === "boolean") return false;
                            if (retType === "int" || retType === "long" || retType === "short" || retType === "byte") return 0;
                            return;
                        };
                    });
                    console.log("[*] Bypass Hook 已生效: $targetClass." + methodName);
                });
                """.trimIndent()
            }

            "trace_all" -> generateTraceAll(targetClass)

            else -> return ToolResult(false, "不支持的 hook_type: $hookType")
        }

        val output = buildString {
            appendLine("# Frida Hook 脚本")
            appendLine("目标: $targetClass${rawMethod?.let { ".$it" } ?: " (所有方法)"}")
            appendLine("类型: $hookType")
            appendLine()
            appendLine("```javascript")
            appendLine(script)
            appendLine("```")
            appendLine()
            appendLine(L("使用方法:", "Usage:"))
            appendLine("1. 保存为 hook.js（可用 create_script 工具）")
            appendLine("2. 运行: frida -U -f <包名> -l hook.js --no-pause")
        }

        return ToolResult(true, output)
    }

    private fun generateTraceAll(targetClass: String): String = """
        Java.perform(function() {
            var clazz = Java.use("$targetClass");
            var methods = clazz.class.getDeclaredMethods();
            methods.forEach(function(method) {
                var methodName = method.getName();
                var overloads = clazz[methodName];
                if (overloads && overloads.overloads) {
                    overloads.overloads.forEach(function(overload) {
                        overload.implementation = function() {
                            console.log("[+] $targetClass." + methodName);
                            for (var i = 0; i < arguments.length; i++) {
                                console.log("  arg[" + i + "]: " + arguments[i]);
                            }
                            var ret = this[methodName].apply(this, arguments);
                            console.log("  ret: " + ret);
                            return ret;
                        };
                    });
                }
            });
            console.log("[*] 已 Hook " + methods.length + " 个方法: $targetClass");
        });
    """.trimIndent()
}
