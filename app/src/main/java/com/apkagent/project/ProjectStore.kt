package com.apkagent.project

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class ProjectStore(private val context: Context, private val workspace: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val projectsRoot: File = File(workspace, "projects").apply { mkdirs() }
    private val indexFile: File = File(projectsRoot, "index.json")

    // index.json 的所有读写共用同一把锁，避免并发「读→改→写回」互相覆盖导致项目丢失
    private val indexLock = Any()

    fun listProjects(): List<ReverseProject> = synchronized(indexLock) {
        // 只读展示：解析失败时返回空表，但绝不写回（写入路径使用 readIndex()，
        // 解析失败会抛异常从而阻止用空表覆盖历史文件）
        try {
            readIndex().sortedByDescending { it.lastOpenedAt }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 读取索引原始列表；解析/读取失败时先备份损坏文件再抛出，防止带空数据继续写回。 */
    private fun readIndex(): List<ReverseProject> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ReverseProject>>(indexFile.readText())
        } catch (e: Throwable) {
            backupCorruptedIndex()
            throw e
        }
    }

    /** 把损坏的 index.json 另存一份，避免后续写入把历史项目永久清空。 */
    private fun backupCorruptedIndex() {
        try {
            if (indexFile.exists()) {
                val backup = File(projectsRoot, "index.corrupt.${System.currentTimeMillis()}.json")
                indexFile.copyTo(backup, overwrite = true)
            }
        } catch (_: Throwable) {}
    }

    fun getProject(projectId: String): ReverseProject? = listProjects().firstOrNull { it.id == projectId }

    fun getProjectDir(projectId: String): File = File(projectsRoot, projectId).apply { mkdirs() }
    fun getSourceDir(projectId: String): File = File(getProjectDir(projectId), "source").apply { mkdirs() }
    fun getAnalysisDir(projectId: String): File = File(getProjectDir(projectId), "analysis").apply { mkdirs() }
    fun getChatDir(projectId: String): File = File(getProjectDir(projectId), "chat").apply { mkdirs() }
    fun getExportsDir(projectId: String): File = File(getProjectDir(projectId), "exports").apply { mkdirs() }
    fun getPatchesDir(projectId: String): File = File(getProjectDir(projectId), "patches").apply { mkdirs() }
    fun getScriptsDir(projectId: String): File = File(getProjectDir(projectId), "scripts").apply { mkdirs() }
    fun getImportedApk(projectId: String): File = File(getSourceDir(projectId), "base.apk")
    fun getAnalysisSummaryFile(projectId: String): File = File(getAnalysisDir(projectId), "project_summary.json")
    fun getAnalysisMarkdownFile(projectId: String): File = File(getAnalysisDir(projectId), "project_summary.md")

    fun createProjectFromApk(apkFile: File, displayName: String = apkFile.nameWithoutExtension): ReverseProject {
        val projectId = buildProjectId(displayName)
        val sourceDir = getSourceDir(projectId)
        val importedApk = File(sourceDir, "base.apk")
        apkFile.copyTo(importedApk, overwrite = true)

        val project = ReverseProject(
            id = projectId,
            name = displayName,
            sourceType = ProjectSourceType.APK_FILE,
            sourcePath = apkFile.absolutePath,
            importedApkPath = importedApk.absolutePath
        )
        saveProject(project)
        return project
    }

    fun createProjectFromInstalledApp(item: InstalledAppItem): ReverseProject {
        val projectId = buildProjectId(item.label.ifBlank { item.packageName })
        val sourceDir = getSourceDir(projectId)
        val importedApk = File(sourceDir, "base.apk")
        File(item.sourceDir).copyTo(importedApk, overwrite = true)
        val iconPath = exportAppIcon(projectId, item.packageName)

        val project = ReverseProject(
            id = projectId,
            name = item.label,
            packageName = item.packageName,
            sourceType = ProjectSourceType.INSTALLED_APP,
            sourcePath = item.sourceDir,
            importedApkPath = importedApk.absolutePath,
            iconPath = iconPath,
            versionName = item.versionName,
            versionCode = item.versionCode
        )
        saveProject(project)
        return project
    }

    fun touchProject(projectId: String) {
        synchronized(indexLock) {
            // 索引损坏时 readIndex() 会抛异常（内部已备份损坏文件），
            // 此时保留旧文件、跳过本次写回，绝不写回空表，也不把异常抛给 UI
            val all = runCatching { readIndex().toMutableList() }.getOrNull()
            if (all != null) {
                val idx = all.indexOfFirst { it.id == projectId }
                if (idx >= 0) {
                    all[idx] = all[idx].copy(
                        updatedAt = System.currentTimeMillis(),
                        lastOpenedAt = System.currentTimeMillis()
                    )
                    writeIndex(all)
                }
            }
        }
    }

    fun saveAnalysisSummary(summary: ProjectAnalysisSummary) {
        val jsonFile = getAnalysisSummaryFile(summary.projectId)
        jsonFile.writeText(json.encodeToString(summary))
        getAnalysisMarkdownFile(summary.projectId).writeText(summary.toMarkdown())
    }

    fun loadAnalysisSummary(projectId: String): ProjectAnalysisSummary? {
        val file = getAnalysisSummaryFile(projectId)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<ProjectAnalysisSummary>(file.readText())
        }.getOrNull()
    }

    fun listInstalledApps(): List<InstalledAppItem> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps
            .asSequence()
            .filter { it.sourceDir?.endsWith(".apk") == true }
            .map { app -> app.toInstalledAppItem(pm) }
            .sortedWith(compareBy<InstalledAppItem> { it.systemApp }.thenBy { it.label.lowercase(Locale.getDefault()) })
            .toList()
    }

    private fun ApplicationInfo.toInstalledAppItem(pm: PackageManager): InstalledAppItem {
        val pkg = packageName ?: "unknown"
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        } catch (_: Throwable) {
            null
        }
        val versionCode = pkgInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        return InstalledAppItem(
            label = loadLabel(pm).toString(),
            packageName = pkg,
            sourceDir = sourceDir ?: publicSourceDir ?: "",
            versionName = pkgInfo?.versionName,
            versionCode = versionCode,
            systemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }

    private fun exportAppIcon(projectId: String, packageName: String): String? {
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val out = File(getProjectDir(projectId), "icon.png")
            FileOutputStream(out).use { stream ->
                drawable.toBitmap(128, 128).compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            out.absolutePath
        }.getOrNull()
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp
    }

    private fun saveProject(project: ReverseProject) {
        synchronized(indexLock) {
            // 索引损坏时 readIndex() 会抛异常（内部已备份损坏文件），
            // 此时保留旧文件、跳过写回，避免用空表覆盖历史项目
            val all = runCatching {
                readIndex().filterNot { it.id == project.id }.toMutableList()
            }.getOrNull()
            if (all != null) {
                all.add(0, project)
                writeIndex(all)
            }
        }
    }

    private fun writeIndex(projects: List<ReverseProject>) {
        // 临时文件 + fsync + 原子 rename：写一半崩溃时 index.json 仍是旧的完整内容
        val tmp = File(projectsRoot, "index.json.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(json.encodeToString(projects).toByteArray(Charsets.UTF_8))
                fos.flush()
                // fsync 让数据尽量落盘；部分文件系统不支持时忽略即可
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(indexFile)) {
                // 部分文件系统 renameTo 覆盖已存在文件会失败，降级为覆盖写（保持旧文件直到写完）
                tmp.copyTo(indexFile, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun buildProjectId(name: String): String {
        val slug = name
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "project" }
        return "$slug-${UUID.randomUUID().toString().take(8)}"
    }
}

private fun ProjectAnalysisSummary.toMarkdown(): String = buildString {
    // 摘要会持久化后作为 AI 上下文读取，这里使用语言中立的固定标题，
    // 不把随当前语言变化的本地化文案（L()）写入文件，避免切换语言后成为过期快照。
    appendLine("# ${projectName}")
    appendLine()
    appendLine("- APK: $apkName")
    appendLine("- Size: ${apkSize / 1024} KB")
    appendLine("- Package: ${packageName ?: "N/A"}")
    appendLine("- Version: ${versionName ?: "N/A"} (${versionCode ?: "?"})")
    appendLine("- SDK: min=${minSdk ?: "?"}, target=${targetSdk ?: "?"}")
    appendLine()
    appendLine("## Permissions / 权限")
    permissions.take(20).forEach { appendLine("- $it") }
    appendLine()
    appendLine("## Components / 组件")
    appendLine("- Activity: ${activities.size}")
    appendLine("- Service: ${services.size}")
    appendLine("- Receiver: ${receivers.size}")
    appendLine("- Provider: ${providers.size}")
    appendLine()
    appendLine("## DEX / Native")
    dexEntries.forEach { appendLine("- DEX: $it") }
    nativeLibs.take(20).forEach { appendLine("- SO: $it") }
    if (packers.isNotEmpty() || frameworks.isNotEmpty() || detectedSdks.isNotEmpty()) {
        appendLine()
        appendLine("## Packer & Tech Stack / 加壳与技术栈")
        if (packers.isNotEmpty()) appendLine("- Packer: ${packers.joinToString()}")
        if (frameworks.isNotEmpty()) appendLine("- Frameworks: ${frameworks.joinToString()}")
        if (detectedSdks.isNotEmpty()) appendLine("- SDKs: ${detectedSdks.joinToString()}")
    }
    appendLine()
    appendLine("## Certificate / 证书")
    appendLine(certificateSummary.ifBlank { "N/A" })
    appendLine()
    appendLine("## Risk Summary / 风险摘要")
    appendLine(riskSummary.ifBlank { "N/A" })
}
