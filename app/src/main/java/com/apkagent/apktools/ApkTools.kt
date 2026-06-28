package com.apkagent.apktools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolRegistry
import com.apkagent.tools.SmartSearchTool
import com.apkagent.tools.AutoPatchTool
import com.apkagent.tools.GenerateHookTool
import com.apkagent.tools.AnalyzeObfuscationTool
import com.apkagent.tools.ExternalFileReadTool
import com.apkagent.tools.ExternalFileWriteTool
import com.apkagent.tools.ExternalFileListTool
import com.apkagent.tools.CreateScriptTool
import com.apkagent.tools.RunScriptTool
import com.apkagent.agent.ToolResult
import com.apkagent.agent.boolProp
import com.apkagent.agent.intProp
import com.apkagent.agent.schemaObject
import com.apkagent.agent.strProp
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import com.apkagent.shizuku.ShizukuManager
import com.apkagent.util.Logger
import com.apkagent.apktools.advanced.ZipAlignTool
import com.apkagent.apktools.advanced.SplitApkHandler
import com.apkagent.apktools.advanced.ResourceTableDecoder
import com.apkagent.apktools.advanced.ResourceExtractor
import com.apkagent.apktools.advanced.CertificateViewer
import com.apkagent.apktools.advanced.PermissionsViewer
import com.apkagent.apktools.advanced.EnhancedSigner
import com.apkagent.apktools.advanced.ApkInstaller
import java.io.File
import java.util.zip.ZipFile

/**
 * 构建并注册全部 APK 逆向工具。
 */
fun buildToolRegistry(): ToolRegistry {
    val r = ToolRegistry()
    r.register(ApkListFiles)
    r.register(ApkReadManifest)
    r.register(ApkReadSigner)
    r.register(ApkReadDex)
    r.register(ApkHexView)
    r.register(ApkExtractEntry)
    r.register(ApkApkInfo)
    r.register(FileReadText)
    r.register(FileList)
    r.register(FileWrite)
    // 高级功能（框架接口，待后续填充实现）
    r.register(ApkRemoveSignatureCheck)
    r.register(ApkRepack)
    r.register(ApkSign)
    r.register(ApkUnpack)
    r.register(DexDisasm)
    r.register(DexAssemble)
    r.register(DexEdit)
    // Shizuku 特权工具
    r.register(ShizukuFileAccess)
    // Python 脚本执行
    r.register(PythonExec)
    r.register(PythonInfo)
    r.register(PipInstall)
    r.register(PipList)
    // 插件系统
    r.register(PluginInstall)
    r.register(PluginList)
    // 高级搜索
    r.register(ApkSearchStrings)
    r.register(FileSearch)
    // Manifest 修改
    r.register(ApkPatchManifest)
    // ADB
    r.register(AdbExec)
    // 智能 Patch 库
    r.register(SmartPatchScan)
    r.register(SmartPatchApply)
    // 安全风险扫描
    r.register(RiskScan)
    // 脚本执行
    r.register(RunPythonScript)
    r.register(RunNodeScript)
    r.register(GenerateFridaScript)
    // 新增工具
    r.register(SmartSearchTool())
    r.register(AutoPatchTool())
    r.register(GenerateHookTool())
    r.register(AnalyzeObfuscationTool())
    // 外部文件访问（不受沙箱限制）
    r.register(ExternalFileReadTool())
    r.register(ExternalFileWriteTool())
    r.register(ExternalFileListTool())
    // AI 自建脚本
    r.register(CreateScriptTool())
    r.register(RunScriptTool())
    // 新增高级工具（来自 AEE 功能移植）
    r.register(ZipAlignTool)
    r.register(SplitApkHandler)
    r.register(ResourceTableDecoder)
    r.register(ResourceExtractor)
    r.register(CertificateViewer)
    r.register(PermissionsViewer)
    r.register(EnhancedSigner)
    r.register(ApkInstaller)
    return r
}

/* ---------------- APK 工具 ---------------- */

private fun resolveApk(args: JsonObject, ctx: ToolContext): File? {
    val p = args.str("apk_path")
    if (p != null) {
        val f = Sandbox.resolve(File(p), ctx.workspace)
        Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
        return f
    }
    return ctx.openApk
}

/** apk.list_files — 列出 APK 内部结构 */
object ApkListFiles : Tool {
    override val name = "apk_list_files"
    override val description = "列出 APK 内部文件结构（ZIP 条目），包含大小/压缩大小/类型。可指定子目录前缀过滤。"
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "prefix" to strProp("只列出路径以该前缀开头的条目，例如 'res/' 或 'assets/'")
        )
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK，且当前没有导入的 APK。请先用 apk.apk_info 或在界面导入 APK。")
        val prefix = args.str("prefix") ?: ""
        val sb = StringBuilder()
        sb.appendLine("APK 内部结构：${apk.name}（${apk.length()} 字节）")
        ZipFile(apk).use { zip ->
            val entries = zip.entries().toList()
                .filter { it.name.startsWith(prefix) }
                .sortedBy { it.name }
            sb.appendLine("共 ${entries.size} 个条目（前缀 '$prefix'）\n")
            sb.appendLine("%-48s %12s %12s  %s".format("路径", "大小", "压缩", "类型"))
            sb.appendLine("-".repeat(90))
            for (e in entries) {
                val type = guessType(e.name)
                sb.appendLine("%-48s %12d %12d  %s".format(
                    e.name.take(48), e.size, e.compressedSize, type
                ))
            }
        }
        return ToolResult.ok(sb.toString())
    }
}

private fun guessType(name: String): String {
    val lower = name.lowercase()
    return when {
        name.endsWith("/") -> "目录"
        name == "AndroidManifest.xml" -> "二进制XML"
        lower.endsWith(".dex") -> "DEX"
        lower.endsWith(".arsc") -> "资源表"
        lower.endsWith(".xml") -> "XML"
        lower.endsWith(".so") -> "Native库"
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp") -> "图片"
        lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".properties") -> "文本"
        else -> "文件"
    }
}

/** apk.read_manifest — 解析 AndroidManifest.xml */
object ApkReadManifest : Tool {
    override val name = "apk_read_manifest"
    override val description = "解析 APK 内二进制 AndroidManifest.xml，还原为可读 XML 文本（包名、权限、组件、SDK 版本等）。"
    override val parameters = schemaObject(
        mapOf("apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"))
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: return ToolResult.err("APK 内未找到 AndroidManifest.xml")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val xml = try { AxmlParser(bytes).parse() }
            catch (e: Throwable) { return ToolResult.err("解析失败：${e.message}") }
            return ToolResult.ok(xml)
        }
    }
}

/** apk.read_signer — 读取签名信息 */
object ApkReadSigner : Tool {
    override val name = "apk_read_signer"
    override val description = "读取 APK 的签名信息：检测 v1/v2/v3 签名方案，提取 X.509 证书（Subject/Issuer/Serial/算法/有效期）。"
    override val parameters = schemaObject(
        mapOf("apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"))
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        return ToolResult.ok(SignatureReader.read(apk))
    }
}

/** apk.read_dex — 解析 DEX 头 + 类名 */
object ApkReadDex : Tool {
    override val name = "apk_read_dex"
    override val description = "解析 DEX 文件：版本、头统计（字符串/类型/方法/字段/类数量），并列出所有类名。可读工作区内 .dex，或直接读 APK 内的 DEX 条目。"
    override val parameters = schemaObject(
        mapOf(
            "path" to strProp("工作区内 .dex 文件路径"),
            "entry_in_apk" to strProp("若要从 APK 内读取 DEX，填条目名如 classes.dex / classes2.dex（此时 path 可留空）"),
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "max_classes" to intProp("最多列出的类数量，默认 200")
        )
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val max = args.int("max_classes") ?: 200
        val bytes: ByteArray = try {
            val entry = args.str("entry_in_apk")
            if (entry != null) {
                val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
                ZipFile(apk).use { zip ->
                    val e = zip.getEntry(entry) ?: return@use null
                    zip.getInputStream(e).use { it.readBytes() }
                } ?: return ToolResult.err("APK 内未找到条目：$entry")
            } else {
                val p = args.str("path") ?: return ToolResult.err("需提供 path 或 entry_in_apk。")
                val f = Sandbox.resolve(File(p), ctx.workspace)
                Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
                f.readBytes()
            }
        } catch (e: SecurityException) { return ToolResult.err(e.message ?: "权限拒绝") }

        val info = try { DexParser(bytes).parse() }
        catch (e: Throwable) { return ToolResult.err("DEX 解析失败：${e.message}") }

        val sb = StringBuilder()
        sb.appendLine("DEX 版本：0${info.version}")
        sb.appendLine("文件大小：${info.fileSize} 字节")
        sb.appendLine("字符串数：${info.stringIdsSize}")
        sb.appendLine("类型数  ：${info.typeIdsSize}")
        sb.appendLine("原型数  ：${info.protoIdsSize}")
        sb.appendLine("字段数  ：${info.fieldIdsSize}")
        sb.appendLine("方法数  ：${info.methodIdsSize}")
        sb.appendLine("类定义数：${info.classDefsSize}")
        sb.appendLine()
        sb.appendLine("类列表（共 ${info.classes.size} 个，显示前 ${minOf(max, info.classes.size)}）：")
        info.classes.take(max).forEachIndexed { i, c -> sb.appendLine("  ${i + 1}. $c") }
        if (info.classes.size > max) sb.appendLine("  …还有 ${info.classes.size - max} 个类未显示，可增大 max_classes。")
        return ToolResult.ok(sb.toString())
    }
}

