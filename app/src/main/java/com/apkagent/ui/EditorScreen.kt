package com.apkagent.ui

import com.apkagent.i18n.LocalAppStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK/DEX 高性能极客代码浏览器与编辑器：
 * 1. 全异步 IO 读取目录与文件，杜绝主线程阻塞；
 * 2. 基于 LazyColumn 按行虚拟化渲染 + 行号栏，支持数千行 Smali 满帧顺滑滚动；
 * 3. 支持在线编辑 Smali / XML / JSON 并保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    rootDir: File,
    onBack: () -> Unit
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var currentDir by rememberSaveable(rootDir) { mutableStateOf(rootDir) }
    var selectedFile by rememberSaveable { mutableStateOf<File?>(null) }
    var fileContent by rememberSaveable { mutableStateOf("") }
    var editedContent by rememberSaveable { mutableStateOf("") }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var isTruncated by rememberSaveable { mutableStateOf(false) }
    var isLoadingFile by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val textExtensions = remember {
        setOf("smali", "xml", "txt", "json", "properties", "mf", "sf", "kotlin_module", "pro", "md", "py", "js", "sh", "yml", "yaml")
    }

    // 选中文件后统一在此加载：旋转/组合重建会随 selectedFile 自动重跑，
    // 避免 rememberSaveable 残留 isLoadingFile 造成永久 loading。
    LaunchedEffect(selectedFile) {
        val f = selectedFile
        if (f == null) {
            isLoadingFile = false
            return@LaunchedEffect
        }
        isLoadingFile = true
        isTruncated = false
        val loaded = withContext(Dispatchers.IO) {
            try {
                val ext = f.extension.lowercase()
                when {
                    ext !in textExtensions ->
                        LoadedFile(s.fmt(s.binaryFileHint, "n" to f.length()), true)
                    f.length() <= 512 * 1024 ->
                        LoadedFile(f.readText(), false)
                    else ->
                        LoadedFile(
                            readLimitedText(f, 200_000) +
                                "\n\n…… [文件较大，已截断显示前 200KB，禁止编辑与保存]",
                            true
                        )
                }
            } catch (e: Throwable) {
                LoadedFile(s.fmt(s.readFailed, "msg" to e.message), true)
            }
        }
        fileContent = loaded.content
        isTruncated = loaded.truncated
        isLoadingFile = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                selectedFile?.name ?: s.apkEditor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            (selectedFile ?: currentDir).absolutePath,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFile != null) {
                            selectedFile = null
                            isEditing = false
                        } else if (currentDir != rootDir && currentDir.parentFile != null) {
                            currentDir = currentDir.parentFile ?: rootDir
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back)
                    }
                },
                actions = {
                    // 截断/二进制文件一律不显示保存按钮，禁止把截断串写回原文件造成永久损坏
                    if (selectedFile != null && isEditing && !isTruncated) {
                        IconButton(onClick = {
                            val target = selectedFile ?: return@IconButton
                            if (isTruncated) return@IconButton
                            scope.launch(Dispatchers.IO) {
                                try {
                                    target.writeText(editedContent)
                                    withContext(Dispatchers.Main) {
                                        fileContent = editedContent
                                        isEditing = false
                                        snackbar.showSnackbar(s.saved)
                                    }
                                } catch (e: Throwable) {
                                    withContext(Dispatchers.Main) {
                                        snackbar.showSnackbar(s.fmt(s.saveFailed, "msg" to e.message))
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Save, s.save, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        val f = selectedFile
        if (f != null) {
            if (isLoadingFile) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                FileViewer(
                    file = f,
                    content = if (isEditing) editedContent else fileContent,
                    isEditable = f.extension.lowercase() in textExtensions && !isTruncated,
                    isTruncated = isTruncated,
                    isEditing = isEditing,
                    onEdit = {
                        editedContent = fileContent
                        isEditing = true
                    },
                    onContentChange = { editedContent = it },
                    modifier = Modifier.padding(padding)
                )
            }
        } else {
            FileBrowser(
                dir = currentDir,
                onDirClick = { currentDir = it },
                onFileClick = { file ->
                    selectedFile = file
                    isEditing = false
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

private data class LoadedFile(val content: String, val truncated: Boolean)

/** 按字符块限量读取，避免对大文件整体 readText() 造成 OOM，也不会把单行超大文件读爆内存。 */
private fun readLimitedText(file: File, limitChars: Int): String {
    val sb = StringBuilder()
    val buf = CharArray(4096)
    var total = 0
    file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        while (total < limitChars) {
            val n = reader.read(buf, 0, minOf(buf.size, limitChars - total))
            if (n < 0) break
            sb.append(String(buf, 0, n))
            total += n
        }
    }
    return sb.toString()
}

@Composable
private fun FileBrowser(
    dir: File,
    onDirClick: (File) -> Unit,
    onFileClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalAppStrings.current
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(dir) {
        withContext(Dispatchers.IO) {
            val list = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            withContext(Dispatchers.Main) {
                entries = list
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(entries, key = { it.absolutePath }) { entry ->
            val isDir = entry.isDirectory
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (isDir) onDirClick(entry) else onFileClick(entry) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isDir) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isDir) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!isDir) {
                            Text(
                                "${entry.length()} B",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(s.emptyDir, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun FileViewer(
    file: File,
    content: String,
    isEditable: Boolean,
    isTruncated: Boolean,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) { content.lines() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 状态信息栏
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${lines.size} lines · ${file.length()} B" + if (isTruncated) " · ⚠ 截断只读" else "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (isTruncated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!isEditing && isEditable) {
                    TextButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(LocalAppStrings.current.edit, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        if (isEditing) {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            )
        } else {
            // 按行虚拟化渲染 + 行号列，数千行代码依然 120 FPS 丝滑滚动
            val hScroll = rememberScrollState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(lines) { idx, line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 0.5.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${idx + 1}".padStart(4, ' '),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.width(42.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
