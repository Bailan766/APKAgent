package com.apkagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkagent.ApkAgentApp
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.project.InstalledAppItem
import com.apkagent.project.ProjectSourceType
import com.apkagent.project.ReverseProject
import com.apkagent.store.ExecutionMode
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

    val activeProject by app.currentProject.collectAsStateWithLifecycle()
    val cfg by app.settingsStore.config.collectAsStateWithLifecycle()
    val execMode = remember(cfg) { cfg.mode() }

    var projects by remember { mutableStateOf<List<ProjectDossierItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    var projectToDelete by remember { mutableStateOf<ReverseProject?>(null) }

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
                    val imported = app.importApk(tmp, name)
                    tmp.delete()
                    withContext(Dispatchers.Main) {
                        app.setCurrentProject(imported)
                        reloadProjects()
                        val res = snackbar.showSnackbar(
                            message = s.fmt(s.importedAnalyzed, "name" to name),
                            actionLabel = s.aiChat,
                            duration = SnackbarDuration.Short
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            onOpenProjectChat()
                        }
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { snackbar.showSnackbar(s.fmt(s.importFailedShort, "msg" to e.message?.take(80))) }
                } finally { loading = false }
            }
        }
    }

    val openInstalledPicker: () -> Unit = {
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
    }

    val filteredProjects = remember(projects, projectQuery) {
        if (projectQuery.isBlank()) projects
        else projects.filter {
            it.project.name.contains(projectQuery, ignoreCase = true) ||
                (it.project.packageName?.contains(projectQuery, ignoreCase = true) == true)
        }
    }

    val heroProject = activeProject ?: projects.firstOrNull()?.project

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.padding(bottom = 84.dp)
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("APKAgent", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                            Text(
                                s.projectsSubtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { reloadProjects() }) {
                        Icon(Icons.Default.Refresh, s.refreshProjects, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, s.settings, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ══════════════════════════════════════════════════
            // 1. 柔和粉红/玫瑰色渐变主卡片 (M3 Rose Hero Card)
            // ══════════════════════════════════════════════════
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(M3RoseGradient)
                        .clickable {
                            if (heroProject != null) {
                                app.setCurrentProject(heroProject)
                                onOpenProjectChat()
                            } else {
                                apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TechBadge(
                                text = "默认",
                                containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                                contentColor = M3RoseHeroTitleColor
                            )
                            TechBadge(
                                text = if (execMode == ExecutionMode.FAST) "⚡ 快速模式" else "🛡️ 安全模式",
                                containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                                contentColor = M3RoseHeroTitleColor
                            )
                            TechBadge(
                                text = "Beta版本",
                                containerColor = M3RoseHeroTitleColor.copy(alpha = 0.12f),
                                contentColor = M3RoseHeroTitleColor
                            )
                        }

                        Text(
                            text = heroProject?.name ?: "APKAgent 逆向引擎",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = M3RoseHeroTitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = heroProject?.let {
                                "${it.packageName ?: File(it.importedApkPath).name} · 在管 ${projects.size} 个目标"
                            } ?: "内置 Python 3.12 · Smali 引擎 · ColorOS 流体云就绪",
                            fontSize = 13.sp,
                            color = M3RoseHeroBodyColor,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(2.dp))

                        Button(
                            onClick = {
                                if (heroProject != null) {
                                    app.setCurrentProject(heroProject)
                                    onOpenProjectChat()
                                } else {
                                    apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                                }
                            },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = M3RoseHeroTitleColor,
                                contentColor = Color(0xFFFFD9DF)
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (heroProject != null) "进入 AI 逆向工作台" else "导入首个 APK 文件",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════
            // 2. 双列大圆角功能卡片 (Dual Action Cards)
            // ══════════════════════════════════════════════════
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DualFeatureCard(
                        title = "导入本地 APK",
                        subtitle = "选择安装包解析",
                        badge = "默认",
                        icon = Icons.Default.FolderOpen,
                        onClick = {
                            apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                        },
                        modifier = Modifier.weight(1f)
                    )

                    DualFeatureCard(
                        title = "提取已装应用",
                        subtitle = "免 Root 提取安装包",
                        badge = "Beta版本",
                        icon = Icons.Default.Android,
                        onClick = openInstalledPicker,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ══════════════════════════════════════════════════
            // 3. 目标档案分组标题与胶囊搜索栏
            // ══════════════════════════════════════════════════
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "逆向目标档案",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.width(8.dp))
                        TechBadge(
                            text = "${projects.size} 个项目",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (projects.size > 1) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                TextField(
                                    value = projectQuery,
                                    onValueChange = { projectQuery = it },
                                    placeholder = { Text("搜索目标名称或包名...", fontSize = 13.sp) },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                if (projectQuery.isNotBlank()) {
                                    IconButton(onClick = { projectQuery = "" }) {
                                        Icon(Icons.Default.Clear, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════
            // 4. 分组大圆角无边框项目列表 (Grouped List Container)
            // ══════════════════════════════════════════════════
            if (filteredProjects.isEmpty()) {
                item {
                    EmptyProjectHint(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                }
            } else {
                itemsIndexed(filteredProjects, key = { _, it -> it.project.id }) { idx, item ->
                    val topCorner = if (idx == 0) 26.dp else 8.dp
                    val bottomCorner = if (idx == filteredProjects.lastIndex) 26.dp else 8.dp
                    val cardShape = RoundedCornerShape(
                        topStart = topCorner,
                        topEnd = topCorner,
                        bottomStart = bottomCorner,
                        bottomEnd = bottomCorner
                    )
                    ProjectDossierCard(
                        item = item,
                        shape = cardShape,
                        isSelected = activeProject?.id == item.project.id,
                        onOpen = {
                            app.setCurrentProject(item.project)
                            onOpenProjectChat()
                        },
                        onEditor = {
                            app.setCurrentProject(item.project)
                            onOpenProjectEditor()
                        },
                        onDelete = {
                            projectToDelete = item.project
                        }
                    )
                }
            }
        }
    }

    if (showApps) {
        AlertDialog(
            onDismissRequest = { showApps = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s.pickInstalledApp, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            TextField(
                                value = appQuery,
                                onValueChange = { appQuery = it },
                                placeholder = { Text(s.searchAppHint, fontSize = 13.sp) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val filtered = installedApps.filter {
                        appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true)
                    }.take(200)

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        showApps = false
                                        scope.launch(Dispatchers.IO) {
                                            loading = true
                                            try {
                                                val imported = app.importInstalledApp(item)
                                                withContext(Dispatchers.Main) {
                                                    app.setCurrentProject(imported)
                                                    reloadProjects()
                                                    val res = snackbar.showSnackbar(
                                                        message = s.fmt(s.importedAnalyzed, "name" to item.label),
                                                        actionLabel = s.aiChat,
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        onOpenProjectChat()
                                                    }
                                                }
                                            } catch (e: Throwable) {
                                                withContext(Dispatchers.Main) { snackbar.showSnackbar(s.fmt(s.importFailedShort, "msg" to e.message?.take(80))) }
                                            } finally { loading = false }
                                        }
                                    }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (item.systemApp) Icons.Default.SettingsSystemDaydream else Icons.Default.Android,
                                            null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.label.ifBlank { item.packageName }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            item.packageName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
            confirmButton = { TextButton(onClick = { showApps = false }, shape = RoundedCornerShape(50)) { Text(s.close) } }
        )
    }

    projectToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            },
            title = { Text("确认删除项目", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定要删除「${target.name}」及其所有沙盒分析文件与聊天记录吗？此操作不可恢复。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        projectToDelete = null
                        scope.launch(Dispatchers.IO) {
                            app.projectStore.deleteProject(target.id)
                            withContext(Dispatchers.Main) {
                                if (app.currentProject.value?.id == target.id) {
                                    app.setCurrentProject(null)
                                }
                                reloadProjects()
                                snackbar.showSnackbar("已删除项目：${target.name}")
                            }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }, shape = RoundedCornerShape(50)) { Text(s.cancel) }
            }
        )
    }
}

@Composable
private fun DualFeatureCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TechBadge(
                    text = badge,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyProjectHint(modifier: Modifier = Modifier) {
    val s = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Terminal,
                    null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                s.noProjectsYet,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                s.noProjectsHint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Material 3 大圆角分组档案条目（无细边框 + 柔和色块底衬 + 清晰字体层级） */
@Composable
private fun ProjectDossierCard(
    item: ProjectDossierItem,
    shape: RoundedCornerShape,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onEditor: () -> Unit,
    onDelete: () -> Unit
) {
    val project = item.project
    val summaryText = item.summaryText
    val bitmap = item.bitmap
    val s = LocalAppStrings.current
    var showFullSummary by remember { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onOpen() }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    } else {
                        Icon(
                            if (project.sourceType == ProjectSourceType.INSTALLED_APP) Icons.Default.Android else Icons.Default.FolderZip,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            project.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(6.dp))
                            TechBadge(
                                text = "当前",
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        project.packageName ?: File(project.importedApkPath).name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除项目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen() },
                    contentAlignment = Alignment.Center
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val isPacked = summaryText.contains("加壳") || summaryText.contains("加固")
                if (isPacked) {
                    TechBadge(
                        text = "加固壳防护",
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    TechBadge(
                        text = "原生未加固",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }

                TechBadge(
                    text = if (project.sourceType == ProjectSourceType.INSTALLED_APP) "已安装" else "APK 文件",
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

            if (summaryText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showFullSummary = !showFullSummary }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            summaryText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullSummary) 20 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.aiChat, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                FilledTonalButton(
                    onClick = onEditor,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.projectFiles, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