/** apk.hex_view — 十六进制查看 */
object ApkHexView : Tool {
    override val name = "apk_hex_view"
    override val description = "十六进制查看文件内容。target 可以是工作区内文件路径，或 'apk://条目名'（如 apk://classes.dex）读取 APK 内条目。"
    override val parameters = schemaObject(
        mapOf(
            "target" to strProp("目标：工作区文件路径，或 'apk://条目名'"),
            "apk_path" to strProp("APK 文件路径（用于 apk:// 条目）；留空则用当前导入的 APK"),
            "offset" to intProp("起始字节偏移，默认 0"),
            "length" to intProp("读取字节数，默认 4096")
        ),
        listOf("target")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val target = args.str("target") ?: return ToolResult.err("缺少 target")
        val offset = args.int("offset") ?: 0
        val length = args.int("length") ?: 4096
        val bytes: ByteArray = if (target.startsWith("apk://")) {
            val entryName = target.removePrefix("apk://")
            val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
            ZipFile(apk).use { zip ->
                val e = zip.getEntry(entryName) ?: return@use null
                zip.getInputStream(e).use { it.readBytes() }
            } ?: return ToolResult.err("APK 内未找到条目：$entryName")
        } else {
            val f = Sandbox.resolve(File(target), ctx.workspace)
            Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
            f.readBytes()
        }
        return ToolResult.ok(HexView.dump(bytes, maxBytes = length, offset = offset))
    }
}

/** apk.extract_entry — 提取 APK 内文件到工作区（敏感） */
object ApkExtractEntry : Tool {
    override val name = "apk_extract_entry"
    override val description = "从 APK 中提取指定条目到工作区目录，便于后续分析/修改。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "entry" to strProp("APK 内条目名，如 classes.dex / AndroidManifest.xml"),
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "dest_name" to strProp("提取后保存的文件名（默认用条目 basename）")
        ),
        listOf("entry")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val entry = args.str("entry") ?: return ToolResult.err("缺少 entry")
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        val destName = args.str("dest_name") ?: File(entry).name
        val dest = File(ctx.workspace, destName)
        Sandbox.assertWritable(dest, ctx.workspace)
        ZipFile(apk).use { zip ->
            val e = zip.getEntry(entry) ?: return ToolResult.err("APK 内未找到条目：$entry")
            zip.getInputStream(e).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        return ToolResult.ok("已提取 '$entry' → ${dest.absolutePath}（${dest.length()} 字节）")
    }
}

/** apk.apk_info — APK 概览 */
object ApkApkInfo : Tool {
    override val name = "apk_apk_info"
    override val description = "获取 APK 概览：包名、版本、SDK、权限、组件数、DEX 数等（来自 Manifest + 结构扫描）。"
    override val parameters = schemaObject(
        mapOf("apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"))
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        val sb = StringBuilder()
        sb.appendLine("APK 概览：${apk.name}")
        sb.appendLine("路径：${apk.absolutePath}")
        sb.appendLine("大小：${apk.length()} 字节")
        ZipFile(apk).use { zip ->
            val entries = zip.entries().toList()
            val dexCount = entries.count { it.name.endsWith(".dex") }
            val soCount = entries.count { it.name.endsWith(".so") }
            val arsc = entries.firstOrNull { it.name == "resources.arsc" }
            sb.appendLine("条目总数：${entries.size}")
            sb.appendLine("DEX 文件：$dexCount 个")
            sb.appendLine("Native 库：$soCount 个")
            sb.appendLine("资源表：${if (arsc != null) "存在 (${arsc.size} 字节)" else "无"}")
            sb.appendLine()
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val xml = try { AxmlParser(bytes).parse() } catch (_: Throwable) { "" }
                sb.appendLine("── AndroidManifest.xml（节选）──")
                sb.appendLine(xml.lines().take(60).joinToString("\n"))
            }
        }
        return ToolResult.ok(sb.toString())
    }
}

/* ---------------- 工作区文件工具 ---------------- */

object FileReadText : Tool {
    override val name = "file_read_text"
    override val description = "以文本形式读取工作区内文件内容。仅限工作区目录。"
    override val parameters = schemaObject(
        mapOf(
            "path" to strProp("工作区内文件路径"),
            "max_chars" to intProp("最多读取字符数，默认 8000")
        ),
        listOf("path")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.err("缺少 path")
        val max = args.int("max_chars") ?: 8000
        val f = Sandbox.resolve(File(path), ctx.workspace)
        Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
        if (!f.exists()) return ToolResult.err("文件不存在：$path")
        if (f.isDirectory) return ToolResult.err("目标是目录：$path")
        val text = f.readText()
        return ToolResult.ok(if (text.length > max) text.take(max) + "\n…(共 ${text.length} 字符，已截断)" else text)
    }
}

object FileList : Tool {
    override val name = "file_list"
    override val description = "列出工作区内某目录的文件/子目录。path 留空列工作区根。"
    override val parameters = schemaObject(
        mapOf("path" to strProp("工作区内目录路径，留空列工作区根"))
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = args.str("path")
        val dir = if (p.isNullOrBlank()) ctx.workspace else Sandbox.resolve(File(p), ctx.workspace)
        Sandbox.assertReadable(dir, ctx.workspace, ctx.openApk)
        if (!dir.exists()) return ToolResult.err("路径不存在：${dir.path}")
        if (!dir.isDirectory) return ToolResult.err("不是目录：${dir.path}")
        val sb = StringBuilder()
        sb.appendLine("目录：${dir.absolutePath}")
        dir.listFiles()?.sortedBy { it.name }?.forEach {
            val t = if (it.isDirectory) "[DIR] " else "      "
            sb.appendLine("$t${it.name}  ${if (it.isFile) "${it.length()}B" else ""}")
        }
        return ToolResult.ok(sb.toString())
    }
}

object FileWrite : Tool {
    override val name = "file_write"
    override val description = "向工作区内写入/覆盖文本文件。仅限工作区目录。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "path" to strProp("工作区内目标文件路径"),
            "content" to strProp("要写入的文本内容")
        ),
        listOf("path", "content")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("path") ?: return ToolResult.err("缺少 path")
        val content = args.str("content") ?: return ToolResult.err("缺少 content")
        val f = File(path)
        Sandbox.assertWritable(f, ctx.workspace)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return ToolResult.ok("已写入 ${f.absolutePath}（${content.length} 字符）")
    }
}

/* ---------------- 高级功能：框架接口（待实现） ---------------- */

