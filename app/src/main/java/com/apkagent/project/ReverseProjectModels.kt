package com.apkagent.project

import kotlinx.serialization.Serializable

@Serializable
data class ReverseProject(
    val id: String,
    val name: String,
    val packageName: String? = null,
    val sourceType: ProjectSourceType,
    val sourcePath: String,
    val importedApkPath: String,
    val iconPath: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class ProjectSourceType {
    INSTALLED_APP,
    APK_FILE
}

@Serializable
data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val sourceDir: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val systemApp: Boolean = false
)

@Serializable
data class ProjectAnalysisSummary(
    val projectId: String,
    val projectName: String,
    val apkName: String,
    val apkSize: Long,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val applicationLabel: String? = null,
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val dexEntries: List<String> = emptyList(),
    val nativeLibs: List<String> = emptyList(),
    val assetEntries: List<String> = emptyList(),
    val certificateSummary: String = "",
    val riskSummary: String = "",
    val generatedAt: Long = System.currentTimeMillis()
)
