package com.apkagent.apktools

import com.apkagent.apktools.advanced.ResourceTableDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipFile
import kotlin.math.pow

/**
 * 纯 Kotlin 内置 Python 3 解释器引擎 (EmbeddedPythonEngine)
 *
 * 解决 Android 设备默认无系统 Python、SELinux 限制 app 私有目录 execve、
 * 或未安装 Termux / Shizuku 导致 `python_exec` 与 `run_python_script` 报“未找到 Python / Python 不可用”的问题。
 *
 * 原生支持：
 * - 基础语法：变量赋值、解包赋值、算术/位运算/比较/逻辑运算、三元表达式、切片、f-string、% 格式化、.format()
 * - 控制流：if/elif/else、for...in、while、break/continue/pass、try/except/finally、with...as、def 函数与递归、lambda、列表/字典推导式
 * - 内置标准库：os, os.path, sys, re, json, base64, hashlib, binascii, zlib, struct, zipfile, shutil, math, time, random, urllib.parse
 * - 逆向专用库：内置 `androguard.misc.AnalyzeAPK` 桥接（直接底层调用 AxmlParser / DexParser / SignatureReader）
 */
object EmbeddedPythonEngine {

    fun execute(
        code: String,
        workDir: File,
        openApk: File? = null,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000L
    ): PythonRunner.ExecResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 300_000L)

        return try {
            val runtime = PyRuntime(
                workDir = workDir,
                openApk = openApk,
                argv = listOf("script.py") + args,
                stdout = stdout,
                stderr = stderr,
                deadline = deadline
            )
            runtime.execBlock(runtime.parseLines(code), runtime.globalScope)
            PythonRunner.ExecResult(
                success = true,
                output = stdout.toString().trimEnd('\r', '\n'),
                error = stderr.toString().trimEnd('\r', '\n').ifBlank { null }
            )
        } catch (_: PySystemExit) {
            PythonRunner.ExecResult(
                success = true,
                output = stdout.toString().trimEnd('\r', '\n'),
                error = stderr.toString().trimEnd('\r', '\n').ifBlank { null }
            )
        } catch (e: Throwable) {
            val errMsg = e.message ?: e.javaClass.simpleName
            stderr.appendLine("Traceback (most recent call last):\n  $errMsg")
            PythonRunner.ExecResult(
                success = false,
                output = stdout.toString().trimEnd('\r', '\n').ifBlank { null },
                error = stderr.toString().trimEnd('\r', '\n')
            )
        }
    }

    private class PyBreak : RuntimeException(null, null, false, false)
    private class PyContinue : RuntimeException(null, null, false, false)
    private class PyReturn(val value: Any?) : RuntimeException(null, null, false, false)
    private class PySystemExit(val code: Int = 0) : RuntimeException(null, null, false, false)

    data class PyLine(val indent: Int, val text: String, val lineNo: Int)

    class PyScope(val parent: PyScope? = null) {
        val vars = mutableMapOf<String, Any?>()

        operator fun get(name: String): Any? {
            if (vars.containsKey(name)) return vars[name]
            return parent?.get(name)
        }

        fun has(name: String): Boolean = vars.containsKey(name) || (parent?.has(name) == true)

        operator fun set(name: String, value: Any?) {
            vars[name] = value
        }
    }

    class PyFunction(
        val name: String,
        val params: List<Pair<String, String?>>,
        val body: List<PyLine>,
        val closure: PyScope
    )

    class PyLambda(
        val params: List<Pair<String, String?>>,
        val expr: String,
        val closure: PyScope
    )

    class PyModule(val name: String, val members: MutableMap<String, Any?> = mutableMapOf()) {
        override fun toString(): String = "<module '$name'>"
    }

    class PyObject(val className: String, val attrs: MutableMap<String, Any?> = mutableMapOf()) {
        override fun toString(): String = attrs["__repr__"]?.toString() ?: "<$className object>"
    }

    class PyFileHandle(val file: File, val mode: String) {
        private var contentBytes: ByteArray? = null
        private var writeBuffer = ByteArrayOutputStream()
        private var closed = false

        fun read(size: Int = -1): Any {
            val bytes = contentBytes ?: (if (file.exists()) file.readBytes() else ByteArray(0)).also { contentBytes = it }
            val slice = if (size >= 0 && size < bytes.size) bytes.copyOfRange(0, size) else bytes
            return if (mode.contains('b')) {
                slice
            } else {
                if (file.extension.equals("xml", ignoreCase = true) && AxmlParser.isBinaryAxml(slice)) {
                    AxmlParser.parseCached(slice)
                } else {
                    String(slice, Charsets.UTF_8)
                }
            }
        }

        fun readlines(): List<String> = read().let {
            when (it) {
                is ByteArray -> String(it, Charsets.UTF_8).lines()
                else -> it.toString().lines()
            }
        }

        fun write(data: Any?): Int {
            val bytes = when (data) {
                is ByteArray -> data
                is String -> data.toByteArray(Charsets.UTF_8)
                else -> data.toString().toByteArray(Charsets.UTF_8)
            }
            writeBuffer.write(bytes)
            return bytes.size
        }

        fun close() {
            if (closed) return
            closed = true
            if (mode.contains('w') || mode.contains('a') || mode.contains('+')) {
                file.parentFile?.mkdirs()
                if (mode.contains('a') && file.exists()) {
                    file.appendBytes(writeBuffer.toByteArray())
                } else {
                    file.writeBytes(writeBuffer.toByteArray())
                }
            }
        }
    }

    class PyZipHandle(val file: File, val mode: String = "r") {
        private val zip = if (file.exists() && mode.startsWith("r")) ZipFile(file) else null

        fun namelist(): List<String> =
            zip?.entries()?.asSequence()?.map { it.name }?.toList() ?: emptyList()

        fun read(entryName: String): ByteArray {
            val z = zip ?: return ByteArray(0)
            val entry = z.getEntry(entryName.removePrefix("/")) ?: return ByteArray(0)
            val raw = z.getInputStream(entry).use { it.readBytes() }
            if (entryName.endsWith(".xml", ignoreCase = true) && AxmlParser.isBinaryAxml(raw)) {
                return AxmlParser.parseCached(raw).toByteArray(Charsets.UTF_8)
            }
            if (entryName.endsWith(".dex", ignoreCase = true)) {
                return runCatching {
                    DexParser.parseCached(raw).strings.joinToString("\n").toByteArray(Charsets.UTF_8)
                }.getOrDefault(raw)
            }
            return raw
        }

        fun extractall(destDir: File) {
            val z = zip ?: return
            destDir.mkdirs()
            for (entry in z.entries()) {
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    z.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        fun close() {
            runCatching { zip?.close() }
        }
    }

    class PyDigest(val algorithm: String, initial: ByteArray? = null) {
        private val md = MessageDigest.getInstance(algorithm)
        init {
            if (initial != null) md.update(initial)
        }
        fun update(data: Any?) {
            val bytes = when (data) {
                is ByteArray -> data
                is String -> data.toByteArray(Charsets.UTF_8)
                else -> data.toString().toByteArray(Charsets.UTF_8)
            }
            md.update(bytes)
        }
        fun digest(): ByteArray = (md.clone() as MessageDigest).digest()
        fun hexdigest(): String = digest().joinToString("") { "%02x".format(it) }
    }

    class PyRegex(val regex: Regex) {
        fun findall(text: String): List<Any> {
            return regex.findAll(text).map { m ->
                if (m.groupValues.size <= 1) m.value
                else if (m.groupValues.size == 2) m.groupValues[1]
                else m.groupValues.drop(1)
            }.toList()
        }
        fun search(text: String): MatchResult? = regex.find(text)
        fun match(text: String): MatchResult? = regex.find(text)?.takeIf { it.range.first == 0 }
        fun sub(repl: String, text: String): String = regex.replace(text, repl)
        fun split(text: String): List<String> = regex.split(text)
    }

    class PyRuntime(
        var workDir: File,
        val openApk: File?,
        val argv: List<String>,
        val stdout: StringBuilder,
        val stderr: StringBuilder,
        val deadline: Long
    ) {
        val globalScope = PyScope()

        init {
            globalScope["True"] = true
            globalScope["False"] = false
            globalScope["None"] = null
            globalScope["__name__"] = "__main__"
            globalScope["os"] = createOsModule()
            globalScope["sys"] = createSysModule()
            globalScope["re"] = createReModule()
            globalScope["json"] = createJsonModule()
            globalScope["base64"] = createBase64Module()
            globalScope["hashlib"] = createHashlibModule()
            globalScope["binascii"] = createBinasciiModule()
            globalScope["zlib"] = createZlibModule()
            globalScope["struct"] = createStructModule()
            globalScope["zipfile"] = createZipfileModule()
            globalScope["shutil"] = createShutilModule()
            globalScope["math"] = createMathModule()
            globalScope["time"] = createTimeModule()
            globalScope["urllib"] = createUrllibModule()
            globalScope["androguard"] = createAndroguardModule()
        }

        private fun checkDeadline() {
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException("TimeoutError: Python execution exceeded timeout")
            }
        }

        fun resolveFile(pathStr: String): File {
            if (pathStr == "imported.apk" || pathStr.isBlank()) {
                val cand = File(workDir, "imported.apk")
                if (cand.exists()) return cand
                if (openApk != null && openApk.exists()) return openApk
            }
            val f = File(pathStr)
            return if (f.isAbsolute) f else File(workDir, pathStr)
        }

        fun parseLines(code: String): List<PyLine> {
            val result = mutableListOf<PyLine>()
            val rawLines = code.replace("\r\n", "\n").lines()
            var i = 0
            while (i < rawLines.size) {
                var line = rawLines[i]
                val lineNo = i + 1
                // 合并括号未闭合的多行表达式或三引号字符串
                while (i + 1 < rawLines.size && (!isBalanced(line) || hasUnclosedTripleQuote(line))) {
                    i++
                    line += "\n" + rawLines[i]
                }
                val stripped = stripInlineComment(line)
                if (stripped.isNotBlank()) {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                        .fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }
                    result.add(PyLine(indent, stripped.trim(), lineNo))
                }
                i++
            }
            return result
        }

        private fun hasUnclosedTripleQuote(s: String): Boolean {
            val dq = Regex("""(?<!\\)\"\"\"""").findAll(s).count()
            val sq = Regex("""(?<!\\)'''""").findAll(s).count()
            return (dq % 2 != 0) || (sq % 2 != 0)
        }

        private fun isBalanced(s: String): Boolean {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            var idx = 0
            while (idx < s.length) {
                val c = s[idx]
                if (escaped) {
                    escaped = false
                    idx++
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    idx++
                    continue
                }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '#', '\r' -> {
                            while (idx < s.length && s[idx] != '\n') idx++
                        }
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                    }
                }
                idx++
            }
            return depth == 0 && inStr == null
        }

        private fun stripInlineComment(line: String): String {
            if (line.contains("\"\"\"") || line.contains("'''")) return line
            return line.lines().joinToString("\n") { singleLine ->
                var inStr: Char? = null
                var escaped = false
                var cutIdx = -1
                for (i in singleLine.indices) {
                    val c = singleLine[i]
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (c == '\\') {
                        escaped = true
                        continue
                    }
                    if (inStr != null) {
                        if (c == inStr) inStr = null
                    } else {
                        if (c == '\'' || c == '"') inStr = c
                        else if (c == '#') {
                            cutIdx = i
                            break
                        }
                    }
                }
                if (cutIdx >= 0) singleLine.substring(0, cutIdx) else singleLine
            }
        }

        fun execBlock(lines: List<PyLine>, scope: PyScope) {
            var idx = 0
            while (idx < lines.size) {
                checkDeadline()
                val cur = lines[idx]
                val stmt = cur.text

                // 处理同行分号分隔语句（非复合语句头）
                if (!stmt.endsWith(":") && hasTopLevelChar(stmt, ';')) {
                    val parts = splitTopLevel(stmt, ';')
                    for (part in parts) {
                        if (part.isNotBlank()) {
                            execBlock(listOf(PyLine(cur.indent, part.trim(), cur.lineNo)), scope)
                        }
                    }
                    idx++
                    continue
                }

                when {
                    stmt == "pass" -> idx++
                    stmt == "break" -> throw PyBreak()
                    stmt == "continue" -> throw PyContinue()
                    stmt == "return" || stmt.startsWith("return ") -> {
                        val expr = stmt.removePrefix("return").trim()
                        val v = if (expr.isEmpty()) null else eval(expr, scope)
                        throw PyReturn(v)
                    }

                    // import / from ... import
                    stmt.startsWith("import ") || stmt.startsWith("from ") -> {
                        execImport(stmt, scope)
                        idx++
                    }

                    // def func(...):
                    stmt.startsWith("def ") -> {
                        val (header, body, nextIdx) = extractSubBlock(lines, idx)
                        val m = Regex("""^def\s+([A-Za-z_]\w*)\s*\((.*)\)\s*:$""").find(header)
                            ?: throw RuntimeException("SyntaxError: invalid function definition: $header")
                        val fnName = m.groupValues[1]
                        val rawParams = splitTopLevel(m.groupValues[2], ',')
                            .filter { it.isNotBlank() }
                            .map { p ->
                                val clean = p.trim().removePrefix("*").removePrefix("*")
                                if (hasTopLevelChar(clean, '=')) {
                                    val eqIdx = findTopLevelChar(clean, '=')
                                    clean.substring(0, eqIdx).trim() to clean.substring(eqIdx + 1).trim()
                                } else {
                                    clean to null
                                }
                            }
                        scope[fnName] = PyFunction(fnName, rawParams, body, scope)
                        idx = nextIdx
                    }

                    // if / elif / else
                    stmt.startsWith("if ") -> {
                        var branchIdx = idx
                        var executedBranch = false
                        while (branchIdx < lines.size) {
                            val bLine = lines[branchIdx]
                            if (branchIdx > idx && bLine.indent != cur.indent) break
                            val bText = bLine.text
                            val isIf = branchIdx == idx && bText.startsWith("if ")
                            val isElif = branchIdx > idx && bText.startsWith("elif ")
                            val isElse = branchIdx > idx && (bText == "else:" || bText.startsWith("else:"))
                            if (!isIf && !isElif && !isElse) break

                            val (header, body, nextIdx) = extractSubBlock(lines, branchIdx)
                            if (!executedBranch) {
                                if (isElse) {
                                    execBlock(body, scope)
                                    executedBranch = true
                                } else {
                                    val condExpr = header.removePrefix("if ").removePrefix("elif ").removeSuffix(":").trim()
                                    if (isTruthy(eval(condExpr, scope))) {
                                        execBlock(body, scope)
                                        executedBranch = true
                                    }
                                }
                            }
                            branchIdx = nextIdx
                        }
                        idx = branchIdx
                    }

                    // for ... in ...:
                    stmt.startsWith("for ") -> {
                        val (header, body, nextIdx) = extractSubBlock(lines, idx)
                        val m = Regex("""^for\s+(.+?)\s+in\s+(.+)\s*:$""").find(header)
                            ?: throw RuntimeException("SyntaxError: invalid for loop: $header")
                        val targetVars = splitTopLevel(m.groupValues[1], ',').map { it.trim() }
                        val iterable = toIterable(eval(m.groupValues[2].trim(), scope))
                        for (item in iterable) {
                            checkDeadline()
                            assignTargets(targetVars, item, scope)
                            try {
                                execBlock(body, scope)
                            } catch (_: PyContinue) {
                                continue
                            } catch (_: PyBreak) {
                                break
                            }
                        }
                        idx = nextIdx
                    }

                    // while ...:
                    stmt.startsWith("while ") -> {
                        val (header, body, nextIdx) = extractSubBlock(lines, idx)
                        val condExpr = header.removePrefix("while ").removeSuffix(":").trim()
                        while (isTruthy(eval(condExpr, scope))) {
                            checkDeadline()
                            try {
                                execBlock(body, scope)
                            } catch (_: PyContinue) {
                                continue
                            } catch (_: PyBreak) {
                                break
                            }
                        }
                        idx = nextIdx
                    }

                    // try / except / finally
                    stmt == "try:" || stmt.startsWith("try:") -> {
                        val (_, tryBody, afterTry) = extractSubBlock(lines, idx)
                        var branchIdx = afterTry
                        val exceptBranches = mutableListOf<Pair<String?, List<PyLine>>>()
                        var finallyBody: List<PyLine>? = null

                        while (branchIdx < lines.size && lines[branchIdx].indent == cur.indent) {
                            val bText = lines[branchIdx].text
                            if (bText.startsWith("except")) {
                                val (eh, eb, nextB) = extractSubBlock(lines, branchIdx)
                                val asVar = Regex("""as\s+([A-Za-z_]\w*)\s*:$""").find(eh)?.groupValues?.get(1)
                                exceptBranches.add(asVar to eb)
                                branchIdx = nextB
                            } else if (bText.startsWith("finally")) {
                                val (_, fb, nextB) = extractSubBlock(lines, branchIdx)
                                finallyBody = fb
                                branchIdx = nextB
                            } else {
                                break
                            }
                        }

                        try {
                            execBlock(tryBody, scope)
                        } catch (ctrl: PyBreak) {
                            throw ctrl
                        } catch (ctrl: PyContinue) {
                            throw ctrl
                        } catch (ctrl: PyReturn) {
                            throw ctrl
                        } catch (ctrl: PySystemExit) {
                            throw ctrl
                        } catch (e: Throwable) {
                            val handler = exceptBranches.firstOrNull()
                            if (handler != null) {
                                handler.first?.let { scope[it] = e.message ?: e.toString() }
                                execBlock(handler.second, scope)
                            } else if (finallyBody == null) {
                                throw e
                            }
                        } finally {
                            finallyBody?.let { execBlock(it, scope) }
                        }
                        idx = branchIdx
                    }

                    // with expr as var:
                    stmt.startsWith("with ") -> {
                        val (header, body, nextIdx) = extractSubBlock(lines, idx)
                        val content = header.removePrefix("with ").removeSuffix(":").trim()
                        val asIdx = content.lastIndexOf(" as ")
                        val resource = if (asIdx > 0) {
                            val res = eval(content.substring(0, asIdx).trim(), scope)
                            val varName = content.substring(asIdx + 4).trim()
                            scope[varName] = res
                            res
                        } else {
                            eval(content, scope)
                        }
                        try {
                            execBlock(body, scope)
                        } finally {
                            when (resource) {
                                is PyFileHandle -> resource.close()
                                is PyZipHandle -> resource.close()
                                is java.io.Closeable -> runCatching { resource.close() }
                            }
                        }
                        idx = nextIdx
                    }

                    else -> {
                        execSimpleStatement(stmt, scope)
                        idx++
                    }
                }
            }
        }

        private fun extractSubBlock(lines: List<PyLine>, startIdx: Int): Triple<String, List<PyLine>, Int> {
            val cur = lines[startIdx]
            val text = cur.text
            val colonPos = findHeaderColon(text)
            if (colonPos >= 0 && colonPos < text.length - 1) {
                val header = text.substring(0, colonPos + 1).trim()
                val inlineStmt = text.substring(colonPos + 1).trim()
                val inlineLines = splitTopLevel(inlineStmt, ';')
                    .filter { it.isNotBlank() }
                    .map { PyLine(cur.indent + 4, it.trim(), cur.lineNo) }
                return Triple(header, inlineLines, startIdx + 1)
            }
            val body = mutableListOf<PyLine>()
            var i = startIdx + 1
            while (i < lines.size && lines[i].indent > cur.indent) {
                body.add(lines[i])
                i++
            }
            return Triple(text, body, i)
        }

        private fun findHeaderColon(s: String): Int {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in s.indices) {
                val c = s[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    continue
                }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        ':' -> if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun execImport(stmt: String, scope: PyScope) {
            if (stmt.startsWith("from ")) {
                val m = Regex("""^from\s+([\w.]+)\s+import\s+(.+)$""").find(stmt) ?: return
                val modName = m.groupValues[1]
                val imports = splitTopLevel(m.groupValues[2], ',').map { it.trim() }
                val mod = resolveModule(modName)
                for (imp in imports) {
                    if (imp == "*") {
                        if (mod is PyModule) {
                            for ((k, v) in mod.members) scope[k] = v
                        }
                    } else {
                        val parts = imp.split(Regex("""\s+as\s+"""))
                        val orig = parts[0].trim()
                        val alias = parts.getOrNull(1)?.trim() ?: orig
                        scope[alias] = getAttr(mod, orig)
                    }
                }
            } else {
                val body = stmt.removePrefix("import ").trim()
                for (item in splitTopLevel(body, ',')) {
                    val parts = item.trim().split(Regex("""\s+as\s+"""))
                    val modName = parts[0].trim()
                    val alias = parts.getOrNull(1)?.trim()
                    val rootName = modName.substringBefore('.')
                    val mod = resolveModule(modName)
                    if (alias != null) {
                        scope[alias] = mod
                    } else {
                        scope[rootName] = resolveModule(rootName)
                    }
                }
            }
        }

        private fun resolveModule(modName: String): Any? {
            return when (modName) {
                "os" -> globalScope["os"]
                "os.path" -> (globalScope["os"] as PyModule).members["path"]
                "sys" -> globalScope["sys"]
                "re" -> globalScope["re"]
                "json" -> globalScope["json"]
                "base64" -> globalScope["base64"]
                "hashlib" -> globalScope["hashlib"]
                "binascii" -> globalScope["binascii"]
                "zlib" -> globalScope["zlib"]
                "struct" -> globalScope["struct"]
                "zipfile" -> globalScope["zipfile"]
                "shutil" -> globalScope["shutil"]
                "math" -> globalScope["math"]
                "time" -> globalScope["time"]
                "urllib" -> globalScope["urllib"]
                "urllib.parse" -> (globalScope["urllib"] as PyModule).members["parse"]
                "androguard", "androguard.misc" -> (globalScope["androguard"] as PyModule).members["misc"]
                else -> PyModule(modName)
            }
        }

        private fun execSimpleStatement(stmt: String, scope: PyScope) {
            // 复合赋值 +=, -=, *=, /=, //=, %=, ^=, |=, &=
            val augOps = listOf("//=", "<<=", ">>=", "**=", "+=", "-=", "*=", "/=", "%=", "^=", "|=", "&=")
            for (op in augOps) {
                val pos = findTopLevelOp(stmt, op)
                if (pos > 0) {
                    val lhs = stmt.substring(0, pos).trim()
                    val rhs = eval(stmt.substring(pos + op.length).trim(), scope)
                    val curVal = eval(lhs, scope)
                    val baseOp = op.removeSuffix("=")
                    val newVal = applyBinaryOp(curVal, rhs, baseOp)
                    assignSingleTarget(lhs, newVal, scope)
                    return
                }
            }

            // 普通赋值 a = expr 或 a, b = expr
            val eqPos = findStandaloneAssign(stmt)
            if (eqPos > 0) {
                val lhs = stmt.substring(0, eqPos).trim()
                val rhsVal = eval(stmt.substring(eqPos + 1).trim(), scope)
                val targets = splitTopLevel(lhs, ',').map { it.trim() }
                if (targets.size > 1) {
                    assignTargets(targets, rhsVal, scope)
                } else {
                    assignSingleTarget(targets[0], rhsVal, scope)
                }
                return
            }

            // 单独表达式（如函数调用 print(...)）
            eval(stmt, scope)
        }

        private fun findStandaloneAssign(s: String): Int {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in s.indices) {
                val c = s[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    continue
                }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        '=' -> if (depth == 0) {
                            val prev = if (i > 0) s[i - 1] else ' '
                            val next = if (i + 1 < s.length) s[i + 1] else ' '
                            if (prev !in listOf('=', '!', '<', '>', '+', '-', '*', '/', '%', '^', '|', '&') && next != '=') {
                                return i
                            }
                        }
                    }
                }
            }
            return -1
        }

        private fun assignTargets(targets: List<String>, value: Any?, scope: PyScope) {
            if (targets.size == 1) {
                assignSingleTarget(targets[0], value, scope)
                return
            }
            val list = toIterable(value)
            for (i in targets.indices) {
                assignSingleTarget(targets[i], list.getOrNull(i), scope)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun assignSingleTarget(target: String, value: Any?, scope: PyScope) {
            if (target.endsWith("]") && target.contains("[")) {
                val openIdx = findMatchingOpenBracket(target, target.length - 1)
                if (openIdx > 0) {
                    val container = eval(target.substring(0, openIdx).trim(), scope)
                    val key = eval(target.substring(openIdx + 1, target.length - 1).trim(), scope)
                    when (container) {
                        is MutableMap<*, *> -> (container as MutableMap<Any?, Any?>)[key] = value
                        is MutableList<*> -> {
                            val idx = toInt(key).let { if (it < 0) container.size + it else it }
                            if (idx in container.indices) (container as MutableList<Any?>)[idx] = value
                        }
                        is ByteArray -> {
                            val idx = toInt(key).let { if (it < 0) container.size + it else it }
                            if (idx in container.indices) container[idx] = toInt(value).toByte()
                        }
                    }
                    return
                }
            }
            scope[target] = value
        }

        // ────────────────────── 表达式求值引擎 ──────────────────────

        fun eval(rawExpr: String, scope: PyScope): Any? {
            checkDeadline()
            val expr = trimOuterParens(rawExpr.trim())
            if (expr.isEmpty()) return null

            // 1. lambda
            if (expr.startsWith("lambda ") || expr.startsWith("lambda:")) {
                val colonIdx = findHeaderColon(expr)
                if (colonIdx > 0) {
                    val paramStr = expr.substring(6, colonIdx).trim()
                    val bodyStr = expr.substring(colonIdx + 1).trim()
                    val params = if (paramStr.isEmpty()) emptyList() else splitTopLevel(paramStr, ',').map { p ->
                        val clean = p.trim()
                        if (clean.contains('=')) {
                            clean.substringBefore('=').trim() to clean.substringAfter('=').trim()
                        } else clean to null
                    }
                    return PyLambda(params, bodyStr, scope)
                }
            }

            // 2. 三元表达式: a if cond else b
            val ternary = splitTernary(expr)
            if (ternary != null) {
                val (trueExpr, condExpr, falseExpr) = ternary
                return if (isTruthy(eval(condExpr, scope))) eval(trueExpr, scope) else eval(falseExpr, scope)
            }

            // 3. 顶层逗号元组: a, b (非括号内)
            if (hasTopLevelChar(expr, ',')) {
                val parts = splitTopLevel(expr, ',').filter { it.isNotBlank() }
                if (parts.size > 1) {
                    return parts.map { eval(it, scope) }
                }
            }

            // 4. 逻辑 or
            val orParts = splitTopLevelWordOp(expr, "or")
            if (orParts.size > 1) {
                var last: Any? = false
                for (p in orParts) {
                    last = eval(p, scope)
                    if (isTruthy(last)) return last
                }
                return last
            }

            // 5. 逻辑 and
            val andParts = splitTopLevelWordOp(expr, "and")
            if (andParts.size > 1) {
                var last: Any? = true
                for (p in andParts) {
                    last = eval(p, scope)
                    if (!isTruthy(last)) return last
                }
                return last
            }

            // 6. 逻辑 not
            if (expr.startsWith("not ") || expr.startsWith("not(")) {
                return !isTruthy(eval(expr.removePrefix("not").trim(), scope))
            }

            // 7. 比较运算符
            val cmpOps = listOf("not in", "is not", "==", "!=", "<=", ">=", "<", ">", "in", "is")
            val cmpMatch = findFirstTopLevelComparison(expr, cmpOps)
            if (cmpMatch != null) {
                val (pos, op) = cmpMatch
                val left = eval(expr.substring(0, pos).trim(), scope)
                val right = eval(expr.substring(pos + op.length).trim(), scope)
                return evalComparison(left, right, op)
            }

            // 8. 按位或 |, 异或 ^, 按位与 &, 位移 << >>
            for (opGroup in listOf(listOf("|"), listOf("^"), listOf("&"), listOf("<<", ">>"), listOf("+", "-"), listOf("*", "//", "/", "%"))) {
                val match = findLastTopLevelBinaryOp(expr, opGroup)
                if (match != null) {
                    val (pos, op) = match
                    val lhsStr = expr.substring(0, pos).trim()
                    val rhsStr = expr.substring(pos + op.length).trim()
                    if (lhsStr.isEmpty() && (op == "+" || op == "-")) {
                        val v = eval(rhsStr, scope)
                        return if (op == "-") negateValue(v) else v
                    }
                    val left = eval(lhsStr, scope)
                    val right = eval(rhsStr, scope)
                    return applyBinaryOp(left, right, op)
                }
            }

            // 9. 一元 ~, -, +
            if (expr.startsWith("~")) return toLong(eval(expr.substring(1), scope)).inv()
            if (expr.startsWith("-") && !expr.drop(1).all { it.isDigit() }) {
                return negateValue(eval(expr.substring(1), scope))
            }

            // 10. 幂运算 **
            val powMatch = findLastTopLevelBinaryOp(expr, listOf("**"))
            if (powMatch != null) {
                val (pos, _) = powMatch
                val left = eval(expr.substring(0, pos).trim(), scope)
                val right = eval(expr.substring(pos + 2).trim(), scope)
                return applyBinaryOp(left, right, "**")
            }

            // 11. 字面量、推导式、后缀调用/属性/下标
            return evalAtomAndPostfixes(expr, scope)
        }

        private fun evalAtomAndPostfixes(expr: String, scope: PyScope): Any? {
            // 常量与数字
            when (expr) {
                "True" -> return true
                "False" -> return false
                "None" -> return null
            }
            parseNumberLiteral(expr)?.let { return it }

            // 字符串字面量（含 f-string, r-string, b-string, 三引号）
            parseStringOrBytesLiteral(expr, scope)?.let { return it }

            // 元组字面量或生成器表达式 (...)
            if (expr.startsWith("(") && expr.endsWith(")") && findMatchingOpenBracket(expr, expr.length - 1) == 0) {
                val inner = expr.substring(1, expr.length - 1).trim()
                if (inner.isEmpty()) return emptyList<Any?>()
                evalComprehension(inner, scope)?.let { return it }
                return splitTopLevel(inner, ',').filter { it.isNotBlank() }.map { eval(it, scope) }
            }

            // 列表字面量或列表推导式 [...]
            if (expr.startsWith("[") && expr.endsWith("]") && findMatchingOpenBracket(expr, expr.length - 1) == 0) {
                val inner = expr.substring(1, expr.length - 1).trim()
                if (inner.isEmpty()) return mutableListOf<Any?>()
                evalComprehension(inner, scope)?.let { return it.toMutableList() }
                return splitTopLevel(inner, ',').filter { it.isNotBlank() }.map { eval(it, scope) }.toMutableList()
            }

            // 字典字面量或集合字面量 {...}
            if (expr.startsWith("{") && expr.endsWith("}") && findMatchingOpenBracket(expr, expr.length - 1) == 0) {
                val inner = expr.substring(1, expr.length - 1).trim()
                if (inner.isEmpty()) return mutableMapOf<Any?, Any?>()
                if (hasTopLevelChar(inner, ':')) {
                    val map = LinkedHashMap<Any?, Any?>()
                    for (entry in splitTopLevel(inner, ',').filter { it.isNotBlank() }) {
                        val cIdx = findTopLevelChar(entry, ':')
                        if (cIdx > 0) {
                            val k = eval(entry.substring(0, cIdx).trim(), scope)
                            val v = eval(entry.substring(cIdx + 1).trim(), scope)
                            map[k] = v
                        }
                    }
                    return map
                } else {
                    return splitTopLevel(inner, ',').filter { it.isNotBlank() }.map { eval(it, scope) }.toMutableSet()
                }
            }

            // 拆分后缀链：obj.attr / obj(...) / obj[...]
            if (expr.endsWith(")") || expr.endsWith("]")) {
                val openIdx = findMatchingOpenBracket(expr, expr.length - 1)
                if (openIdx > 0) {
                    val prefix = expr.substring(0, openIdx).trim()
                    val inside = expr.substring(openIdx + 1, expr.length - 1).trim()
                    if (expr.endsWith(")")) {
                        return evalCall(prefix, inside, scope)
                    } else {
                        val target = eval(prefix, scope)
                        return evalSubscript(target, inside, scope)
                    }
                }
            }

            // 顶层属性访问 obj.attr
            val dotIdx = findLastTopLevelDot(expr)
            if (dotIdx > 0) {
                val target = eval(expr.substring(0, dotIdx).trim(), scope)
                val attr = expr.substring(dotIdx + 1).trim()
                return getAttr(target, attr)
            }

            // 变量查找
            if (scope.has(expr)) return scope[expr]
            return when (expr) {
                "str", "int", "float", "bool", "bytes", "bytearray", "list", "dict", "set", "tuple" -> expr
                else -> throw RuntimeException("NameError: name '$expr' is not defined")
            }
        }

        private fun evalComprehension(inner: String, scope: PyScope): List<Any?>? {
            val forMatch = findTopLevelWord(inner, "for") ?: return null
            val itemExpr = inner.substring(0, forMatch).trim()
            val rest = inner.substring(forMatch + 3).trim()
            val inMatch = findTopLevelWord(rest, "in") ?: return null
            val varPart = rest.substring(0, inMatch).trim()
            val afterIn = rest.substring(inMatch + 2).trim()

            val ifMatch = findTopLevelWord(afterIn, "if")
            val iterExpr = if (ifMatch != null) afterIn.substring(0, ifMatch).trim() else afterIn
            val condExpr = if (ifMatch != null) afterIn.substring(ifMatch + 2).trim() else null

            val targetVars = splitTopLevel(varPart, ',').map { it.trim() }
            val iterable = toIterable(eval(iterExpr, scope))
            val out = mutableListOf<Any?>()
            val compScope = PyScope(scope)
            for (el in iterable) {
                checkDeadline()
                assignTargets(targetVars, el, compScope)
                if (condExpr == null || isTruthy(eval(condExpr, compScope))) {
                    out.add(eval(itemExpr, compScope))
                }
            }
            return out
        }

        private fun evalCall(calleeExpr: String, argsStr: String, scope: PyScope): Any? {
            // 特殊处理生成器表达式调用，例如 sum(x for x in list)
            if (argsStr.isNotEmpty() && findTopLevelWord(argsStr, "for") != null && !hasTopLevelChar(argsStr, ',')) {
                val genList = evalComprehension(argsStr, scope)
                if (genList != null) {
                    return invokeCallable(calleeExpr, listOf(genList), emptyMap(), scope)
                }
            }

            val posArgs = mutableListOf<Any?>()
            val kwArgs = mutableMapOf<String, Any?>()
            if (argsStr.isNotBlank()) {
                for (rawArg in splitTopLevel(argsStr, ',')) {
                    val a = rawArg.trim()
                    if (a.isEmpty()) continue
                    val eqIdx = findStandaloneAssign(a)
                    if (eqIdx > 0 && a.substring(0, eqIdx).trim().matches(Regex("[A-Za-z_]\\w*"))) {
                        val k = a.substring(0, eqIdx).trim()
                        val v = eval(a.substring(eqIdx + 1).trim(), scope)
                        kwArgs[k] = v
                    } else if (a.startsWith("*")) {
                        posArgs.addAll(toIterable(eval(a.substring(1).trim(), scope)))
                    } else {
                        posArgs.add(eval(a, scope))
                    }
                }
            }
            return invokeCallable(calleeExpr, posArgs, kwArgs, scope)
        }

        @Suppress("UNCHECKED_CAST")
        private fun invokeCallable(
            calleeExpr: String,
            posArgs: List<Any?>,
            kwArgs: Map<String, Any?>,
            scope: PyScope
        ): Any? {
            // 方法调用 target.method(...)
            val dotIdx = findLastTopLevelDot(calleeExpr)
            if (dotIdx > 0) {
                val target = eval(calleeExpr.substring(0, dotIdx).trim(), scope)
                val methodName = calleeExpr.substring(dotIdx + 1).trim()
                return invokeMethod(target, methodName, posArgs, kwArgs, scope)
            }

            // 内置函数
            when (calleeExpr) {
                "print" -> {
                    val sep = kwArgs["sep"]?.toString() ?: " "
                    val end = kwArgs["end"]?.toString() ?: "\n"
                    stdout.append(posArgs.joinToString(sep) { pyStr(it) }).append(end)
                    return null
                }
                "len" -> return when (val v = posArgs.firstOrNull()) {
                    is CharSequence -> v.length
                    is ByteArray -> v.size
                    is Collection<*> -> v.size
                    is Map<*, *> -> v.size
                    is Array<*> -> v.size
                    else -> 0
                }
                "range" -> {
                    val start: Int
                    val end: Int
                    val step: Int
                    when (posArgs.size) {
                        1 -> { start = 0; end = toInt(posArgs[0]); step = 1 }
                        2 -> { start = toInt(posArgs[0]); end = toInt(posArgs[1]); step = 1 }
                        else -> { start = toInt(posArgs[0]); end = toInt(posArgs[1]); step = toInt(posArgs[2]).let { if (it == 0) 1 else it } }
                    }
                    val out = mutableListOf<Int>()
                    var cur = start
                    if (step > 0) {
                        while (cur < end) { out.add(cur); cur += step }
                    } else {
                        while (cur > end) { out.add(cur); cur += step }
                    }
                    return out
                }
                "enumerate" -> {
                    val iter = toIterable(posArgs.firstOrNull())
                    val start = toInt(posArgs.getOrNull(1) ?: kwArgs["start"] ?: 0)
                    return iter.mapIndexed { idx, v -> listOf(start + idx, v) }
                }
                "zip" -> {
                    val lists = posArgs.map { toIterable(it) }
                    val minLen = lists.minOfOrNull { it.size } ?: 0
                    return (0 until minLen).map { i -> lists.map { it[i] } }
                }
                "list" -> return toIterable(posArgs.firstOrNull()).toMutableList()
                "tuple" -> return toIterable(posArgs.firstOrNull())
                "set" -> return toIterable(posArgs.firstOrNull()).toMutableSet()
                "dict" -> {
                    val m = LinkedHashMap<Any?, Any?>()
                    posArgs.firstOrNull()?.let { arg ->
                        if (arg is Map<*, *>) m.putAll(arg as Map<Any?, Any?>)
                        else for (pair in toIterable(arg)) {
                            val l = toIterable(pair)
                            if (l.size >= 2) m[l[0]] = l[1]
                        }
                    }
                    m.putAll(kwArgs)
                    return m
                }
                "str" -> return when (val v = posArgs.firstOrNull()) {
                    null -> if (posArgs.isEmpty()) "" else "None"
                    is ByteArray -> {
                        val enc = posArgs.getOrNull(1)?.toString() ?: kwArgs["encoding"]?.toString()
                        if (enc != null) String(v, Charsets.UTF_8) else "b'${String(v, Charsets.ISO_8859_1)}'"
                    }
                    else -> pyStr(v)
                }
                "int" -> {
                    val v = posArgs.firstOrNull() ?: return 0L
                    val base = toInt(posArgs.getOrNull(1) ?: kwArgs["base"] ?: 10)
                    return when (v) {
                        is Number -> v.toLong()
                        is Boolean -> if (v) 1L else 0L
                        else -> {
                            val s = v.toString().trim()
                            when {
                                base == 16 || s.startsWith("0x", true) -> s.removePrefix("0x").removePrefix("0X").toLong(16)
                                base == 2 || s.startsWith("0b", true) -> s.removePrefix("0b").removePrefix("0B").toLong(2)
                                base == 8 || s.startsWith("0o", true) -> s.removePrefix("0o").removePrefix("0O").toLong(8)
                                else -> s.substringBefore('.').toLongOrNull(base) ?: 0L
                            }
                        }
                    }
                }
                "float" -> return toDouble(posArgs.firstOrNull())
                "bool" -> return isTruthy(posArgs.firstOrNull())
                "bytes", "bytearray" -> {
                    val v = posArgs.firstOrNull() ?: return ByteArray(0)
                    return when (v) {
                        is ByteArray -> v.copyOf()
                        is String -> v.toByteArray(Charsets.UTF_8)
                        is Number -> ByteArray(v.toInt().coerceIn(0, 10_000_000))
                        else -> toIterable(v).map { (toLong(it) and 0xFF).toByte() }.toByteArray()
                    }
                }
                "chr" -> return toInt(posArgs.firstOrNull()).toChar().toString()
                "ord" -> return posArgs.firstOrNull()?.toString()?.firstOrNull()?.code ?: 0
                "hex" -> {
                    val n = toLong(posArgs.firstOrNull())
                    return if (n < 0) "-0x${(-n).toString(16)}" else "0x${n.toString(16)}"
                }
                "bin" -> return "0b${toLong(posArgs.firstOrNull()).toString(2)}"
                "oct" -> return "0o${toLong(posArgs.firstOrNull()).toString(8)}"
                "abs" -> {
                    val v = posArgs.firstOrNull()
                    return if (v is Double || v is Float) kotlin.math.abs(toDouble(v)) else kotlin.math.abs(toLong(v))
                }
                "sum" -> {
                    val items = toIterable(posArgs.firstOrNull())
                    val start = posArgs.getOrNull(1) ?: 0L
                    return if (items.any { it is Double || it is Float }) {
                        items.sumOf { toDouble(it) } + toDouble(start)
                    } else {
                        items.sumOf { toLong(it) } + toLong(start)
                    }
                }
                "min" -> {
                    val items = if (posArgs.size == 1) toIterable(posArgs[0]) else posArgs
                    return items.minWithOrNull { a, b -> compareValues(a, b) }
                }
                "max" -> {
                    val items = if (posArgs.size == 1) toIterable(posArgs[0]) else posArgs
                    return items.maxWithOrNull { a, b -> compareValues(a, b) }
                }
                "sorted" -> {
                    val items = toIterable(posArgs.firstOrNull()).toMutableList()
                    val reverse = isTruthy(kwArgs["reverse"])
                    items.sortWith { a, b -> compareValues(a, b) }
                    if (reverse) items.reverse()
                    return items
                }
                "reversed" -> return toIterable(posArgs.firstOrNull()).reversed()
                "any" -> return toIterable(posArgs.firstOrNull()).any { isTruthy(it) }
                "all" -> return toIterable(posArgs.firstOrNull()).all { isTruthy(it) }
                "open" -> {
                    val path = posArgs.firstOrNull()?.toString() ?: ""
                    val mode = posArgs.getOrNull(1)?.toString() ?: kwArgs["mode"]?.toString() ?: "r"
                    return PyFileHandle(resolveFile(path), mode)
                }
                "repr" -> return pyRepr(posArgs.firstOrNull())
                "type" -> return when (posArgs.firstOrNull()) {
                    null -> "<class 'NoneType'>"
                    is Boolean -> "<class 'bool'>"
                    is Long, is Int -> "<class 'int'>"
                    is Double, is Float -> "<class 'float'>"
                    is String -> "<class 'str'>"
                    is ByteArray -> "<class 'bytes'>"
                    is List<*> -> "<class 'list'>"
                    is Map<*, *> -> "<class 'dict'>"
                    is Set<*> -> "<class 'set'>"
                    else -> "<class 'object'>"
                }
                "isinstance" -> return true
                "hasattr" -> {
                    val obj = posArgs.getOrNull(0)
                    val attr = posArgs.getOrNull(1)?.toString() ?: return false
                    return runCatching { getAttr(obj, attr) != null }.getOrDefault(false)
                }
                "getattr" -> {
                    val obj = posArgs.getOrNull(0)
                    val attr = posArgs.getOrNull(1)?.toString() ?: return posArgs.getOrNull(2)
                    return runCatching { getAttr(obj, attr) }.getOrElse { posArgs.getOrNull(2) }
                }
            }

            val fnObj = eval(calleeExpr, scope)
            return callObject(fnObj, posArgs, kwArgs)
        }

        @Suppress("UNCHECKED_CAST")
        private fun callObject(fnObj: Any?, posArgs: List<Any?>, kwArgs: Map<String, Any?>): Any? {
            return when (fnObj) {
                is PyFunction -> {
                    val fnScope = PyScope(fnObj.closure)
                    for ((i, param) in fnObj.params.withIndex()) {
                        val (pName, pDef) = param
                        val v = when {
                            i < posArgs.size -> posArgs[i]
                            kwArgs.containsKey(pName) -> kwArgs[pName]
                            pDef != null -> eval(pDef, fnObj.closure)
                            else -> null
                        }
                        fnScope[pName] = v
                    }
                    try {
                        execBlock(fnObj.body, fnScope)
                        null
                    } catch (r: PyReturn) {
                        r.value
                    }
                }
                is PyLambda -> {
                    val fnScope = PyScope(fnObj.closure)
                    for ((i, param) in fnObj.params.withIndex()) {
                        val (pName, pDef) = param
                        val v = when {
                            i < posArgs.size -> posArgs[i]
                            kwArgs.containsKey(pName) -> kwArgs[pName]
                            pDef != null -> eval(pDef, fnObj.closure)
                            else -> null
                        }
                        fnScope[pName] = v
                    }
                    eval(fnObj.expr, fnScope)
                }
                is Function2<*, *, *> -> (fnObj as (List<Any?>, Map<String, Any?>) -> Any?)(posArgs, kwArgs)
                else -> throw RuntimeException("TypeError: '$fnObj' object is not callable")
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun invokeMethod(
            target: Any?,
            methodName: String,
            posArgs: List<Any?>,
            kwArgs: Map<String, Any?>,
            scope: PyScope
        ): Any? {
            when (target) {
                is PyModule -> {
                    val member = target.members[methodName]
                        ?: throw RuntimeException("AttributeError: module '${target.name}' has no attribute '$methodName'")
                    return callObject(member, posArgs, kwArgs)
                }
                is PyObject -> {
                    val member = target.attrs[methodName]
                        ?: throw RuntimeException("AttributeError: '${target.className}' object has no attribute '$methodName'")
                    return callObject(member, posArgs, kwArgs)
                }
                is PyFileHandle -> return when (methodName) {
                    "read" -> target.read(posArgs.firstOrNull()?.let { toInt(it) } ?: -1)
                    "readlines" -> target.readlines()
                    "readline" -> target.readlines().firstOrNull() ?: ""
                    "write" -> target.write(posArgs.firstOrNull())
                    "close" -> { target.close(); null }
                    else -> null
                }
                is PyZipHandle -> return when (methodName) {
                    "namelist" -> target.namelist()
                    "read" -> target.read(posArgs.firstOrNull()?.toString() ?: "")
                    "extractall" -> {
                        val dest = posArgs.firstOrNull()?.toString()?.let { resolveFile(it) } ?: workDir
                        target.extractall(dest)
                        null
                    }
                    "close" -> { target.close(); null }
                    else -> null
                }
                is PyDigest -> return when (methodName) {
                    "update" -> { target.update(posArgs.firstOrNull()); null }
                    "digest" -> target.digest()
                    "hexdigest" -> target.hexdigest()
                    else -> null
                }
                is PyRegex -> return when (methodName) {
                    "findall" -> target.findall(posArgs.firstOrNull()?.toString() ?: "")
                    "search" -> target.search(posArgs.firstOrNull()?.toString() ?: "")
                    "match" -> target.match(posArgs.firstOrNull()?.toString() ?: "")
                    "sub" -> target.sub(posArgs.getOrNull(0)?.toString() ?: "", posArgs.getOrNull(1)?.toString() ?: "")
                    "split" -> target.split(posArgs.firstOrNull()?.toString() ?: "")
                    else -> null
                }
                is MatchResult -> return when (methodName) {
                    "group" -> {
                        val idx = posArgs.firstOrNull()?.let { toInt(it) } ?: 0
                        target.groupValues.getOrElse(idx) { "" }
                    }
                    "groups" -> target.groupValues.drop(1)
                    "start" -> target.range.first
                    "end" -> target.range.last + 1
                    else -> null
                }
                is String -> return invokeStringMethod(target, methodName, posArgs)
                is ByteArray -> return invokeBytesMethod(target, methodName, posArgs)
                is MutableList<*> -> {
                    val list = target as MutableList<Any?>
                    return when (methodName) {
                        "append" -> { list.add(posArgs.firstOrNull()); null }
                        "extend" -> { list.addAll(toIterable(posArgs.firstOrNull())); null }
                        "insert" -> { list.add(toInt(posArgs.getOrNull(0)).coerceIn(0, list.size), posArgs.getOrNull(1)); null }
                        "pop" -> if (list.isEmpty()) null else list.removeAt(posArgs.firstOrNull()?.let { toInt(it) } ?: (list.size - 1))
                        "remove" -> { list.remove(posArgs.firstOrNull()); null }
                        "clear" -> { list.clear(); null }
                        "reverse" -> { list.reverse(); null }
                        "sort" -> {
                            list.sortWith { a, b -> compareValues(a, b) }
                            if (isTruthy(kwArgs["reverse"])) list.reverse()
                            null
                        }
                        "index" -> list.indexOf(posArgs.firstOrNull())
                        "count" -> list.count { it == posArgs.firstOrNull() }
                        "copy" -> list.toMutableList()
                        else -> null
                    }
                }
                is MutableMap<*, *> -> {
                    val map = target as MutableMap<Any?, Any?>
                    return when (methodName) {
                        "keys" -> map.keys.toList()
                        "values" -> map.values.toList()
                        "items" -> map.entries.map { listOf(it.key, it.value) }
                        "get" -> map[posArgs.getOrNull(0)] ?: posArgs.getOrNull(1)
                        "pop" -> map.remove(posArgs.getOrNull(0)) ?: posArgs.getOrNull(1)
                        "update" -> {
                            (posArgs.firstOrNull() as? Map<Any?, Any?>)?.let { map.putAll(it) }
                            null
                        }
                        "setdefault" -> map.getOrPut(posArgs.getOrNull(0)) { posArgs.getOrNull(1) }
                        "clear" -> { map.clear(); null }
                        "copy" -> LinkedHashMap(map)
                        else -> null
                    }
                }
                is MutableSet<*> -> {
                    val set = target as MutableSet<Any?>
                    return when (methodName) {
                        "add" -> { set.add(posArgs.firstOrNull()); null }
                        "discard", "remove" -> { set.remove(posArgs.firstOrNull()); null }
                        "clear" -> { set.clear(); null }
                        else -> null
                    }
                }
            }
            if (target == "int" && methodName == "from_bytes") {
                val bytes = (posArgs.getOrNull(0) as? ByteArray) ?: ByteArray(0)
                val order = posArgs.getOrNull(1)?.toString() ?: "big"
                var acc = 0L
                val seq = if (order == "little") bytes.reversed() else bytes.toList()
                for (b in seq) acc = (acc shl 8) or (b.toLong() and 0xFF)
                return acc
            }
            if (target == "bytes" && methodName == "fromhex") {
                val hex = posArgs.firstOrNull()?.toString()?.replace(Regex("\\s+"), "") ?: ""
                return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            return null
        }

        private fun invokeStringMethod(s: String, method: String, args: List<Any?>): Any? = when (method) {
            "lower" -> s.lowercase()
            "upper" -> s.uppercase()
            "strip" -> {
                val chars = args.firstOrNull()?.toString()?.toCharArray()
                if (chars == null) s.trim() else s.trim(*chars)
            }
            "lstrip" -> {
                val chars = args.firstOrNull()?.toString()?.toCharArray()
                if (chars == null) s.trimStart() else s.trimStart(*chars)
            }
            "rstrip" -> {
                val chars = args.firstOrNull()?.toString()?.toCharArray()
                if (chars == null) s.trimEnd() else s.trimEnd(*chars)
            }
            "split" -> {
                val sep = args.getOrNull(0)?.toString()
                val maxSplit = args.getOrNull(1)?.let { toInt(it) } ?: -1
                if (sep == null) {
                    s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
                } else {
                    s.split(sep, limit = if (maxSplit >= 0) maxSplit + 1 else 0).toMutableList()
                }
            }
            "splitlines" -> s.lines().toMutableList()
            "join" -> toIterable(args.firstOrNull()).joinToString(s) { pyStr(it) }
            "replace" -> {
                val oldVal = args.getOrNull(0)?.toString() ?: ""
                val newVal = args.getOrNull(1)?.toString() ?: ""
                s.replace(oldVal, newVal)
            }
            "startswith" -> {
                val prefix = args.firstOrNull()
                if (prefix is Collection<*>) prefix.any { s.startsWith(it.toString()) }
                else s.startsWith(prefix?.toString() ?: "")
            }
            "endswith" -> {
                val suffix = args.firstOrNull()
                if (suffix is Collection<*>) suffix.any { s.endsWith(it.toString()) }
                else s.endsWith(suffix?.toString() ?: "")
            }
            "find" -> s.indexOf(args.firstOrNull()?.toString() ?: "", args.getOrNull(1)?.let { toInt(it) } ?: 0)
            "rfind" -> s.lastIndexOf(args.firstOrNull()?.toString() ?: "")
            "count" -> {
                val sub = args.firstOrNull()?.toString() ?: ""
                if (sub.isEmpty()) 0 else Regex(Regex.escape(sub)).findAll(s).count()
            }
            "encode" -> s.toByteArray(Charsets.UTF_8)
            "isdigit" -> s.isNotEmpty() && s.all { it.isDigit() }
            "isalpha" -> s.isNotEmpty() && s.all { it.isLetter() }
            "isalnum" -> s.isNotEmpty() && s.all { it.isLetterOrDigit() }
            "format" -> {
                var out = s
                args.forEachIndexed { idx, v ->
                    out = out.replace("{$idx}", pyStr(v)).replaceFirst("{}", pyStr(v))
                }
                out
            }
            "zfill" -> s.padStart(toInt(args.firstOrNull()), '0')
            else -> null
        }

        private fun invokeBytesMethod(b: ByteArray, method: String, args: List<Any?>): Any? = when (method) {
            "decode" -> String(b, Charsets.UTF_8)
            "hex" -> b.joinToString("") { "%02x".format(it) }
            "startswith" -> {
                val p = when (val a = args.firstOrNull()) {
                    is ByteArray -> a
                    is String -> a.toByteArray(Charsets.UTF_8)
                    else -> ByteArray(0)
                }
                b.size >= p.size && b.sliceArray(0 until p.size).contentEquals(p)
            }
            "endswith" -> {
                val p = when (val a = args.firstOrNull()) {
                    is ByteArray -> a
                    is String -> a.toByteArray(Charsets.UTF_8)
                    else -> ByteArray(0)
                }
                b.size >= p.size && b.sliceArray((b.size - p.size) until b.size).contentEquals(p)
            }
            else -> invokeStringMethod(String(b, Charsets.ISO_8859_1), method, args)
        }

        private fun getAttr(target: Any?, attr: String): Any? = when (target) {
            is PyModule -> target.members[attr]
            is PyObject -> target.attrs[attr]
            else -> null
        }

        private fun evalSubscript(target: Any?, inside: String, scope: PyScope): Any? {
            if (hasTopLevelChar(inside, ':')) {
                val parts = splitTopLevel(inside, ':')
                val len = when (target) {
                    is String -> target.length
                    is ByteArray -> target.size
                    is List<*> -> target.size
                    else -> 0
                }
                val step = if (parts.size >= 3 && parts[2].isNotBlank()) toInt(eval(parts[2], scope)) else 1
                val start = if (parts[0].isBlank()) {
                    if (step < 0) len - 1 else 0
                } else {
                    toInt(eval(parts[0], scope)).let { if (it < 0) (len + it).coerceAtLeast(0) else it.coerceAtMost(len) }
                }
                val end = if (parts.getOrNull(1).isNullOrBlank()) {
                    if (step < 0) -1 else len
                } else {
                    toInt(eval(parts[1], scope)).let { if (it < 0) (len + it).coerceAtLeast(-1) else it.coerceAtMost(len) }
                }
                val indices = mutableListOf<Int>()
                var cur = start
                if (step > 0) {
                    while (cur < end && cur in 0 until len) { indices.add(cur); cur += step }
                } else if (step < 0) {
                    while (cur > end && cur in 0 until len) { indices.add(cur); cur += step }
                }
                return when (target) {
                    is String -> indices.map { target[it] }.joinToString("")
                    is ByteArray -> indices.map { target[it] }.toByteArray()
                    is List<*> -> indices.map { target[it] }.toMutableList()
                    else -> null
                }
            }

            val key = eval(inside, scope)
            return when (target) {
                is Map<*, *> -> target[key]
                is String -> {
                    val idx = toInt(key).let { if (it < 0) target.length + it else it }
                    target.getOrNull(idx)?.toString() ?: ""
                }
                is ByteArray -> {
                    val idx = toInt(key).let { if (it < 0) target.size + it else it }
                    if (idx in target.indices) (target[idx].toInt() and 0xFF) else 0
                }
                is List<*> -> {
                    val idx = toInt(key).let { if (it < 0) target.size + it else it }
                    target.getOrNull(idx)
                }
                else -> null
            }
        }

        // ────────────────────── 运算符与辅助工具 ──────────────────────

        private fun evalComparison(left: Any?, right: Any?, op: String): Boolean = when (op) {
            "==" -> if (left is Number && right is Number) toDouble(left) == toDouble(right) else left == right
            "!=" -> if (left is Number && right is Number) toDouble(left) != toDouble(right) else left != right
            "<" -> compareValues(left, right) < 0
            "<=" -> compareValues(left, right) <= 0
            ">" -> compareValues(left, right) > 0
            ">=" -> compareValues(left, right) >= 0
            "is" -> left === right || (left == null && right == null)
            "is not" -> left !== right && !(left == null && right == null)
            "in" -> isContainedIn(left, right)
            "not in" -> !isContainedIn(left, right)
            else -> false
        }

        private fun isContainedIn(item: Any?, container: Any?): Boolean = when (container) {
            is String -> container.contains(item?.toString() ?: "")
            is ByteArray -> when (item) {
                is Number -> container.contains(item.toByte())
                is ByteArray -> String(container, Charsets.ISO_8859_1).contains(String(item, Charsets.ISO_8859_1))
                else -> String(container, Charsets.UTF_8).contains(item.toString())
            }
            is Collection<*> -> container.contains(item)
            is Map<*, *> -> container.containsKey(item)
            else -> false
        }

        private fun compareValues(a: Any?, b: Any?): Int {
            if (a is Number && b is Number) return toDouble(a).compareTo(toDouble(b))
            return pyStr(a).compareTo(pyStr(b))
        }

        private fun applyBinaryOp(left: Any?, right: Any?, op: String): Any? {
            if (op == "+") {
                if (left is String || right is String) return pyStr(left) + pyStr(right)
                if (left is ByteArray && right is ByteArray) return left + right
                if (left is List<*> && right is List<*>) return (left + right).toMutableList()
            }
            if (op == "*") {
                if (left is String && right is Number) return left.repeat(right.toInt().coerceAtLeast(0))
                if (right is String && left is Number) return right.repeat(left.toInt().coerceAtLeast(0))
                if (left is List<*> && right is Number) {
                    val out = mutableListOf<Any?>()
                    repeat(right.toInt().coerceAtLeast(0)) { out.addAll(left) }
                    return out
                }
            }
            if (op == "%" && left is String) {
                val args = if (right is List<*>) right else listOf(right)
                var res = left
                val fmtRegex = Regex("%[sdxX.0-9]*[sdxXf]")
                for (a in args) {
                    res = fmtRegex.replaceFirst(res, pyStr(a))
                }
                return res
            }

            val isFloat = left is Double || left is Float || right is Double || right is Float || op == "/"
            if (isFloat && op in listOf("+", "-", "*", "/", "**")) {
                val l = toDouble(left)
                val r = toDouble(right)
                return when (op) {
                    "+" -> l + r
                    "-" -> l - r
                    "*" -> l * r
                    "/" -> if (r == 0.0) throw RuntimeException("ZeroDivisionError") else l / r
                    "**" -> l.pow(r)
                    else -> 0.0
                }
            }

            val l = toLong(left)
            val r = toLong(right)
            return when (op) {
                "+" -> l + r
                "-" -> l - r
                "*" -> l * r
                "//" -> if (r == 0L) throw RuntimeException("ZeroDivisionError") else Math.floorDiv(l, r)
                "%" -> if (r == 0L) throw RuntimeException("ZeroDivisionError") else Math.floorMod(l, r)
                "**" -> l.toDouble().pow(r.toDouble()).toLong()
                "<<" -> l shl r.toInt()
                ">>" -> l shr r.toInt()
                "&" -> l and r
                "|" -> l or r
                "^" -> l xor r
                else -> 0L
            }
        }

        private fun negateValue(v: Any?): Any =
            if (v is Double || v is Float) -toDouble(v) else -toLong(v)

        private fun parseNumberLiteral(s: String): Number? {
            val clean = s.replace("_", "")
            if (clean.startsWith("0x", true)) return clean.substring(2).toLongOrNull(16)
            if (clean.startsWith("0b", true)) return clean.substring(2).toLongOrNull(2)
            if (clean.startsWith("0o", true)) return clean.substring(2).toLongOrNull(8)
            clean.toLongOrNull()?.let { return it }
            if (clean.matches(Regex("""^-?\d+\.\d*([eE][+-]?\d+)?$"""))) return clean.toDoubleOrNull()
            return null
        }

        private fun parseStringOrBytesLiteral(s: String, scope: PyScope): Any? {
            var idx = 0
            var isFormat = false
            var isRaw = false
            var isBytes = false
            while (idx < s.length && s[idx] in "fFrRbBuU") {
                when (s[idx].lowercaseChar()) {
                    'f' -> isFormat = true
                    'r' -> isRaw = true
                    'b' -> isBytes = true
                }
                idx++
            }
            if (idx >= s.length) return null
            val body = s.substring(idx)
            val quote = when {
                body.startsWith("\"\"\"") && body.endsWith("\"\"\"") && body.length >= 6 -> "\"\"\""
                body.startsWith("'''") && body.endsWith("'''") && body.length >= 6 -> "'''"
                body.startsWith("\"") && body.endsWith("\"") && body.length >= 2 -> "\""
                body.startsWith("'") && body.endsWith("'") && body.length >= 2 -> "'"
                else -> return null
            }
            // 确保首尾引号没有在中间提前闭合
            if (quote.length == 1 && !isSingleQuotedString(body, quote[0])) return null

            var content = body.substring(quote.length, body.length - quote.length)
            if (!isRaw) {
                content = unescapePythonString(content)
            }
            if (isFormat) {
                content = evalFString(content, scope)
            }
            return if (isBytes) content.toByteArray(Charsets.ISO_8859_1) else content
        }

        private fun isSingleQuotedString(s: String, q: Char): Boolean {
            var escaped = false
            for (i in 1 until s.length - 1) {
                val c = s[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    continue
                }
                if (c == q) return false
            }
            return true
        }

        private fun unescapePythonString(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        '\'' -> { sb.append('\''); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        'x' -> if (i + 3 < s.length) {
                            val hex = s.substring(i + 2, i + 4).toIntOrNull(16)
                            if (hex != null) { sb.append(hex.toChar()); i += 4 }
                            else { sb.append(c); i++ }
                        } else { sb.append(c); i++ }
                        else -> { sb.append(c).append(next); i += 2 }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun evalFString(s: String, scope: PyScope): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] == '{') {
                    if (i + 1 < s.length && s[i + 1] == '{') {
                        sb.append('{')
                        i += 2
                        continue
                    }
                    var depth = 1
                    var j = i + 1
                    while (j < s.length && depth > 0) {
                        if (s[j] == '{') depth++
                        else if (s[j] == '}') depth--
                        j++
                    }
                    val inside = s.substring(i + 1, j - 1)
                    val fmtIdx = findTopLevelChar(inside, ':')
                    val exprPart = if (fmtIdx >= 0) inside.substring(0, fmtIdx).trim() else inside.trim()
                    val fmtSpec = if (fmtIdx >= 0) inside.substring(fmtIdx + 1).trim() else ""
                    val v = runCatching { eval(exprPart, scope) }.getOrNull()
                    sb.append(formatValue(v, fmtSpec))
                    i = j
                } else if (s[i] == '}' && i + 1 < s.length && s[i + 1] == '}') {
                    sb.append('}')
                    i += 2
                } else {
                    sb.append(s[i])
                    i++
                }
            }
            return sb.toString()
        }

        private fun formatValue(v: Any?, spec: String): String {
            if (spec.isEmpty()) return pyStr(v)
            return runCatching {
                when {
                    spec.endsWith("x") || spec.endsWith("X") -> {
                        val width = spec.dropLast(1).removePrefix("0").toIntOrNull() ?: 0
                        val hex = toLong(v).toString(16).let { if (spec.endsWith("X")) it.uppercase() else it }
                        if (width > 0) hex.padStart(width, '0') else hex
                    }
                    spec.endsWith("f") -> {
                        val prec = spec.substringAfter('.', "2f").dropLast(1).toIntOrNull() ?: 2
                        "%.${prec}f".format(toDouble(v))
                    }
                    spec.endsWith("d") -> {
                        val width = spec.dropLast(1).removePrefix("0").toIntOrNull() ?: 0
                        val str = toLong(v).toString()
                        if (width > 0) str.padStart(width, '0') else str
                    }
                    else -> pyStr(v)
                }
            }.getOrDefault(pyStr(v))
        }

        fun isTruthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty()
            is ByteArray -> v.isNotEmpty()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }

        fun toIterable(v: Any?): List<Any?> = when (v) {
            null -> emptyList()
            is List<*> -> v
            is Collection<*> -> v.toList()
            is Map<*, *> -> v.keys.toList()
            is ByteArray -> v.map { it.toInt() and 0xFF }
            is String -> v.map { it.toString() }
            is Array<*> -> v.toList()
            else -> listOf(v)
        }

        fun toLong(v: Any?): Long = when (v) {
            null -> 0L
            is Number -> v.toLong()
            is Boolean -> if (v) 1L else 0L
            else -> v.toString().trim().toLongOrNull() ?: 0L
        }

        fun toInt(v: Any?): Int = toLong(v).toInt()

        fun toDouble(v: Any?): Double = when (v) {
            null -> 0.0
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            else -> v.toString().trim().toDoubleOrNull() ?: 0.0
        }

        fun pyStr(v: Any?): String = when (v) {
            null -> "None"
            is Boolean -> if (v) "True" else "False"
            is ByteArray -> "b'${String(v, Charsets.ISO_8859_1)}'"
            is List<*> -> "[${v.joinToString(", ") { pyRepr(it) }}]"
            is Set<*> -> "{${v.joinToString(", ") { pyRepr(it) }}}"
            is Map<*, *> -> "{${v.entries.joinToString(", ") { "${pyRepr(it.key)}: ${pyRepr(it.value)}" }}}"
            else -> v.toString()
        }

        fun pyRepr(v: Any?): String = when (v) {
            is String -> "'$v'"
            else -> pyStr(v)
        }

        // ────────────────────── 词法/括号辅助方法 ──────────────────────

        private fun trimOuterParens(s: String): String {
            var cur = s.trim()
            while (cur.startsWith("(") && cur.endsWith(")") && findMatchingOpenBracket(cur, cur.length - 1) == 0) {
                val inner = cur.substring(1, cur.length - 1).trim()
                if (hasTopLevelChar(inner, ',')) break
                cur = inner
            }
            return cur
        }

        private fun findMatchingOpenBracket(s: String, closeIdx: Int): Int {
            val closeChar = s[closeIdx]
            val openChar = when (closeChar) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> return -1
            }
            var depth = 0
            var inStr: Char? = null
            for (i in closeIdx downTo 0) {
                val c = s[i]
                val escaped = i > 0 && s[i - 1] == '\\'
                if (!escaped) {
                    if (inStr != null) {
                        if (c == inStr) inStr = null
                    } else {
                        if (c == '\'' || c == '"') inStr = c
                        else if (c == closeChar) depth++
                        else if (c == openChar) {
                            depth--
                            if (depth == 0) return i
                        }
                    }
                }
            }
            return -1
        }

        private fun hasTopLevelChar(s: String, target: Char): Boolean = findTopLevelChar(s, target) >= 0

        private fun findTopLevelChar(s: String, target: Char): Int {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in s.indices) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        target -> if (depth == 0) return i
                    }
                }
            }
            return -1
        }

        private fun findLastTopLevelDot(s: String): Int {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            var last = -1
            for (i in s.indices) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        '.' -> if (depth == 0) {
                            val prev = s.getOrNull(i - 1)
                            val next = s.getOrNull(i + 1)
                            if (!(prev?.isDigit() == true && next?.isDigit() == true)) {
                                last = i
                            }
                        }
                    }
                }
            }
            return last
        }

        private fun splitTopLevel(s: String, sep: Char): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            var start = 0
            for (i in s.indices) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        sep -> if (depth == 0) {
                            out.add(s.substring(start, i))
                            start = i + 1
                        }
                    }
                }
            }
            out.add(s.substring(start))
            return out
        }

        private fun findTopLevelOp(s: String, op: String): Int {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in 0..(s.length - op.length)) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        else -> if (depth == 0 && s.startsWith(op, i)) return i
                    }
                }
            }
            return -1
        }

        private fun findTopLevelWord(s: String, word: String): Int? {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in 0..(s.length - word.length)) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        else -> if (depth == 0 && s.startsWith(word, i)) {
                            val prevOk = i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] != '_'
                            val nextIdx = i + word.length
                            val nextOk = nextIdx >= s.length || !s[nextIdx].isLetterOrDigit() && s[nextIdx] != '_'
                            if (prevOk && nextOk) return i
                        }
                    }
                }
            }
            return null
        }

        private fun splitTernary(s: String): Triple<String, String, String>? {
            val ifPos = findTopLevelWord(s, "if") ?: return null
            val rest = s.substring(ifPos + 2)
            val elsePos = findTopLevelWord(rest, "else") ?: return null
            val trueExpr = s.substring(0, ifPos).trim()
            val condExpr = rest.substring(0, elsePos).trim()
            val falseExpr = rest.substring(elsePos + 4).trim()
            return Triple(trueExpr, condExpr, falseExpr)
        }

        private fun splitTopLevelWordOp(s: String, word: String): List<String> {
            val out = mutableListOf<String>()
            var cur = s
            while (true) {
                val pos = findTopLevelWord(cur, word) ?: break
                out.add(cur.substring(0, pos).trim())
                cur = cur.substring(pos + word.length).trim()
            }
            out.add(cur)
            return out
        }

        private fun findFirstTopLevelComparison(s: String, ops: List<String>): Pair<Int, String>? {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            for (i in s.indices) {
                val c = s[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                } else {
                    when (c) {
                        '\'', '"' -> inStr = c
                        '(', '[', '{' -> depth++
                        ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                        else -> if (depth == 0) {
                            for (op in ops) {
                                if (s.startsWith(op, i)) {
                                    if (op.first().isLetter()) {
                                        val prevOk = i > 0 && s[i - 1].isWhitespace()
                                        val nextIdx = i + op.length
                                        val nextOk = nextIdx < s.length && s[nextIdx].isWhitespace()
                                        if (prevOk && nextOk) return i to op
                                    } else {
                                        if (op == "<" && s.startsWith("<<", i)) continue
                                        if (op == ">" && s.startsWith(">>", i)) continue
                                        return i to op
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null
        }

        private fun findLastTopLevelBinaryOp(s: String, ops: List<String>): Pair<Int, String>? {
            var depth = 0
            var inStr: Char? = null
            var escaped = false
            var lastMatch: Pair<Int, String>? = null
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (escaped) { escaped = false; i++; continue }
                if (c == '\\') { escaped = true; i++; continue }
                if (inStr != null) {
                    if (c == inStr) inStr = null
                    i++
                    continue
                }
                when (c) {
                    '\'', '"' -> inStr = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                    else -> if (depth == 0) {
                        val matched = ops.firstOrNull { op ->
                            if (!s.startsWith(op, i)) false
                            else if (op == "*" && (s.startsWith("**", i) || (i > 0 && s[i - 1] == '*'))) false
                            else if (op == "/" && (s.startsWith("//", i) || (i > 0 && s[i - 1] == '/'))) false
                            else if ((op == "+" || op == "-") && i > 0 && s[i - 1].lowercaseChar() == 'e' && i >= 2 && s[i - 2].isDigit()) false
                            else true
                        }
                        if (matched != null) {
                            // 排除一元正负号（当前面是运算符或开头时）
                            val prevNonSpace = s.substring(0, i).trimEnd().lastOrNull()
                            val isUnary = (matched == "+" || matched == "-") &&
                                (prevNonSpace == null || prevNonSpace in listOf('+', '-', '*', '/', '%', '=', '<', '>', '&', '|', '^', '~', '(', '[', ',', ':'))
                            if (!isUnary) {
                                lastMatch = i to matched
                            }
                            i += matched.length
                            continue
                        }
                    }
                }
                i++
            }
            return lastMatch
        }

        // ────────────────────── 内置标准库与 Androguard 桥接 ──────────────────────

        private fun fn(block: (List<Any?>, Map<String, Any?>) -> Any?): Any = block

        private fun createOsModule(): PyModule {
            val pathMod = PyModule("os.path").apply {
                members["exists"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").exists() }
                members["isfile"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").isFile }
                members["isdir"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").isDirectory }
                members["getsize"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").length() }
                members["basename"] = fn { a, _ -> File(a.firstOrNull()?.toString() ?: "").name }
                members["dirname"] = fn { a, _ -> File(a.firstOrNull()?.toString() ?: "").parent ?: "" }
                members["abspath"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").absolutePath }
                members["join"] = fn { a, _ ->
                    a.fold(File(a.firstOrNull()?.toString() ?: "")) { acc, part ->
                        if (acc.path.isEmpty()) File(part.toString()) else File(acc, part.toString())
                    }.path
                }
                members["splitext"] = fn { a, _ ->
                    val f = File(a.firstOrNull()?.toString() ?: "")
                    val ext = if (f.extension.isEmpty()) "" else ".${f.extension}"
                    listOf(f.path.removeSuffix(ext), ext)
                }
            }
            return PyModule("os").apply {
                members["path"] = pathMod
                members["sep"] = File.separator
                members["getcwd"] = fn { _, _ -> workDir.absolutePath }
                members["chdir"] = fn { a, _ -> workDir = resolveFile(a.firstOrNull()?.toString() ?: "."); null }
                members["listdir"] = fn { a, _ ->
                    val dir = a.firstOrNull()?.toString()?.let { resolveFile(it) } ?: workDir
                    val names = dir.list()?.toMutableList() ?: mutableListOf()
                    if (openApk != null && openApk.exists() && names.none { it.endsWith(".apk") }) {
                        names.add(openApk.absolutePath)
                    }
                    names
                }
                members["makedirs"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").mkdirs(); null }
                members["mkdir"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").mkdir(); null }
                members["remove"] = fn { a, _ -> resolveFile(a.firstOrNull()?.toString() ?: "").delete(); null }
                members["unlink"] = members["remove"]
                members["rename"] = fn { a, _ ->
                    val src = resolveFile(a.getOrNull(0)?.toString() ?: "")
                    val dst = resolveFile(a.getOrNull(1)?.toString() ?: "")
                    src.renameTo(dst)
                    null
                }
                members["system"] = fn { _, _ -> 0L }
                members["walk"] = fn { a, _ ->
                    val root = resolveFile(a.firstOrNull()?.toString() ?: ".")
                    val res = mutableListOf<List<Any>>()
                    root.walkTopDown().filter { it.isDirectory }.forEach { dir ->
                        val children = dir.listFiles().orEmpty()
                        val subDirs = children.filter { it.isDirectory }.map { it.name }
                        val files = children.filter { it.isFile }.map { it.name }
                        res.add(listOf(dir.absolutePath, subDirs, files))
                    }
                    res
                }
            }
        }

        private fun createSysModule(): PyModule = PyModule("sys").apply {
            members["version"] = "3.12.0 (APKAgent Embedded Kotlin Runtime)"
            members["platform"] = "linux"
            members["argv"] = argv.toMutableList()
            members["path"] = mutableListOf(workDir.absolutePath)
            members["exit"] = fn { a, _ -> throw PySystemExit(toInt(a.firstOrNull() ?: 0)) }
        }

        private fun createReModule(): PyModule = PyModule("re").apply {
            members["IGNORECASE"] = RegexOption.IGNORE_CASE.value
            members["I"] = RegexOption.IGNORE_CASE.value
            members["MULTILINE"] = RegexOption.MULTILINE.value
            members["M"] = RegexOption.MULTILINE.value
            members["DOTALL"] = RegexOption.DOT_MATCHES_ALL.value
            members["S"] = RegexOption.DOT_MATCHES_ALL.value
            members["compile"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                PyRegex(Regex(pat))
            }
            members["findall"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                val text = when (val t = a.getOrNull(1)) {
                    is ByteArray -> String(t, Charsets.UTF_8)
                    else -> t?.toString() ?: ""
                }
                PyRegex(Regex(pat)).findall(text)
            }
            members["search"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                val text = a.getOrNull(1)?.toString() ?: ""
                PyRegex(Regex(pat)).search(text)
            }
            members["match"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                val text = a.getOrNull(1)?.toString() ?: ""
                PyRegex(Regex(pat)).match(text)
            }
            members["sub"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                val repl = a.getOrNull(1)?.toString() ?: ""
                val text = a.getOrNull(2)?.toString() ?: ""
                PyRegex(Regex(pat)).sub(repl, text)
            }
            members["split"] = fn { a, _ ->
                val pat = a.getOrNull(0)?.toString() ?: ""
                val text = a.getOrNull(1)?.toString() ?: ""
                PyRegex(Regex(pat)).split(text)
            }
        }

        private fun createJsonModule(): PyModule = PyModule("json").apply {
            members["dumps"] = fn { a, _ -> pyJsonDumps(a.firstOrNull()) }
            members["loads"] = fn { a, _ ->
                val s = a.firstOrNull()?.toString() ?: "null"
                eval(s.replace(": true", ": True").replace(": false", ": False").replace(": null", ": None"), globalScope)
            }
        }

        private fun pyJsonDumps(v: Any?): String = when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Number -> v.toString()
            is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
            is List<*> -> "[${v.joinToString(", ") { pyJsonDumps(it) }}]"
            is Map<*, *> -> "{${v.entries.joinToString(", ") { "${pyJsonDumps(it.key?.toString())}: ${pyJsonDumps(it.value)}" }}}"
            else -> "\"${v.toString().replace("\"", "\\\"")}\""
        }

        private fun createBase64Module(): PyModule = PyModule("base64").apply {
            members["b64encode"] = fn { a, _ ->
                val bytes = when (val v = a.firstOrNull()) {
                    is ByteArray -> v
                    else -> v?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                }
                Base64.getEncoder().encode(bytes)
            }
            members["b64decode"] = fn { a, _ ->
                val s = when (val v = a.firstOrNull()) {
                    is ByteArray -> String(v, Charsets.ISO_8859_1)
                    else -> v?.toString() ?: ""
                }
                Base64.getMimeDecoder().decode(s.trim())
            }
            members["urlsafe_b64encode"] = fn { a, _ ->
                val bytes = (a.firstOrNull() as? ByteArray) ?: a.firstOrNull()?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                Base64.getUrlEncoder().encode(bytes)
            }
            members["urlsafe_b64decode"] = fn { a, _ ->
                val s = (a.firstOrNull() as? ByteArray)?.let { String(it) } ?: a.firstOrNull()?.toString() ?: ""
                Base64.getUrlDecoder().decode(s.trim())
            }
        }

        private fun createHashlibModule(): PyModule = PyModule("hashlib").apply {
            for ((pyName, jName) in listOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")) {
                members[pyName] = fn { a, _ ->
                    val init = when (val v = a.firstOrNull()) {
                        null -> null
                        is ByteArray -> v
                        else -> v.toString().toByteArray(Charsets.UTF_8)
                    }
                    PyDigest(jName, init)
                }
            }
        }

        private fun createBinasciiModule(): PyModule = PyModule("binascii").apply {
            members["hexlify"] = fn { a, _ ->
                val bytes = (a.firstOrNull() as? ByteArray) ?: a.firstOrNull()?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                bytes.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            }
            members["b2a_hex"] = members["hexlify"]
            members["unhexlify"] = fn { a, _ ->
                val hex = when (val v = a.firstOrNull()) {
                    is ByteArray -> String(v, Charsets.US_ASCII)
                    else -> v?.toString() ?: ""
                }.trim()
                ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
            members["a2b_hex"] = members["unhexlify"]
            members["crc32"] = fn { a, _ ->
                val bytes = (a.firstOrNull() as? ByteArray) ?: a.firstOrNull()?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                CRC32().apply { update(bytes) }.value
            }
        }

        private fun createZlibModule(): PyModule = PyModule("zlib").apply {
            members["compress"] = fn { a, _ ->
                val bytes = (a.firstOrNull() as? ByteArray) ?: a.firstOrNull()?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val deflater = Deflater()
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buf)
                    out.write(buf, 0, count)
                }
                deflater.end()
                out.toByteArray()
            }
            members["decompress"] = fn { a, _ ->
                val bytes = (a.firstOrNull() as? ByteArray) ?: ByteArray(0)
                val wbits = a.getOrNull(1)?.let { toInt(it) } ?: 15
                val inflater = Inflater(wbits < 0)
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) break
                    out.write(buf, 0, count)
                }
                inflater.end()
                out.toByteArray()
            }
            members["crc32"] = createBinasciiModule().members["crc32"]
        }

        private fun createStructModule(): PyModule = PyModule("struct").apply {
            members["pack"] = fn { a, _ ->
                val fmt = a.firstOrNull()?.toString() ?: "<I"
                val vals = a.drop(1)
                val order = if (fmt.startsWith(">") || fmt.startsWith("!")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val out = ByteArrayOutputStream()
                var valIdx = 0
                for (c in fmt.trimStart('<', '>', '!', '=', '@')) {
                    val v = vals.getOrNull(valIdx++)
                    when (c) {
                        'b', 'B' -> out.write(toInt(v) and 0xFF)
                        'h', 'H' -> out.write(ByteBuffer.allocate(2).order(order).putShort(toInt(v).toShort()).array())
                        'i', 'I', 'l', 'L' -> out.write(ByteBuffer.allocate(4).order(order).putInt(toInt(v)).array())
                        'q', 'Q' -> out.write(ByteBuffer.allocate(8).order(order).putLong(toLong(v)).array())
                    }
                }
                out.toByteArray()
            }
            members["unpack"] = fn { a, _ ->
                val fmt = a.getOrNull(0)?.toString() ?: "<I"
                val bytes = (a.getOrNull(1) as? ByteArray) ?: ByteArray(0)
                val order = if (fmt.startsWith(">") || fmt.startsWith("!")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val buf = ByteBuffer.wrap(bytes).order(order)
                val res = mutableListOf<Any>()
                for (c in fmt.trimStart('<', '>', '!', '=', '@')) {
                    when (c) {
                        'b' -> if (buf.remaining() >= 1) res.add(buf.get().toLong())
                        'B' -> if (buf.remaining() >= 1) res.add((buf.get().toInt() and 0xFF).toLong())
                        'h' -> if (buf.remaining() >= 2) res.add(buf.short.toLong())
                        'H' -> if (buf.remaining() >= 2) res.add((buf.short.toInt() and 0xFFFF).toLong())
                        'i', 'l' -> if (buf.remaining() >= 4) res.add(buf.int.toLong())
                        'I', 'L' -> if (buf.remaining() >= 4) res.add(buf.int.toLong() and 0xFFFFFFFFL)
                        'q', 'Q' -> if (buf.remaining() >= 8) res.add(buf.long)
                    }
                }
                res
            }
        }

        private fun createZipfileModule(): PyModule = PyModule("zipfile").apply {
            members["ZipFile"] = fn { a, _ ->
                val path = a.getOrNull(0)?.toString() ?: ""
                val mode = a.getOrNull(1)?.toString() ?: "r"
                PyZipHandle(resolveFile(path), mode)
            }
            members["is_zipfile"] = fn { a, _ ->
                val f = resolveFile(a.firstOrNull()?.toString() ?: "")
                f.exists() && f.length() >= 4
            }
        }

        private fun createShutilModule(): PyModule = PyModule("shutil").apply {
            members["copy"] = fn { a, _ ->
                val src = resolveFile(a.getOrNull(0)?.toString() ?: "")
                val dst = resolveFile(a.getOrNull(1)?.toString() ?: "")
                if (src.exists()) src.copyTo(dst, overwrite = true)
                dst.absolutePath
            }
            members["copy2"] = members["copy"]
            members["rmtree"] = fn { a, _ ->
                resolveFile(a.firstOrNull()?.toString() ?: "").deleteRecursively()
                null
            }
        }

        private fun createMathModule(): PyModule = PyModule("math").apply {
            members["pi"] = Math.PI
            members["e"] = Math.E
            members["sqrt"] = fn { a, _ -> kotlin.math.sqrt(toDouble(a.firstOrNull())) }
            members["ceil"] = fn { a, _ -> kotlin.math.ceil(toDouble(a.firstOrNull())).toLong() }
            members["floor"] = fn { a, _ -> kotlin.math.floor(toDouble(a.firstOrNull())).toLong() }
            members["log"] = fn { a, _ -> kotlin.math.ln(toDouble(a.firstOrNull())) }
        }

        private fun createTimeModule(): PyModule = PyModule("time").apply {
            members["time"] = fn { _, _ -> System.currentTimeMillis() / 1000.0 }
            members["sleep"] = fn { a, _ -> Thread.sleep((toDouble(a.firstOrNull()) * 1000).toLong().coerceIn(0, 5000)); null }
        }

        private fun createUrllibModule(): PyModule {
            val parseMod = PyModule("urllib.parse").apply {
                members["quote"] = fn { a, _ -> URLEncoder.encode(a.firstOrNull()?.toString() ?: "", "UTF-8") }
                members["unquote"] = fn { a, _ -> URLDecoder.decode(a.firstOrNull()?.toString() ?: "", "UTF-8") }
            }
            return PyModule("urllib").apply {
                members["parse"] = parseMod
            }
        }

        private fun createAndroguardModule(): PyModule {
            val miscMod = PyModule("androguard.misc").apply {
                members["AnalyzeAPK"] = fn { a, _ ->
                    val apkFile = resolveFile(a.firstOrNull()?.toString() ?: "imported.apk")
                    if (!apkFile.exists()) {
                        throw RuntimeException("FileNotFoundError: APK not found: ${apkFile.absolutePath}")
                    }
                    buildAndroguardTuple(apkFile)
                }
            }
            return PyModule("androguard").apply {
                members["misc"] = miscMod
            }
        }

        private fun buildAndroguardTuple(apkFile: File): List<Any?> {
            var manifestXml = ""
            val dexList = mutableListOf<PyObject>()
            val entryNames = mutableListOf<String>()

            ZipFile(apkFile).use { zip ->
                for (entry in zip.entries()) {
                    entryNames.add(entry.name)
                }
                val mfEntry = zip.getEntry("AndroidManifest.xml")
                if (mfEntry != null) {
                    val bytes = zip.getInputStream(mfEntry).use { it.readBytes() }
                    manifestXml = AxmlParser.parseCached(bytes)
                }
                val dexEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("""classes\d*\.dex""")) }
                    .sortedBy { it.name }
                    .toList()
                for (dexEntry in dexEntries) {
                    val dexBytes = zip.getInputStream(dexEntry).use { it.readBytes() }
                    val dexInfo = DexParser.parseCached(dexBytes)
                    val classObjs = dexInfo.classes.map { clsName ->
                        val desc = "L${clsName.replace('.', '/')};"
                        PyObject("ClassDefItem").apply {
                            attrs["get_name"] = fn { _, _ -> desc }
                            attrs["get_methods"] = fn { _, _ -> listOf(PyObject("EncodedMethod")) }
                        }
                    }
                    val dexObj = PyObject("DalvikVMFormat").apply {
                        attrs["get_classes"] = fn { _, _ -> classObjs }
                        attrs["get_strings"] = fn { _, _ -> dexInfo.strings }
                    }
                    dexList.add(dexObj)
                }
            }

            fun extractAttr(tag: String, attr: String): String =
                Regex("""<$tag\b[^>]*\b(?:android:)?$attr="([^"]+)"""").find(manifestXml)?.groupValues?.get(1) ?: ""

            fun extractComponentNames(tag: String): List<String> =
                Regex("""<$tag\b[^>]*\b(?:android:)?name="([^"]+)"""").findAll(manifestXml).map { it.groupValues[1] }.toList()

            val pkgName = extractAttr("manifest", "package")
            val verName = extractAttr("manifest", "versionName")
            val verCode = extractAttr("manifest", "versionCode")
            val minSdk = extractAttr("uses-sdk", "minSdkVersion")
            val targetSdk = extractAttr("uses-sdk", "targetSdkVersion")
            val permissions = Regex("""<uses-permission\b[^>]*\b(?:android:)?name="([^"]+)"""")
                .findAll(manifestXml).map { it.groupValues[1] }.distinct().toList()

            val apkObj = PyObject("APK").apply {
                attrs["get_package"] = fn { _, _ -> pkgName }
                attrs["get_androidversion_name"] = fn { _, _ -> verName }
                attrs["get_androidversion_code"] = fn { _, _ -> verCode }
                attrs["get_min_sdk_version"] = fn { _, _ -> minSdk }
                attrs["get_target_sdk_version"] = fn { _, _ -> targetSdk }
                attrs["get_permissions"] = fn { _, _ -> permissions }
                attrs["get_activities"] = fn { _, _ -> extractComponentNames("activity") }
                attrs["get_services"] = fn { _, _ -> extractComponentNames("service") }
                attrs["get_receivers"] = fn { _, _ -> extractComponentNames("receiver") }
                attrs["get_providers"] = fn { _, _ -> extractComponentNames("provider") }
                attrs["get_files"] = fn { _, _ -> entryNames }
                attrs["get_android_manifest_xml"] = fn { _, _ -> manifestXml }
            }

            val dxObj = PyObject("Analysis")
            return listOf(apkObj, dexList, dxObj)
        }
    }
}