object ApkRemoveSignatureCheck : Tool {
    override val name = "apk_remove_signature_check"
    override val description = "扫描 APK 内 DEX 中的签名校验调用点（getPackageInfo+GET_SIGNATURES、PackageInfo.signatures、signingInfo、hasSignature、签名 equals 比对等），列出位置并可选 patch。基于 baksmali 反编译 + 模式匹配实现。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "action" to strProp("操作：scan（仅扫描列出）/ patch（扫描并尝试 patch 后输出新 DEX）", enum = listOf("scan", "patch")),
            "output_dir" to strProp("patch 模式下输出目录（工作区内），存放 patched DEX")
        )
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        val action = args.str("action") ?: "scan"
        return when (action) {
            "scan" -> {
                val r = com.apkagent.apktools.smali.SignatureCheckerScanner.scanApk(apk)
                if (!r.success) return ToolResult.err(r.message)
                val sb = StringBuilder()
                sb.appendLine("签名校验扫描：${apk.name}")
                sb.appendLine(r.message)
                sb.appendLine()
                if (r.hits.isEmpty()) {
                    sb.appendLine("✅ 未检测到明显的签名校验调用点。")
                    sb.appendLine("（注：JNI 层 .so 校验、加固壳校验无法通过 DEX 扫描发现）")
                } else {
                    sb.appendLine("── 发现的调用点 ──")
                    r.hits.forEachIndexed { i, h ->
                        sb.appendLine("${i + 1}. [${h.pattern}] ${h.className}.${h.methodName}")
                        sb.appendLine("   ${h.detail}")
                        sb.appendLine("   ${if (h.patchable) "可 patch" else "需人工判断"}")
                    }
                    sb.appendLine()
                    sb.appendLine("可调用 action=patch 进行自动 patch（将校验返回值改为 true）。")
                }
                ToolResult.ok(sb.toString())
            }
            "patch" -> {
                val outDir = args.str("output_dir")?.let { Sandbox.resolve(File(it), ctx.workspace) }
                    ?: File(ctx.workspace, "patched_${apk.nameWithoutExtension}")
                Sandbox.assertWritable(outDir, ctx.workspace)
                outDir.mkdirs()
                val sb = StringBuilder()
                sb.appendLine("签名校验 Patch：${apk.name}")
                // 提取所有 DEX + 并行 patch
                val dexFiles = mutableListOf<Pair<String, File>>()
                java.util.zip.ZipFile(apk).use { zip ->
                    zip.entries().toList().filter { it.name.endsWith(".dex") }.forEach { entry ->
                        val tmp = File(ctx.workspace, "_tmp_${entry.name.replace("/", "_")}")
                        zip.getInputStream(entry).use { it.copyTo(tmp.outputStream()) }
                        dexFiles.add(entry.name to tmp)
                    }
                }
                // 并行 patch 所有 DEX
                val report = com.apkagent.apktools.smali.SignatureCheckerScanner.patchAllDex(dexFiles, outDir)
                // 清理临时文件
                dexFiles.forEach { (_, f) -> try { f.delete() } catch (_: Throwable) {} }
                sb.appendLine(report)
                sb.appendLine()
                sb.appendLine("Patch 输出目录：${outDir.absolutePath}")
                sb.appendLine("下一步：用 apk.repack + apk.sign 重打包签名。")
                ToolResult.ok(sb.toString())
            }
            else -> ToolResult.err("未知 action：$action")
        }
    }
}

object ApkRepack : Tool {
    override val name = "apk_repack"
    override val description = "将工作区内解包后的 APK 目录重新打包为 APK（自动排除 META-INF 旧签名）。配合 apk.unpack 和 apk.sign 使用完成完整重打包流程。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "src_dir" to strProp("工作区内解包后的 APK 目录", ),
            "output" to strProp("输出 APK 路径（工作区内）")
        ),
        listOf("src_dir", "output")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val srcDir = args.str("src_dir")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: return ToolResult.err("缺少 src_dir")
        val output = args.str("output")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: return ToolResult.err("缺少 output")
        Sandbox.assertReadable(srcDir, ctx.workspace, ctx.openApk)
        Sandbox.assertWritable(output, ctx.workspace)
        val r = com.apkagent.apktools.smali.ApkRepackSigner.repack(srcDir, output)
        return if (r.success) ToolResult.ok(
            "${r.message}\n输出：${output.absolutePath}（${output.length()} 字节）\n下一步：调用 apk.sign 进行签名。"
        ) else ToolResult.err(r.message)
    }
}

object ApkUnpack : Tool {
    override val name = "apk_unpack"
    override val description = "解包 APK 到工作区目录（提取全部文件，便于修改后用 apk.repack 重打包）。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "output_dir" to strProp("输出目录（工作区内），留空则自动命名")
        )
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
        val outDir = args.str("output_dir")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: File(ctx.workspace, "unpacked_${apk.nameWithoutExtension}")
        Sandbox.assertWritable(outDir, ctx.workspace)
        val r = com.apkagent.apktools.smali.ApkRepackSigner.unpackApk(apk, outDir)
        return if (r.success) ToolResult.ok(
            "${r.message}\n输出目录：${outDir.absolutePath}"
        ) else ToolResult.err(r.message)
    }
}

object ApkSign : Tool {
    override val name = "apk_sign"
    override val description = "对 APK 进行 v1/v2 签名。使用内置调试密钥（自签名，与 Android debug 等价）或自定义 keystore。基于 Google apksig 库实现。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "apk_path" to strProp("待签名的 APK 路径（工作区内）"),
            "output" to strProp("签名后输出 APK 路径，留空则在原名加 -signed"),
            "scheme" to strProp("签名方案：v1 / v2 / both", enum = listOf("v1", "v2", "both"))
        ),
        listOf("apk_path")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apkPath = args.str("apk_path")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: return ToolResult.err("缺少 apk_path")
        Sandbox.assertReadable(apkPath, ctx.workspace, ctx.openApk)
        val scheme = args.str("scheme") ?: "both"
        val outPath = args.str("output")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: File(apkPath.parentFile, "${apkPath.nameWithoutExtension}-signed.apk")
        Sandbox.assertWritable(outPath, ctx.workspace)
        val (v1, v2) = when (scheme) {
            "v1" -> true to false
            "v2" -> false to true
            else -> true to true
        }
        val r = com.apkagent.apktools.smali.ApkRepackSigner.signApk(apkPath, outPath, v1, v2)
        return if (r.success) {
            val sb = StringBuilder()
            sb.appendLine("✅ ${r.message}")
            sb.appendLine("输出：${outPath.absolutePath}（${outPath.length()} 字节）")
            r.certInfo?.let { sb.appendLine("证书信息：\n$it") }
            ToolResult.ok(sb.toString())
        } else ToolResult.err(r.message)
    }
}

object DexDisasm : Tool {
    override val name = "dex_disasm"
    override val description = "将 DEX 反编译为 smali 代码（基于 baksmali 引擎）。可反编译工作区内 .dex 或直接读 APK 内 DEX。输出 smali 文件到工作区目录。"
    override val parameters = schemaObject(
        mapOf(
            "path" to strProp("工作区内 .dex 文件路径"),
            "entry_in_apk" to strProp("若从 APK 内读取 DEX，填条目名如 classes.dex"),
            "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
            "output_dir" to strProp("smali 输出目录（工作区内），留空自动命名")
        )
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val outDir = args.str("output_dir")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: File(ctx.workspace, "smali_out_${System.currentTimeMillis()}")
        Sandbox.assertWritable(outDir, ctx.workspace)
        val entry = args.str("entry_in_apk")
        val result = if (entry != null) {
            val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK。")
            java.util.zip.ZipFile(apk).use { zip ->
                val e = zip.getEntry(entry) ?: return@use null
                val tmp = File(ctx.workspace, "_tmp_${entry.replace("/", "_")}")
                zip.getInputStream(e).use { it.copyTo(tmp.outputStream()) }
                val r = com.apkagent.apktools.smali.SmaliEngine.disassembleDex(tmp, outDir)
                tmp.delete()
                r
            } ?: return ToolResult.err("APK 内未找到条目：$entry")
        } else {
            val p = args.str("path") ?: return ToolResult.err("需提供 path 或 entry_in_apk")
            val f = Sandbox.resolve(File(p), ctx.workspace)
            Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
            com.apkagent.apktools.smali.SmaliEngine.disassembleDex(f, outDir)
        }
        return if (result.success) {
            ToolResult.ok("${result.message}\nsmali 输出目录：${outDir.absolutePath}\n可用 dex.assemble 回编译，或 file.read_text 查看具体类。")
        } else ToolResult.err(result.message)
    }
}

object DexAssemble : Tool {
    override val name = "dex_assemble"
    override val description = "将 smali 目录回编译为 DEX 文件（基于 smali 汇编器）。用于修改 smali 后重新生成 DEX。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "smali_dir" to strProp("工作区内 smali 文件目录"),
            "output" to strProp("输出 .dex 文件路径（工作区内）")
        ),
        listOf("smali_dir", "output")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val smaliDir = args.str("smali_dir")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: return ToolResult.err("缺少 smali_dir")
        val output = args.str("output")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: return ToolResult.err("缺少 output")
        Sandbox.assertReadable(smaliDir, ctx.workspace, ctx.openApk)
        Sandbox.assertWritable(output, ctx.workspace)
        val r = com.apkagent.apktools.smali.SmaliEngine.assembleSmali(smaliDir, output)
        return if (r.success) ToolResult.ok(
            "${r.message}\n输出：${output.absolutePath}（${output.length()} 字节）"
        ) else ToolResult.err(r.message)
    }
}

