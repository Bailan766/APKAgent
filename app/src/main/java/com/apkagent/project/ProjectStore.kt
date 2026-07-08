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

    fun listProjects(): List<ReverseProject> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ReverseProject>>(indexFile.readText())
        }.getOrElse { emptyList() }
            .sortedByDescending { it.lastOpenedAt }
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
        val all = listProjects().toMutableList()
        val idx = all.indexOfFirst { it.id == projectId }
        if (idx >= 0) {
            all[idx] = all[idx].copy(
                updatedAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis()
            )
            writeIndex(all)
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
        val all = listProjects().filterNot { it.id == project.id }.toMutableList()
        all.add(0, project)
        writeIndex(all)
    }

    private fun writeIndex(projects: List<ReverseProject>) {
        indexFile.writeText(json.encodeToString(projects))
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
    appendLine("# ${projectName}")
    appendLine()
    appendLine("- APK: $apkName")
    appendLine("- 大小: ${apkSize / 1024} KB")
    appendLine("- 包名: ${packageName ?: "未知"}")
    appendLine("- 版本: ${versionName ?: "未知"} (${versionCode ?: "?"})")
    appendLine("- SDK: min=${minSdk ?: "?"}, target=${targetSdk ?: "?"}")
    appendLine()
    appendLine("## 权限")
    permissions.take(20).forEach { appendLine("- $it") }
    appendLine()
    appendLine("## 组件")
    appendLine("- Activity: ${activities.size}")
    appendLine("- Service: ${services.size}")
    appendLine("- Receiver: ${receivers.size}")
    appendLine("- Provider: ${providers.size}")
    appendLine()
    appendLine("## DEX / Native")
    dexEntries.forEach { appendLine("- DEX: $it") }
    nativeLibs.take(20).forEach { appendLine("- SO: $it") }
    appendLine()
    appendLine("## 证书")
    appendLine(certificateSummary.ifBlank { "无" })
    appendLine()
    appendLine("## 风险摘要")
    appendLine(riskSummary.ifBlank { "无" })
}
