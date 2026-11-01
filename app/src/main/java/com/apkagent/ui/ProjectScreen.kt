package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkagent.ApkAgentApp
import com.apkagent.project.InstalledAppItem
import com.apkagent.project.ReverseProject
import com.apkagent.project.ProjectSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private object ProjectIconCache {
    val cache = android.util.LruCache<String, android.graphics.Bitmap>(50)
}

data class ProjectDossierItem(
    val project: ReverseProject,
    val summaryText: String,
    val bitmap: android.graphics.Bitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    onOpenProjectChat: () -> Unit,
    onOpenProjectEditor: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val s = LocalAppStrings.current
    val context = LocalContext.current
    val app = remember { context.applicationContext as ApkAgentApp }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var projects by remember { mutableStateOf<List<ProjectDossierItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }

    fun reloadProjects() {
        scope.launch(Dispatchers.IO) {
            val rawProjects = app.projectStore.listProjects()
            val items = rawProjects.map { p ->
                val summary = runCatching {
                    app.projectStore.getAnalysisMarkdownFile(p.id)
                        .takeIf { it.exists() }?.readText()?.take(500) ?: ""
                }.getOrDefault("")

                val bmp = p.iconPath?.let { path ->
                    ProjectIconCache.cache.get(path) ?: runCatching {
                        val f = File(path)
                        if (f.exists()) {
                            android.graphics.BitmapFactory.decodeFile(path)?.also {
                                ProjectIconCache.cache.put(path, it)
                            }
                        } else null
                    }.getOrNull()
                }

                ProjectDossierItem(project = p, summaryText = summary, bitmap = bmp)
            }
            withContext(Dispatchers.Main) {
                projects = items
            }
        }
    }

    LaunchedEffect(Unit) { reloadProjects() }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                loading = true
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".apk") ?: "imported-apk"
                    val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
                    val ins = context.contentResolver.openInputStream(uri)
                    if (ins == null) {
                        withContext(Dispatchers.Main) { snackbar.showSnackbar(s.cannotReadApk) }
                        return@launch
                    }
                    ins.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
                    app.importApk(tmp, name)
                    tmp.delete()
                    withContext(Dispatchers.Main) {
                        reloadProjects()
                        snackbar.showSnackbar(s.fmt(s.importedAnalyzed, "name" to name))
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { snackbar.showSnackbar(s.fmt(s.importFailedShort, "msg" to e.message?.take(80))) }
                } finally { loading = false }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("APKAgent Studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Spacer(Modifier.width(6.dp))
                                TechBadge(
                                    text = "${projects.size} 目标",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                s.projectsSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, s.settings, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        loading = true
                        try {
                            val apps = app.projectStore.listInstalledApps()
                            withContext(Dispatchers.Main) {
                                installedApps = apps
                                showApps = true
                            }
                        } catch (e: Throwable) {
                            withContext(Dispatchers.Main) { snackbar.showSnackbar(s.fmt(s.readAppsFailed, "msg" to e.message)) }
                        } finally { loading = false }
                    }
                },
                icon = { Icon(Icons.Default.Android, null) },
                text = { Text(s.importInstalledApp, fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 操作工具栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.importApkBtn, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { reloadProjects() },
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.refreshProjects, fontSize = 13.sp)
                }
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)

            if (projects.isEmpty()) {
                EmptyProjectHint(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.project.id }) { item ->
                        ProjectDossierCard(
                            item = item,
                            onOpen = {
                                app.setCurrentProject(item.project)
                                onOpenProjectChat()
                            },
                            onEditor = {
                                app.setCurrentProject(item.project)
                                onOpenProjectEditor()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showApps) {
        AlertDialog(
            onDismissRequest = { showApps = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Android, null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.pickInstalledApp, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        placeholder = { Text(s.searchAppHint, fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = installedApps.filter {
                        appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true)
                    }.take(200)

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showApps = false
                                        scope.launch(Dispatchers.IO) {
                                            loading = true
                                            try {
                                                app.importInstalledApp(item)
                                                withContext(Dispatchers.Main) {
                                                    reloadProjects()
                                                    snackbar.showSnackbar(s.fmt(s.importedAnalyzed, "name" to item.label))
                                                }
                                            } catch (e: Throwable) {
                                                withContext(Dispatchers.Main) { snackbar.showSnackbar(s.fmt(s.importFailedShort, "msg" to e.message?.take(80))) }
                                            } finally { loading = false }
                                        }
                                    }
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (item.systemApp) Icons.Default.SettingsSystemDaydream else Icons.Default.Android,
                                        null,
                                        tint = if (item.systemApp) Color(0xFF94A3B8) else Color(0xFF10B981),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.label.ifBlank { item.packageName }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(
                                            item.packageName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (item.versionName != null) {
                                        TechBadge(
                                            text = item.versionName,
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showApps = false }) { Text(s.close) } }
        )
    }
}

@Composable
private fun EmptyProjectHint(modifier: Modifier = Modifier) {
    val s = LocalAppStrings.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Terminal,
                        null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                s.noProjectsYet,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.noProjectsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 逆向工程目标档案卡片 (Target Dossier) */
@Composable
private fun ProjectDossierCard(
    item: ProjectDossierItem,
    onOpen: () -> Unit,
    onEditor: () -> Unit
) {
    val project = item.project
    val summaryText = item.summaryText
    val bitmap = item.bitmap
    val s = LocalAppStrings.current
    var showFullSummary by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // 头部：图标 + 标题 + 包名 + 箭头
            Row(verticalAlignment = Alignment.CenterVertically) {

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(46.dp))
                    } else {
                        Icon(
                            if (project.sourceType == ProjectSourceType.INSTALLED_APP) Icons.Default.Android else Icons.Default.FolderZip,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        project.packageName ?: File(project.importedApkPath).name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onOpen,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        s.enter,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 状态徽章条
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 加壳徽章判定
                val isPacked = summaryText.contains("加壳") || summaryText.contains("加固")
                if (isPacked) {
                    TechBadge(
                        text = "加固壳防护",
                        containerColor = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        contentColor = Color(0xFFF59E0B)
                    )
                } else {
                    TechBadge(
                        text = "原生未加固",
                        containerColor = Color(0xFF10B981).copy(alpha = 0.15f),
                        contentColor = Color(0xFF10B981)
                    )
                }

                // 来源
                TechBadge(
                    text = if (project.sourceType == ProjectSourceType.INSTALLED_APP) "已安装" else "APK文件",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (project.versionName != null) {
                    TechBadge(
                        text = "v${project.versionName}",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 摘要折叠区
            if (summaryText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullSummary = !showFullSummary }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            summaryText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullSummary) 20 else 4
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.aiChat, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onEditor,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.projectFiles, fontSize = 13.sp)
                }
            }
        }
    }
}