object DexEdit : Tool {
    override val name = "dex_edit"
    override val description = "DEX/smali 编辑：列出类的方法字段、或修改 smali 文本。工作流：dex.disasm 反编译 → file.read_text 查看/修改 smali → dex.assemble 回编译。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "smali_path" to strProp("工作区内 .smali 文件路径"),
            "action" to strProp("操作：list_methods（列出方法）/ list_fields（列出字段）/ read（读取smali文本）", enum = listOf("list_methods", "list_fields", "read"))
        ),
        listOf("smali_path")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("smali_path") ?: return ToolResult.err("缺少 smali_path")
        val action = args.str("action") ?: "read"
        val f = Sandbox.resolve(File(path), ctx.workspace)
        Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
        if (!f.exists()) return ToolResult.err("文件不存在：$path")
        val text = f.readText()
        return when (action) {
            "read" -> ToolResult.ok(text.take(8000) + if (text.length > 8000) "\n…(共 ${text.length} 字符)" else "")
            "list_methods" -> {
                val methods = Regex("""\.method\s+(.*)""").findAll(text).map { it.groupValues[1] }.toList()
                ToolResult.ok("方法列表（${methods.size}）：\n${methods.joinToString("\n")}")
            }
            "list_fields" -> {
                val fields = Regex("""\.field\s+(.*)""").findAll(text).map { it.groupValues[1] }.toList()
                ToolResult.ok("字段列表（${fields.size}）：\n${fields.joinToString("\n")}")
            }
            else -> ToolResult.err("未知 action")
        }
    }
}

/* ---------------- Shizuku 特权工具 ---------------- */

/** shizuku.file_access — 通过 Shizuku 以系统权限读文件 */
object ShizukuFileAccess : Tool {
    override val name = "shizuku_file_access"
    override val description = "通过 Shizuku 系统权限读取/列出任意路径文件（绕过 SAF 限制）。需 Shizuku 已授权。"
    override val parameters = schemaObject(
        mapOf(
            "action" to strProp("操作：read（读文件）/ list（列目录）/ copy_to_workspace（复制到工作区）",
                enum = listOf("read", "list", "copy_to_workspace")),
            "path" to strProp("目标路径（绝对路径）"),
            "max_chars" to intProp("read 模式最多读取字符数，默认 8000"),
            "dest_name" to strProp("copy_to_workspace 模式的目标文件名（留空用原名）")
        ),
        listOf("action", "path")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ShizukuManager.isAuthorized()) {
            return ToolResult.err("Shizuku 未授权，无法使用系统级文件访问。请先在设置页授权 Shizuku。")
        }
        val path = args.str("path") ?: return ToolResult.err("缺少 path")
        val action = args.str("action") ?: "read"

        return when (action) {
            "read" -> {
                val max = args.int("max_chars") ?: 8000
                val content = ShizukuManager.readFileShizuku(path)
                if (content != null) {
                    ToolResult.ok(
                        if (content.length > max) content.take(max) + "\n…(共 ${content.length} 字符，已截断)"
                        else content
                    )
                } else {
                    ToolResult.err("无法读取：$path（文件不存在或无权限）")
                }
            }
            "list" -> {
                val output = ShizukuManager.execAndGet("ls -lah '$path' 2>&1")
                if (output != null && !output.contains("Permission denied") && !output.contains("No such file")) {
                    ToolResult.ok(output)
                } else {
                    ToolResult.err("无法列出：$path")
                }
            }
            "copy_to_workspace" -> {
                val src = File(path)
                val destName = args.str("dest_name") ?: src.name
                val dest = File(ctx.workspace, destName)
                Sandbox.assertWritable(dest, ctx.workspace)
                val ok = ShizukuManager.copyFileShizuku(src, dest)
                if (ok) {
                    ToolResult.ok("已复制到工作区：${dest.absolutePath}（${dest.length()} 字节）")
                } else {
                    ToolResult.err("复制失败：$path")
                }
            }
            else -> ToolResult.err("未知 action：$action")
        }
    }
}

/* ---------------- Python 脚本执行工具 ---------------- */

/** python.exec — 在设备上执行 Python 脚本 */
object PythonExec : Tool {
    override val name = "python_exec"
    override val description = "在 Android 设备上执行 Python 脚本。优先用 Python 引擎，不可用时自动降级为 Shizuku shell 执行简单操作（文件复制/删除/目录操作等）。复杂库（androguard/frida）仍需安装 Python。"
    override val sensitive = true
    override val parameters = schemaObject(
        mapOf(
            "code" to strProp("Python 代码（必填）。可直接内联代码，或写导入 os/json 等标准库。代码中可用 open() 读写工作区文件。"),
            "timeout" to intProp("超时秒数，默认 30，最大 120"),
            "save_as" to strProp("将代码保存为工作区内 .py 文件（留空则自动命名并执行后删除）")
        ),
        listOf("code")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val code = args.str("code") ?: return ToolResult.err("缺少 code")
        val timeout = (args.int("timeout") ?: 30).coerceIn(1, 120)
        val saveAs = args.str("save_as")

        // 尝试用 Python 执行
        val pyFile = if (saveAs != null) {
            File(ctx.workspace, saveAs)
        } else {
            File(ctx.workspace, "_py_${System.currentTimeMillis()}.py")
        }

        Sandbox.assertWritable(pyFile, ctx.workspace)
        pyFile.writeText(code)

        val pyResult: ToolResult? = try {
            val result = PythonRunner.execute(pyFile, timeout, ctx.workspace)
            if (result.success) {
                return ToolResult.ok(result.combined)
            }
            // 如果 Python 本身不可用（不是脚本错误），尝试降级
            if (result.error?.contains("Permission denied") == true
                || result.error?.contains("未找到") == true
                || result.error?.contains("installGuide") == true) {
                null // 标记需要降级
            } else {
                return ToolResult.err(result.combined) // Python 可用但脚本有错
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (saveAs == null) { try { pyFile.delete() } catch (_: Throwable) {} }
        }

        // Python 不可用 — 降级为 Shizuku shell 执行简单文件操作
        if (pyResult == null && ShizukuManager.isAuthorized()) {
            Logger.i("Py", "Python 不可用，降级为 Shizuku shell 执行")
            val shellCmd = pythonToShell(code, ctx.workspace.absolutePath)
            if (shellCmd != null) {
                val output = ShizukuManager.execAndGet(shellCmd)
                return if (output != null) {
                    ToolResult.ok("⚠️ Python 不可用，已通过 Shizuku shell 执行等效操作：\n${output.take(8000)}")
                } else {
                    ToolResult.err("Python 不可用且 Shizuku shell 执行失败")
                }
            }
            return ToolResult.err("Python 不可用。此操作需要完整 Python 环境（androguard/frida 等库），请在设置页安装。")
        }
        return pyResult ?: ToolResult.err("Python 不可用。请在设置页安装 Python 环境。")
    }

    /**
     * 将简单 Python 文件操作转换为等效 shell 命令。
     * 覆盖 AI 最常用的操作：shutil.copy2, os.remove, os.makedirs, os.path.exists 等。
     * 返回 null 表示无法转换（需要真正 Python）。
     */
    private fun pythonToShell(code: String, workspace: String): String? {
        val lines = code.trim().lines().map { it.trim() }
        val cmds = mutableListOf<String>()
        cmds.add("cd '$workspace'")

        for (line in lines) {
            when {
                // import/赋值/空行跳过
                line.startsWith("import ") || line.startsWith("from ") -> {}
                line.isBlank() || line.startsWith("#") -> {}
                line.contains(" = ") && !line.contains("shutil") && !line.contains("os.") -> {}
                // shutil.copy2(src, dst)
                line.matches(Regex(".*shutil\\.copy2?\\(.+\\).*")) -> {
                    val args = extractArgs(line) ?: return null
                    if (args.size >= 2) cmds.add("cp -f '${args[0]}' '${args[1]}'")
                    else return null
                }
                // os.remove(path)
                line.matches(Regex(".*os\\.remove\\(.+\\).*")) -> {
                    val args = extractArgs(line) ?: return null
                    cmds.add("rm -f '${args[0]}'")
                }
                // os.makedirs(path, exist_ok=True)
                line.matches(Regex(".*os\\.makedirs\\(.+\\).*")) -> {
                    val args = extractArgs(line) ?: return null
                    cmds.add("mkdir -p '${args[0]}'")
                }
                // shutil.rmtree(path)
                line.matches(Regex(".*shutil\\.rmtree\\(.+\\).*")) -> {
                    val args = extractArgs(line) ?: return null
                    cmds.add("rm -rf '${args[0]}'")
                }
                // os.path.getsize → stat
                line.matches(Regex(".*os\\.path\\.getsize\\(.+\\).*")) -> {
                    val args = extractArgs(line) ?: return null
                    cmds.add("stat -c %s '${args[0]}' 2>/dev/null || wc -c < '${args[0]}'")
                }
                // print() → echo
                line.matches(Regex("print\\(.*\\).*")) -> {
                    val inner = line.removePrefix("print(").removeSuffix(")")
                        .removeSurrounding("\"").removeSurrounding("'")
                    cmds.add("echo \"$inner\"")
                }
                else -> return null // 无法转换，需要真正 Python
            }
        }
        return cmds.joinToString(" && ")
    }

    /** 提取函数调用参数（简单版） */
    private fun extractArgs(call: String): List<String>? {
        val start = call.indexOf('(')
        val end = call.lastIndexOf(')')
        if (start < 0 || end < 0 || end <= start) return null
        val inner = call.substring(start + 1, end)
        return inner.split(",").map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
    }
}

/** python.info — 检查 Python 环境状态 */
object PythonInfo : Tool {
    override val name = "python_info"
    override val description = "检查设备上 Python 环境是否可用，以及版本信息。"
    override val parameters = schemaObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val python = PythonRunner.findPython()
        return if (python == null) {
            ToolResult.err(PythonRunner.installGuide())
        } else {
            val version = PythonRunner.getVersion() ?: "unknown"
            ToolResult.ok("Python 环境就绪\n路径: $python\n版本: $version\n\n可用的 Python APK 分析库（需在 Termux 中安装）：\n- androguard: APK 解析/证书/MinSdk分析\n- frida-tools: Frida Hook 脚本管理\n- objection: 运行时安全评估\n- apkid: APK 加固壳识别\n\n用法：用 python_exec 执行脚本代码。")
        }
    }
}

