package com.apkagent.apktools

import com.apkagent.agent.Tool
import com.apkagent.agent.ToolContext
import com.apkagent.agent.ToolRegistry
import com.apkagent.agent.ToolResult
import com.apkagent.agent.boolProp
import com.apkagent.agent.intProp
import com.apkagent.agent.schemaObject
import com.apkagent.agent.strProp
import kotlinx.serialization.json.JsonObject
import com.apkagent.shizuku.ShizukuManager
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
    return r
}

/* ---------------- APK 工具 ---------------- */

private fun resolveApk(args: JsonObject, ctx: ToolContext): File? {
    val p = args.str("apk_path")
    if (p != null) {
        val f = File(p)
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
                val f = File(p)
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
            val f = File(target)
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
        val f = File(path)
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
        val dir = if (p.isNullOrBlank()) ctx.workspace else File(p)
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
                val outDir = args.str("output_dir")?.let { File(it) }
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
        val srcDir = args.str("src_dir")?.let { File(it) }
            ?: return ToolResult.err("缺少 src_dir")
        val output = args.str("output")?.let { File(it) }
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
        val outDir = args.str("output_dir")?.let { File(it) }
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
        val apkPath = args.str("apk_path")?.let { File(it) }
            ?: return ToolResult.err("缺少 apk_path")
        Sandbox.assertReadable(apkPath, ctx.workspace, ctx.openApk)
        val scheme = args.str("scheme") ?: "both"
        val outPath = args.str("output")?.let { File(it) }
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
        val outDir = args.str("output_dir")?.let { File(it) }
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
            val f = File(p)
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
        val smaliDir = args.str("smali_dir")?.let { File(it) }
            ?: return ToolResult.err("缺少 smali_dir")
        val output = args.str("output")?.let { File(it) }
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
        val f = File(path)
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
