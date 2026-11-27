package com.apkagent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apkagent.ApkAgentApp
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.i18n.LocaleManager
import com.apkagent.installer.InternalInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class InstallStatus {
    IDLE, CHECKING, NOT_INSTALLED, INSTALLING, INSTALLED, FAILED
}

private fun hasStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSetupComplete: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = remember { context.applicationContext as ApkAgentApp }
    val runtimeManager = remember(app) { app.runtimeManager }

    var pythonStatus by remember { mutableStateOf(InstallStatus.CHECKING) }
    var nodeStatus by remember { mutableStateOf(InstallStatus.CHECKING) }
    var storageGranted by remember { mutableStateOf(hasStoragePermission(context)) }
    var statusMessage by remember { mutableStateOf(LocaleManager.strings.detectingEnv) }
    var progress by remember { mutableFloatStateOf(0f) }
    var pythonVersion by remember { mutableStateOf("") }
    var nodeVersion by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        storageGranted = granted || hasStoragePermission(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = hasStoragePermission(context)
                if (Build.VERSION.SDK_INT >= 33) {
                    val notifGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!notifGranted) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        delay(300)
        storageGranted = hasStoragePermission(context)
        val status = runtimeManager.getStatus()

        pythonVersion = status.pythonVersion ?: ""
        pythonStatus = when {
            status.pythonInstalled -> InstallStatus.INSTALLED
            else -> InstallStatus.NOT_INSTALLED
        }

        nodeVersion = status.nodeVersion ?: ""
        nodeStatus = when {
            status.nodeInstalled -> InstallStatus.INSTALLED
            else -> InstallStatus.NOT_INSTALLED
        }

        progress = when {
            status.pythonInstalled && status.nodeInstalled -> 1.0f
            status.pythonInstalled -> 0.6f
            else -> 0.1f
        }

        statusMessage = when {
            status.pythonInstalled && status.nodeInstalled -> s.envReady
            status.pythonInstalled -> s.pythonReadyNodeOptional
            else -> s.tapInstallPython
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── M3 玫瑰暖粉渐变引导主卡 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(M3RoseGradient)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(M3RoseHeroTitleColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = M3RoseHeroTitleColor
                        )
                    }

                    Text(
                        text = "APKAgent",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = M3RoseHeroTitleColor
                    )

                    Text(
                        text = s.setupTitle,
                        fontSize = 13.sp,
                        color = M3RoseHeroBodyColor,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { progress },
                        color = M3RoseHeroTitleColor,
                        trackColor = M3RoseHeroTitleColor.copy(alpha = 0.18f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            StoragePermissionCard(
                granted = storageGranted,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        }
                    } else {
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            )

            EnvCard(
                title = s.pythonRuntime,
                subtitle = if (pythonVersion.isNotEmpty()) "v$pythonVersion" else s.pythonSubtitleRequired,
                icon = Icons.Default.Code,
                status = pythonStatus,
                required = true,
                isInstalling = isInstalling,
                onAction = {
                    when (pythonStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                isInstalling = true
                                pythonStatus = InstallStatus.INSTALLING

                                val ok = InternalInstaller.installPython(context) { p ->
                                    statusMessage = p.message
                                    progress = 0.1f + p.progress * 0.5f
                                }

                                pythonStatus = if (ok) {
                                    val refreshed = runtimeManager.getStatus()
                                    pythonVersion = refreshed.pythonVersion ?: ""
                                    InstallStatus.INSTALLED
                                } else {
                                    InstallStatus.FAILED
                                }
                                isInstalling = false
                            }
                        }
                        else -> {}
                    }
                }
            )

            EnvCard(
                title = s.nodeRuntime,
                subtitle = if (nodeVersion.isNotEmpty()) nodeVersion else s.nodeSubtitleOptional,
                icon = Icons.Default.AccountTree,
                status = nodeStatus,
                required = false,
                isInstalling = isInstalling,
                onAction = {
                    when (nodeStatus) {
                        InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                            scope.launch {
                                isInstalling = true
                                nodeStatus = InstallStatus.INSTALLING

                                val ok = InternalInstaller.installNode(context) { p ->
                                    statusMessage = p.message
                                    progress = 0.6f + p.progress * 0.3f
                                }

                                nodeStatus = if (ok) {
                                    val refreshed = runtimeManager.getStatus()
                                    nodeVersion = refreshed.nodeVersion ?: nodeVersion
                                    InstallStatus.INSTALLED
                                } else InstallStatus.FAILED
                                isInstalling = false
                            }
                        }
                        else -> {}
                    }
                }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = statusMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            FilledTonalButton(
                onClick = onOpenTerminal,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(s.openBuiltinTerminal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onSetupComplete,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = pythonStatus == InstallStatus.INSTALLED,
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(s.startUsing, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            TextButton(onClick = onSetupComplete, shape = RoundedCornerShape(50)) {
                Text(s.configureLater, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StoragePermissionCard(granted: Boolean, onGrant: () -> Unit) {
    val s = LocalAppStrings.current
    val bgColor = if (granted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.storagePermission, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    TechBadge(
                        text = s.required,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (granted) s.storageGranted else s.storageDenied,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (granted) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(
                    onClick = onGrant,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(s.authorize, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EnvCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    status: InstallStatus,
    required: Boolean,
    isInstalling: Boolean,
    onAction: () -> Unit
) {
    val s = LocalAppStrings.current
    val bgColor = when (status) {
        InstallStatus.INSTALLED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        InstallStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (required) {
                        Spacer(modifier = Modifier.width(6.dp))
                        TechBadge(
                            text = s.required,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (status) {
                InstallStatus.CHECKING, InstallStatus.INSTALLING -> {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                InstallStatus.NOT_INSTALLED, InstallStatus.FAILED -> {
                    FilledTonalButton(
                        onClick = onAction,
                        enabled = !isInstalling,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(s.oneClickInstall, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                InstallStatus.INSTALLED -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                InstallStatus.IDLE -> {}
            }
        }
    }
}