/* ---------------- pip 包管理 ---------------- */

/** pip.install — AI 自安装 Python 包，跳过已安装的 */
object PipInstall : Tool {
    override val name = "pip_install"
    override val description = "安装 Python pip 包（自动跳过已安装）。AI 可在需要时自行安装依赖，如 frida-tools、androguard、objection 等。支持批量安装。"
    override val sensitive = true
    override val parameters = schemaObject(mapOf(
        "packages" to strProp("要安装的包名或空格分隔的多个包名，如 'frida-tools androguard'"),
        "timeout" to intProp("超时秒数，默认120，最大300")
    ), listOf("packages"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!PythonRunner.isAvailable()) return ToolResult.err(PythonRunner.installGuide())
        val list = args.str("packages")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: return ToolResult.err("缺少 packages")
        val timeout = args.int("timeout") ?: 120
        return if (list.size == 1) {
            val r = PythonRunner.pipInstall(list[0], timeoutSeconds = timeout)
            if (r.success) ToolResult.ok(r.combined) else ToolResult.err(r.combined)
        } else {
            val r = PythonRunner.pipInstall(*list.toTypedArray(), timeoutSeconds = timeout)
            if (r.success) ToolResult.ok(r.combined) else ToolResult.err(r.combined)
        }
    }
}

/** pip.list — 查看已安装的 pip 包 */
object PipList : Tool {
    override val name = "pip_list"
    override val description = "列出 Python 已安装的 pip 包及版本。"
    override val parameters = schemaObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!PythonRunner.isAvailable()) return ToolResult.err(PythonRunner.installGuide())
        val pkgs = PythonRunner.refreshInstalled()
        return ToolResult.ok(if (pkgs.isEmpty()) "无已安装的 pip 包" else "已安装 ${pkgs.size} 个包:\n${pkgs.joinToString("\n")}")
    }
}

/* ---------------- 插件系统 ---------------- */

