package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apkagent.ApkAgentApp
import com.apkagent.project.InstalledAppItem
import com.apkagent.project.ReverseProject
import com.apkagent.project.ProjectSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var projects by remember { mutableStateOf<List<ReverseProject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var appQuery by remember { mutableStateOf("") }

    fun reloadProjects() {
        projects = app.projectStore.listProjects()
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
                    Column {
                        Text(s.reverseProjects, fontWeight = FontWeight.Bold)
                        Text(s.projectsSubtitle, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, s.settings) }
                }
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
                icon = { Icon(Icons.Default.Apps, null) },
                text = { Text(s.importInstalledApp) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { apkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    modifier = Modifier.weight(1f)
                ) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text(s.importApkBtn) }
                OutlinedButton(onClick = { reloadProjects() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(s.refreshProjects)
                }
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (projects.isEmpty()) {
                EmptyProjectHint(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            summaryText = remember(project.id) {
                                runCatching { app.projectStore.getAnalysisMarkdownFile(project.id).takeIf { it.exists() }?.readText()?.take(600) ?: "" }.getOrDefault("")
                            },
                            onOpen = {
                                app.setCurrentProject(project)
                                onOpenProjectChat()
                            },
                            onEditor = {
                                app.setCurrentProject(project)
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
            title = { Text(s.pickInstalledApp) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        label = { Text(s.searchAppHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = installedApps.filter {
                        appQuery.isBlank() || it.label.contains(appQuery, true) || it.packageName.contains(appQuery, true)
                    }.take(200)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.packageName }) { item ->
                            ListItem(
                                headlineContent = { Text(item.label.ifBlank { item.packageName }, maxLines = 1) },
                                supportingContent = { Text("${item.packageName}\n${item.versionName ?: s.unknownVersion}${if (item.systemApp) " · ${s.systemApp}" else ""}") },
                                leadingContent = { Icon(Icons.Default.Android, null) },
                                modifier = Modifier.clickable {
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
                            )
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Apps, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(s.noProjectsYet, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(s.noProjectsHint, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProjectCard(
    project: ReverseProject,
    summaryText: String,
    onOpen: () -> Unit,
    onEditor: () -> Unit
) {
    val s = LocalAppStrings.current
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val iconFile = project.iconPath?.let(::File)
                val bitmap = remember(project.iconPath) {
                    runCatching { iconFile?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) } }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(42.dp))
                } else {
                    Icon(if (project.sourceType == ProjectSourceType.INSTALLED_APP) Icons.Default.Android else Icons.Default.FolderOpen, null, modifier = Modifier.size(42.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(project.packageName ?: File(project.importedApkPath).name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Filled.ArrowForward, s.enter) }
            }
            Spacer(Modifier.height(8.dp))
            if (summaryText.isNotBlank()) {
                Text(summaryText, style = MaterialTheme.typography.bodySmall, maxLines = 8)
            } else {
                Text(s.noAnalysisYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SmartToy, null); Spacer(Modifier.width(6.dp)); Text(s.aiChat) }
                OutlinedButton(onClick = onEditor, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text(s.projectFiles) }
            }
        }
    }
}
