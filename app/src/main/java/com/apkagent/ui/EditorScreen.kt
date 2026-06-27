package com.apkagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * APK/DEX 可视化编辑器：
 * - 浏览工作区目录树（smali/资源/DEX）
 * - 查看文件内容（smali 文本/十六进制）
 * - 编辑 smali 文本并保存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    rootDir: File,
    onBack: () -> Unit
) {
    var currentDir by remember { mutableStateOf(rootDir) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var editedContent by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackbar.showSnackbar(it); snackbarMsg = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("APK 编辑器", fontFamily = FontFamily.Monospace)
                        Text(currentDir.absolutePath, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFile != null) {
                            selectedFile = null; isEditing = false
                        } else if (currentDir != rootDir) {
                            currentDir = currentDir.parentFile ?: rootDir
                        } else {
                            onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    if (selectedFile != null && isEditing) {
                        IconButton(onClick = {
                            try {
                                selectedFile?.writeText(editedContent)
                                fileContent = editedContent
                                isEditing = false
                                snackbarMsg = "已保存"
                            } catch (e: Throwable) { snackbarMsg = "保存失败：${e.message}" }
                        }) { Icon(Icons.Default.Save, "保存") }
                    }
                }
            )
        }
    ) { padding ->
        val f = selectedFile
        if (f != null) {
            // 文件查看/编辑
            FileViewer(
                file = f,
                content = if (isEditing) editedContent else fileContent,
                isEditing = isEditing,
                onEdit = {
                    editedContent = fileContent
                    isEditing = true
                },
                onContentChange = { editedContent = it },
                modifier = Modifier.padding(padding)
            )
        } else {
            // 目录浏览
            FileBrowser(
                dir = currentDir,
                onDirClick = { currentDir = it },
                onFileClick = { file ->
                    selectedFile = file
                    fileContent = try {
                        if (file.extension in listOf("smali", "xml", "txt", "json", "properties", "mf", "kotlin_module", "pro")) {
                            file.readText()
                        } else {
                            "（二进制文件，${file.length()} 字节，用 Agent 的 apk.hex_view 工具查看十六进制）"
                        }
                    } catch (e: Throwable) { "读取失败：${e.message}" }
                    isEditing = false
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun FileBrowser(
    dir: File,
    onDirClick: (File) -> Unit,
    onFileClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = remember(dir) {
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(8.dp)) {
        items(entries) { entry ->
            val isDir = entry.isDirectory
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (isDir) onDirClick(entry) else onFileClick(entry) }
                    .padding(12.dp),
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
                    Text(entry.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    if (!isDir) Text("${entry.length()} B", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (entries.isEmpty()) {
            item { Text("（空目录）", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewer(
    file: File,
    content: String,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(file.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${file.length()}B", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!isEditing && file.extension in listOf("smali", "xml", "txt", "json")) {
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
        HorizontalDivider()
        if (isEditing) {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            )
        } else {
            // 只读显示
            val scrollState = rememberScrollState()
            Text(
                text = content,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(scrollState),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