/** plugin.install — 安装内置或远程插件 */
object PluginInstall : Tool {
    override val name = "plugin_install"
    override val description = "安装分析插件到本地。内置插件（frida-scripts/smali-patches/反混淆/脱壳/分析脚本）直接写入，远程插件下载到 workspace/plugins/ 并缓存。"
    override val sensitive = true
    override val parameters = schemaObject(mapOf(
        "id" to strProp("内置插件ID（如 frida-sslpin, smali-vip-bypass），留空则用 url 下载"),
        "url" to strProp("远程插件下载URL（.js/.py/.patch/.zip），id 和 url 二选一"),
        "timeout" to intProp("下载超时秒数，默认60")
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pluginsDir = PluginManager.pluginDir(ctx.workspace)

        val id = args.str("id")
        val url = args.str("url")

        if (id != null) {
            // 查内置清单
            val info = PluginManager.BUILTIN_CATALOG.find { it.id == id }
            if (info == null) return ToolResult.err("未知插件ID: $id\n可用: ${PluginManager.BUILTIN_CATALOG.joinToString { it.id }}")
            // 安装内置代码
            val path = PluginManager.installBuiltin(id, pluginsDir)
            return if (path != null) ToolResult.ok("✅ 已安装: ${info.name}\n路径: $path\n类型: ${info.category}")
            else ToolResult.err("内置插件无预置代码，请从网络下载或参考模板自行编写。\n描述: ${info.description}")
        } else if (url != null) {
            val timeout = args.int("timeout") ?: 60
            val result = PluginManager.download(url, pluginsDir, timeout)
            return if (result.startsWith("已")) ToolResult.ok(result) else ToolResult.err(result)
        } else {
            return ToolResult.err("需提供 id 或 url")
        }
    }
}

/** plugin.list — 列出可用和已安装插件 */
object PluginList : Tool {
    override val name = "plugin_list"
    override val description = "列出所有可用插件及安装状态。内置插件有默认代码可直接使用。"
    override val parameters = schemaObject(mapOf(
        "category" to strProp("按类别过滤: frida / smali / deobfuscation / unpackers / analysis")
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val cat = args.str("category")?.lowercase()
        val pluginsDir = PluginManager.pluginDir(ctx.workspace)
        val filtered = if (cat != null) PluginManager.BUILTIN_CATALOG.filter { it.category == cat }
        else PluginManager.BUILTIN_CATALOG

        val sb = StringBuilder()
        var currentCat = ""
        for (plugin in filtered) {
            if (plugin.category != currentCat) {
                currentCat = plugin.category
                sb.appendLine("\n── ${currentCat.uppercase()} ──")
            }
            val installed = File(pluginsDir, "${plugin.category}/${plugin.filename}").exists()
            sb.appendLine("  ${if (installed) "✅" else "⬇️"} ${plugin.id}: ${plugin.name}")
            sb.appendLine("     ${plugin.description}")
        }
        sb.appendLine("\n用法: plugin.install id=插件ID")
        sb.appendLine("目录: ${pluginsDir.absolutePath}")
        return ToolResult.ok(sb.toString())
    }
}

/* ---------------- 高级搜索 ---------------- */

/** apk.search_strings — APK 内正则搜索 */
object ApkSearchStrings : Tool {
    override val name = "apk_search_strings"
    override val description = "在 APK 所有文件中搜索匹配正则的内容（字符串/URL/密钥模式）。逐个条目读取、忽略二进制、限时。"
    override val parameters = schemaObject(mapOf(
        "pattern" to strProp("""搜索正则表达式，如 'https?://[\w./]+' 或 'api_key|token|secret'"""),
        "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK"),
        "file_filter" to strProp("文件名过滤，如 '*.xml' 或 '*.dex'，留空搜全部"),
        "max_results" to intProp("最多匹配数，默认50")
    ), listOf("pattern"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.err("缺少 pattern")
        val apk = resolveApk(args, ctx) ?: return ToolResult.err("未指定 APK")
        val fileFilter = args.str("file_filter")
        val maxResults = args.int("max_results") ?: 50

        val regex = try { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
        catch (e: Throwable) { return ToolResult.err("无效正则: ${e.message}") }

        val sb = StringBuilder()
        var count = 0
        var scannedFiles = 0
        try {
            java.util.zip.ZipFile(apk).use { zip ->
                val entries = zip.entries().toList()
                    .filter { !it.isDirectory && (fileFilter == null || it.name.contains(fileFilter.removeSuffix("*"))) }
                for (entry in entries) {
                    if (count >= maxResults) break
                    scannedFiles++
                    try {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val text = String(bytes, Charsets.UTF_8).take(100_000)
                        if (text.isBlank()) continue
                        val matches = regex.findAll(text).take(maxResults - count).toList()
                        if (matches.isNotEmpty()) {
                            for (m in matches) {
                                count++
                                val matchCtx = text.substring(maxOf(0, m.range.first - 20), minOf(text.length, m.range.last + 30))
                                sb.appendLine("${entry.name}:${m.range.first} → $matchCtx")
                            }
                        }
                    } catch (_: Exception) { /* skip binary/corrupt */ }
                }
            }
            sb.appendLine("\n搜索 ${scannedFiles} 个文件，找到 $count 条匹配")
            return ToolResult.ok(sb.toString().take(8000))
        } catch (e: Throwable) {
            return ToolResult.err("搜索失败: ${e.message}")
        }
    }
}

/** file.search — 工作区文件正则搜索 */
object FileSearch : Tool {
    override val name = "file_search"
    override val description = "在工作区文件内容中搜索正则匹配。类似 grep -rn。"
    override val parameters = schemaObject(mapOf(
        "pattern" to strProp("搜索正则"),
        "path" to strProp("搜索起始路径（工作区内）；留空=工作区根"),
        "file_glob" to strProp("文件名匹配，如 '*.smali' 或 '*.py'"),
        "max_results" to intProp("最多结果数，默认30")
    ), listOf("pattern"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.err("缺少 pattern")
        val baseDir = args.str("path")?.let { Sandbox.resolve(File(it), ctx.workspace) } ?: ctx.workspace
        Sandbox.assertReadable(baseDir, ctx.workspace, ctx.openApk)
        val glob = args.str("file_glob")?.let { it.removePrefix("*.") }?.lowercase()
        val maxResults = args.int("max_results") ?: 30

        val regex = try { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }
        catch (e: Throwable) { return ToolResult.err("无效正则: ${e.message}") }

        val sb = StringBuilder()
        var count = 0
        try {
            baseDir.walkTopDown().filter { it.isFile && it.length() < 1_000_000 && (glob == null || it.extension.lowercase() == glob) }
                .forEach { f ->
                    if (count >= maxResults) return@forEach
                    try {
                        val text = f.readText(Charsets.UTF_8)
                        regex.find(text)?.let { m ->
                            count++
                            val matchCtx = text.substring(maxOf(0, m.range.first - 15), minOf(text.length, m.range.last + 25))
                            val rel = f.relativeTo(baseDir).path
                            sb.appendLine("$rel:${m.range.first} → $matchCtx")
                        }
                    } catch (_: Exception) {}
                }
            sb.appendLine("\n找到 $count 条匹配")
            return ToolResult.ok(sb.toString().take(8000))
        } catch (e: Throwable) {
            return ToolResult.err("搜索失败: ${e.message}")
        }
    }
}

/* ---------------- Manifest 修改 ---------------- */

/** apk.patch_manifest — 修改 AndroidManifest */
object ApkPatchManifest : Tool {
    override val name = "apk_patch_manifest"
    override val description = "修改 APK 的 AndroidManifest.xml（配合 apk_unpack/apk_repack 流程）。支持添加 debuggable=true、networkSecurity（允许明文）、backup、导出组件等。"
    override val sensitive = true
    override val parameters = schemaObject(mapOf(
        "manifest_path" to strProp("工作区内解包后的 AndroidManifest.xml 路径"),
        "patch_type" to strProp("修改类型：debuggable/network/backup/export/all", enum = listOf("debuggable", "network", "backup", "export", "all"))
    ), listOf("manifest_path", "patch_type"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("manifest_path") ?: return ToolResult.err("缺少 manifest_path")
        val patchType = args.str("patch_type") ?: "all"
        val f = Sandbox.resolve(File(path), ctx.workspace)
        Sandbox.assertReadable(f, ctx.workspace, ctx.openApk)
        Sandbox.assertWritable(f, ctx.workspace)
        if (!f.exists()) return ToolResult.err("文件不存在: $path")

        var text = f.readText()
        val changes = mutableListOf<String>()
        if (patchType in listOf("debuggable", "all")) {
            if (!text.contains("android:debuggable=\"true\"")) {
                text = text.replace("<application", "<application android:debuggable=\"true\"") // 简单替换
                changes.add("debuggable=true")
            }
        }
        if (patchType in listOf("network", "all")) {
            if (!text.contains("android:networkSecurityConfig")) {
                text = text.replace("<application", "<application android:networkSecurityConfig=\"@xml/network_security_config\"")
                changes.add("networkSecurityConfig")
            }
            if (!text.contains("android:usesCleartextTraffic=\"true\"")) {
                text = text.replace("<application", "<application android:usesCleartextTraffic=\"true\"")
                changes.add("usesCleartextTraffic=true")
            }
        }
        if (patchType in listOf("backup", "all")) {
            if (!text.contains("android:allowBackup=\"true\"")) {
                text = text.replace("<application", "<application android:allowBackup=\"true\"")
                changes.add("allowBackup=true")
            }
        }
        if (patchType in listOf("export", "all")) {
            // 将所有 activity/receiver/service 加 exported=true（如果缺）
            val exportedCount = Regex("""<(activity|receiver|service)[^>]*>""").findAll(text).count {
                val tag = it.value
                !tag.contains("android:exported=") && !tag.contains("intent-filter")
            }
            if (exportedCount > 0) changes.add("提示: $exportedCount 个组件缺少 exported (需手动处理)")
        }

        if (changes.isEmpty()) return ToolResult.ok("无需修改（Manifest 已包含所需属性）")
        f.writeText(text)
        return ToolResult.ok("已修改 ${f.absolutePath}:\n${changes.joinToString("\n")}\n\n下一步: apk_repack + apk_sign 重打包")
    }
}

/* ---------------- ADB 命令执行 ---------------- */

/** adb.exec — 执行 ADB 命令 */
object AdbExec : Tool {
    override val name = "adb_exec"
    override val description = "执行 ADB 命令（需设备已连接 USB 调试或无线调试）。可用于: adb install/uninstall, am/pm, logcat, dumpsys 等。"
    override val sensitive = true
    override val parameters = schemaObject(mapOf(
        "command" to strProp("ADB 命令（不需要 adb 前缀），如 'install /path/to.apk' 或 'shell pm list packages'"),
        "timeout" to intProp("超时秒数，默认30，最大60")
    ), listOf("command"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val cmd = args.str("command") ?: return ToolResult.err("缺少 command")
        val timeout = (args.int("timeout") ?: 30).coerceIn(5, 60)

        // 查找 adb
        val adbPaths = listOf("/data/data/com.termux/files/usr/bin/adb", "/system/bin/adb", "/usr/bin/adb")
        val adb = adbPaths.firstOrNull { File(it).exists() && File(it).canExecute() }
            ?: return ToolResult.err("未找到 ADB。请在 Termux 中: pkg install android-tools\n或连接电脑使用 adb。")

        return try {
            kotlinx.coroutines.withTimeout(timeout * 1000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val fullCmd = mutableListOf(adb)
                    fullCmd.addAll(cmd.split(" ").filter { it.isNotBlank() })
                    val pb = ProcessBuilder(fullCmd)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().use { it.readText() }
                    val exit = proc.waitFor()
                    ToolResult.ok(if (exit == 0) out.take(6000) else "exit=$exit\n${out.take(6000)}")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult.err("ADB 命令超时(${timeout}s)")
        } catch (e: Throwable) {
            ToolResult.err("ADB 执行失败: ${e.message}")
        }
    }
}

/* ───────── 智能 Patch 库 ───────── */

/** smart_patch.scan — 扫描可应用的 Patch */
object SmartPatchScan : Tool {
    override val name = "smart_patch_scan"
    override val description = "扫描 APK 工作区，列出所有可应用的智能 Patch（签名校验/root检测/emulator检测/调试限制/SSL pinning/VIP绕过）"
    override val parameters = schemaObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val result = SmartPatch.scanAvailable(ctx.workspace)
        return ToolResult.ok(result)
    }
}

/** smart_patch.apply — 应用指定 Patch */
object SmartPatchApply : Tool {
    override val name = "smart_patch_apply"
    override val description = "一键应用智能 Patch。可用ID: signature_check(签名校验), root_detection(Root检测), emulator_detection(模拟器检测), debug_restriction(调试限制), ssl_pinning(SSL Pinning), vip_bypass(VIP绕过)"
    override val sensitive = true
    override val parameters = schemaObject(mapOf(
        "patch_id" to strProp("Patch ID: signature_check / root_detection / emulator_detection / debug_restriction / ssl_pinning / vip_bypass"),
        "target_dir" to strProp("目标 smali 目录路径（留空自动检测）")
    ), listOf("patch_id"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val patchId = args.str("patch_id") ?: return ToolResult.err("缺少 patch_id\n可用: ${SmartPatch.PATCHES.joinToString { it.id }}")
        val targetDir = args.str("target_dir")
        val result = SmartPatch.applyPatch(ctx.workspace, patchId, targetDir)
        return if (result.success) ToolResult.ok(result.message) else ToolResult.err(result.message)
    }
}

/* ───────── 安全风险扫描 ───────── */

/** risk.scan — 安全风险扫描仪表盘 */
object RiskScan : Tool {
    override val name = "risk_scan"
    override val description = "全面扫描 APK 安全风险：危险权限/导出组件/WebView风险/硬编码密钥/证书Pinning/debuggable/allowBackup/明文传输/代码混淆/原生库。生成安全评分和报告。"
    override val parameters = schemaObject(mapOf(
        "apk_path" to strProp("APK 文件路径；留空则用当前导入的 APK")
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val apk = args.str("apk_path")?.let { Sandbox.resolve(File(it), ctx.workspace) }
            ?: ctx.openApk
            ?: return ToolResult.err("未指定 APK，请先导入或提供路径")

        if (!apk.exists()) return ToolResult.err("APK 文件不存在: ${apk.absolutePath}")

        val result = RiskScanner.scan(apk)
        return ToolResult.ok(result.report)
    }
}

/* ───────── 脚本执行工具 ───────── */

/** run_python_script — 执行预置或自定义 Python 分析脚本 */
object RunPythonScript : Tool {
    override val name = "run_python_script"
    override val description = "执行 Python 脚本进行深度分析。可用预置脚本: androguard_analysis(类/方法/权限分析), find_secrets(搜索硬编码密钥), manifest_parser(解析Manifest), dex_class_list(列出所有类)。也可传入自定义脚本内容。"
    override val parameters = schemaObject(mapOf(
        "script_name" to strProp("预置脚本名: androguard_analysis / find_secrets / manifest_parser / dex_class_list，或留空用 script_content"),
        "script_content" to strProp("自定义 Python 脚本内容（script_name 为空时使用）"),
        "args" to strProp("传给脚本的参数，如 APK 路径"),
        "timeout" to intProp("超时秒数，默认60")
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!PythonRunner.isAvailable()) return ToolResult.err("Python 未安装，请在设置页安装")

        val scriptName = args.str("script_name")
        val scriptContent = args.str("script_content")
        val scriptArgs = args.str("args") ?: ""
        val timeout = args.int("timeout") ?: 60

        val script = if (scriptName != null) {
            // 生成预置脚本
            val preset = PRESET_SCRIPTS[scriptName]
                ?: return ToolResult.err("未知脚本: $scriptName\n可用: ${PRESET_SCRIPTS.keys.joinToString()}")
            preset.replace("{ARGS}", scriptArgs)
        } else if (scriptContent != null) {
            scriptContent
        } else {
            return ToolResult.err("请提供 script_name 或 script_content")
        }

        // 写入临时文件执行
        val tmpScript = File(ctx.workspace, "_tmp_script.py")
        try {
            tmpScript.writeText(script)
            val result = PythonRunner.execute(tmpScript, timeout, ctx.workspace)
            return if (result.success) ToolResult.ok(result.combined.take(8000))
            else ToolResult.err(result.combined.take(4000))
        } finally {
            tmpScript.delete()
        }
    }

    private val PRESET_SCRIPTS = mapOf(
        "androguard_analysis" to """
import sys, os
try:
    from androguard.misc import AnalyzeAPK
except ImportError:
    print("❌ 未安装 androguard，正在安装...")
    os.system("pip3 install androguard")
    from androguard.misc import AnalyzeAPK

apk_path = "{ARGS}" or "imported.apk"
if not os.path.exists(apk_path):
    for f in os.listdir("."):
        if f.endswith(".apk"):
            apk_path = f; break

print(f"📦 分析: {apk_path}")
a, d, dx = AnalyzeAPK(apk_path)

print(f"\n=== APK 基本信息 ===")
print(f"包名: {a.get_package()}")
print(f"版本: {a.get_androidversion_name()} ({a.get_androidversion_code()})")
print(f"Min SDK: {a.get_min_sdk_version()}")
print(f"Target SDK: {a.get_target_sdk_version()}")
print(f"权限数: {len(a.get_permissions())}")

print(f"\n=== 权限列表 ===")
for p in sorted(a.get_permissions()):
    print(f"  {p}")

print(f"\n=== 组件统计 ===")
print(f"Activities: {len(a.get_activities())}")
print(f"Services: {len(a.get_services())}")
print(f"Receivers: {len(a.get_receivers())}")
print(f"Providers: {len(a.get_providers())}")

print(f"\n=== DEX 统计 ===")
for i, dex in enumerate(d):
    classes = dex.get_classes()
    methods = sum(len(c.get_methods()) for c in classes)
    print(f"  DEX{i}: {len(list(classes))} 类, {methods} 方法")
""".trimIndent(),

        "find_secrets" to """
import os, re

apk_path = "{ARGS}" or "imported.apk"
patterns = {
    "API Key": r'(?i)(api[_-]?key|apikey)\s*[=:]\s*["\'][A-Za-z0-9_\-]{16,}["\']',
    "Secret/Password": r'(?i)(secret|password|passwd)\s*[=:]\s*["\'][^"\']{6,}["\']',
    "Token": r'(?i)token\s*[=:]\s*["\'][A-Za-z0-9_\-\.]{20,}["\']',
    "AWS Key": r'AKIA[0-9A-Z]{16}',
    "Private Key": r'-----BEGIN (RSA |EC )?PRIVATE KEY-----',
    "URL with Auth": r'https?://[^:]+:[^@]+@',
}

import zipfile
print(f"🔍 搜索密钥: {apk_path}")
found = 0
try:
    with zipfile.ZipFile(apk_path) as z:
        for entry in z.namelist():
            if entry.endswith(('.dex', '.xml', '.json', '.properties', '.cfg')):
                try:
                    content = z.read(entry).decode('utf-8', errors='ignore')
                    for name, pattern in patterns.items():
                        matches = re.findall(pattern, content)
                        if matches:
                            for m in matches[:3]:
                                print(f"  🔴 [{name}] {entry}: {str(m)[:80]}")
                                found += 1
                except: pass
except Exception as e:
    print(f"错误: {e}")
print(f"\n扫描完成，发现 {found} 处潜在密钥")
""".trimIndent(),

        "dex_class_list" to """
import os
apk_path = "{ARGS}" or "imported.apk"
try:
    from androguard.misc import AnalyzeAPK
except ImportError:
    os.system("pip3 install androguard")
    from androguard.misc import AnalyzeAPK

a, d, dx = AnalyzeAPK(apk_path)
print(f"=== DEX 类列表 ({apk_path}) ===\n")
for i, dex in enumerate(d):
    classes = list(dex.get_classes())
    print(f"--- DEX{i}: {len(classes)} 类 ---")
    for c in classes[:200]:
        print(f"  {c.get_name()}")
    if len(classes) > 200:
        print(f"  ... 共 {len(classes)} 类")
""".trimIndent()
    )
}

/** run_node_script — 执行 Node.js 脚本 */
object RunNodeScript : Tool {
    override val name = "run_node_script"
    override val description = "执行 Node.js 脚本。用于 Frida 脚本生成、JS 分析等。"
    override val parameters = schemaObject(mapOf(
        "script_content" to strProp("Node.js 脚本内容"),
        "args" to strProp("传给脚本的参数"),
        "timeout" to intProp("超时秒数，默认30")
    ))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val script = args.str("script_content") ?: return ToolResult.err("缺少 script_content")
        val timeout = args.int("timeout") ?: 30

        // 查找 node（只需 exists，Shizuku 可以执行）
        val nodePaths = listOf(
            File(ctx.workspace.parentFile ?: ctx.workspace, "nodejs/bin/node").absolutePath,
            "/data/data/com.termux/files/usr/bin/node",
            "/system/bin/node"
        )
        val node = nodePaths.firstOrNull { File(it).exists() }
            ?: return ToolResult.err("Node.js 未安装，请在设置页安装")

        val tmpScript = File(ctx.workspace, "_tmp_script.js")
        try {
            tmpScript.writeText(script)

            // Shizuku 优先 — 内部 node 复制到 /data/local/tmp/ 后执行
            if (ShizukuManager.isAuthorized()) {
                val workDir = ctx.workspace.absolutePath
                // 内部 node 需要复制到 /data/local/tmp/（app 私有目录对 shell 不可访问）
                val execNode = if (node.contains("/files/nodejs/")) {
                    val tmpNode = "/data/local/tmp/apkagent_node"
                    val srcNode = node
                    val needCopy = !File(tmpNode).exists() || File(tmpNode).lastModified() < File(srcNode).lastModified()
                    if (needCopy) {
                        ShizukuManager.execAndGet("cp -f '$srcNode' '$tmpNode' && chmod 755 '$tmpNode'")
                    }
                    "'$tmpNode'"
                } else {
                    "'$node'"
                }
                val cmd = "cd '$workDir' && $execNode '${tmpScript.absolutePath}'"
                val process = ShizukuManager.execShizuku(cmd)
                if (process != null) {
                    val finished = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
                    if (finished == false) {
                        process.destroyForcibly()
                        return ToolResult.err("执行超时(${timeout}s)")
                    }
                    val out = process.inputStream.bufferedReader().use { it.readText() }
                    return ToolResult.ok(out.take(8000))
                }
            }

            // 回退到普通 ProcessBuilder
            val pb = ProcessBuilder(node, tmpScript.absolutePath)
            pb.directory(ctx.workspace)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = withTimeoutOrNull(timeout * 1000L) {
                proc.inputStream.bufferedReader().use { it.readText() }
            } ?: return ToolResult.err("执行超时(${timeout}s)")
            proc.waitFor()
            return ToolResult.ok(out.take(8000))
        } finally {
            tmpScript.delete()
        }
    }
}

/** generate_frida_script — AI 生成 Frida JS hook 脚本 */
object GenerateFridaScript : Tool {
    override val name = "generate_frida_script"
    override val description = "生成 Frida JS hook 脚本模板。支持: bypass_ssl(绕过SSL pinning), bypass_root(绕过Root检测), hook_method( hook指定方法), bypass_debug(绕过反调试), intercept_crypto(拦截加密调用)"
    override val parameters = schemaObject(mapOf(
        "template" to strProp("模板名: bypass_ssl / bypass_root / hook_method / bypass_debug / intercept_crypto"),
        "target_class" to strProp("目标类名（hook_method 时必填）"),
        "target_method" to strProp("目标方法名（hook_method 时可选）")
    ), listOf("template"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val template = args.str("template") ?: return ToolResult.err("缺少 template")
        val targetClass = args.str("target_class") ?: ""
        val targetMethod = args.str("target_method") ?: ""

        val script = FRIDA_TEMPLATES[template]
            ?: return ToolResult.err("未知模板: $template\n可用: ${FRIDA_TEMPLATES.keys.joinToString()}")

        val final = script
            .replace("{TARGET_CLASS}", targetClass)
            .replace("{TARGET_METHOD}", targetMethod)

        // 保存到工作区
        val outFile = File(ctx.workspace, "frida_${template}.js")
        outFile.writeText(final)

        return ToolResult.ok("✅ Frida 脚本已生成: ${outFile.name}\n\n```javascript\n$final\n```\n\n使用方法: frida -U -f <包名> -l ${outFile.name}")
    }

    private val FRIDA_TEMPLATES = mapOf(
        "bypass_ssl" to """
// Frida: 绕过 SSL Pinning
Java.perform(function() {
    console.log("[+] SSL Unpin loaded");

    // OkHttp3 CertificatePinner
    try {
        var CertPinner = Java.use("okhttp3.CertificatePinner");
        CertPinner.check.overload('java.lang.String', 'java.util.List').implementation = function() {
            console.log("[+] Bypassed SSL pin for: " + arguments[0]);
            return;
        };
    } catch(e) { console.log("[-] OkHttp3 not found: " + e); }

    // TrustManager
    try {
        var TrustManager = Java.use("javax.net.ssl.X509TrustManager");
        var SSLContext = Java.use("javax.net.ssl.SSLContext");
        var TrustMan = Java.registerClass({
            name: "com.apkagent.BypassTrustManager",
            implements: [TrustManager],
            methods: {
                checkClientTrusted: function(chain, authType) {},
                checkServerTrusted: function(chain, authType) {},
                getAcceptedIssuers: function() { return []; }
            }
        });
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, [TrustMan.${"$"}new()], null);
    } catch(e) { console.log("[-] TrustManager bypass failed: " + e); }

    console.log("[✓] SSL Pinning bypassed");
});
""".trimIndent(),

        "bypass_root" to """
// Frida: 绕过 Root 检测
Java.perform(function() {
    console.log("[+] Root bypass loaded");

    // 常见 Root 检测类
    var rootClasses = [
        "com.scottyab.rootbeer.RootBeer",
        "com.thanosfisherman.elgoog.RootChecker",
        "eu.chainfire.libsuperuser.Shell"
    ];

    rootClasses.forEach(function(cls) {
        try {
            var c = Java.use(cls);
            ["isRooted", "isRootedWithBusyBox", "checkRoot", "detectRoot", "isSuPresent"].forEach(function(m) {
                if (c[m]) {
                    c[m].implementation = function() { return false; };
                    console.log("[+] Hooked " + cls + "." + m);
                }
            });
        } catch(e) {}
    });

    // Runtime.exec("su")
    try {
        var Runtime = Java.use("java.lang.Runtime");
        Runtime.exec.overload('[Ljava.lang.String;').implementation = function(cmd) {
            if (cmd[0] === "su") {
                console.log("[+] Blocked su command");
                throw Java.use("java.io.IOException").${"$"}new("su not found");
            }
            return this.exec(cmd);
        };
    } catch(e) {}

    console.log("[✓] Root detection bypassed");
});
""".trimIndent(),

        "bypass_debug" to """
// Frida: 绕过反调试
Java.perform(function() {
    console.log("[+] Debug bypass loaded");

    // Debug.isDebuggerConnected
    try {
        var Debug = Java.use("android.os.Debug");
        Debug.isDebuggerConnected.implementation = function() { return false; };
        console.log("[+] isDebuggerConnected -> false");
    } catch(e) {}

    // ApplicationInfo.FLAG_DEBUGGABLE
    try {
        var AppInfo = Java.use("android.content.pm.ApplicationInfo");
        // 无法直接改，但可以 hook 检查点
    } catch(e) {}

    // TracerPid check
    try {
        var BufferedReader = Java.use("java.io.BufferedReader");
        BufferedReader.readLine.implementation = function() {
            var line = this.readLine();
            if (line && line.indexOf("TracerPid") !== -1) {
                return "TracerPid:\t0";
            }
            return line;
        };
        console.log("[+] TracerPid check bypassed");
    } catch(e) {}

    console.log("[✓] Debug detection bypassed");
});
""".trimIndent(),

        "hook_method" to """
// Frida: Hook 指定方法
// 目标: {TARGET_CLASS}.{TARGET_METHOD}
Java.perform(function() {
    var targetClass = "{TARGET_CLASS}";
    var targetMethod = "{TARGET_METHOD}";

    try {
        var cls = Java.use(targetClass);
        var methods = targetMethod ? [targetMethod] : cls.class.getDeclaredMethods().map(function(m) { return m.getName(); });

        methods.forEach(function(method) {
            try {
                cls[method].overloads.forEach(function(overload) {
                    overload.implementation = function() {
                        console.log("[+] Called " + targetClass + "." + method);
                        for (var i = 0; i < arguments.length; i++) {
                            console.log("    arg[" + i + "]: " + arguments[i]);
                        }
                        var result = this[method].apply(this, arguments);
                        console.log("    return: " + result);
                        return result;
                    };
                });
                console.log("[+] Hooked: " + method);
            } catch(e) {}
        });
    } catch(e) {
        console.log("[-] Failed to hook: " + e);
    }
});
""".trimIndent(),

        "intercept_crypto" to """
// Frida: 拦截加密调用
Java.perform(function() {
    console.log("[+] Crypto interceptor loaded");

    // Cipher
    try {
        var Cipher = Java.use("javax.crypto.Cipher");
        Cipher.doFinal.overload('[B').implementation = function(input) {
            console.log("[Cipher.doFinal] input(" + input.length + "B): " + bytesToHex(input.slice(0, 32)));
            var result = this.doFinal(input);
            console.log("[Cipher.doFinal] output(" + result.length + "B): " + bytesToHex(result.slice(0, 32)));
            return result;
        };
    } catch(e) {}

    // MessageDigest
    try {
        var MD = Java.use("java.security.MessageDigest");
        MD.digest.overload('[B').implementation = function(input) {
            var result = this.digest(input);
            console.log("[MD.digest] " + bytesToHex(result));
            return result;
        };
    } catch(e) {}

    function bytesToHex(bytes) {
        var hex = [];
        for (var i = 0; i < bytes.length; i++) {
            hex.push((bytes[i] & 0xFF).toString(16).padStart(2, '0'));
        }
        return hex.join('');
    }

    console.log("[✓] Crypto interceptor ready");
});
""".trimIndent()
    )
}
