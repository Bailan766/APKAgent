package com.apkagent.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.apkagent.i18n.AppLanguage
import com.apkagent.i18n.LocaleManager

enum class ExecutionMode(
    val id: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String
) {
    FAST("fast", "快速模式", "Fast Mode", "自动化逆向免拦截，敏感操作自动放行执行", "Autonomous reverse engineering, auto-approves sensitive operations"),
    SAFE("safe", "安全模式", "Safe Mode", "高风险敏感操作逐步弹窗确认", "Prompts for confirmation before executing high-risk operations");

    companion object {
        fun fromId(id: String?): ExecutionMode =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FAST
    }
}

data class AgentConfig(
    val providerId: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.6,
    val systemExtra: String = "",
    val maxRounds: Int = 100,
    val executionMode: String = ExecutionMode.FAST.id
) {
    fun isValid(): Boolean = (providerId == "ollama" || apiKey.trim().isNotBlank()) && model.trim().isNotBlank()
    fun provider(): AiProvider = AiProvider.byId(providerId)
    fun mode(): ExecutionMode = ExecutionMode.fromId(executionMode)
}

class SettingsStore(private val context: Context) {

    @Volatile private var prefs: SharedPreferences? = null
    private val fallbackPrefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences("apk_agent_prefs_fallback", Context.MODE_PRIVATE)
    }

    private fun getPrefs(): SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: createPrefsSafe().also { prefs = it }
        }
    }

    private fun createPrefsSafe(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            context.applicationContext, "apk_agent_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // 验证加密存储是否真正可读写（防止重装 APK 后 Keystore 失效导致静默失败）
        encrypted.getString(KEY_PROVIDER, null)
        encrypted
    } catch (e: Throwable) {
        Log.w("SettingsStore", "加密存储不可用或密钥已失效，切换至稳健存储", e)
        try {
            context.applicationContext.deleteSharedPreferences("apk_agent_secrets")
        } catch (_: Throwable) {}
        fallbackPrefs
    }

    private val _config = MutableStateFlow(loadInternal())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    fun sanitizeApiKey(raw: String): String {
        var s = raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("`")
            .removeSurrounding("“", "”")
            .removeSurrounding("‘", "’")
            .trim()
        s = s.replace(Regex("^(?i)(bearer|apikey|api_key|key)\\s*[:=]?\\s*"), "")
        // 严格保留可见 ASCII 字符（0x21..0x7E），彻底剔除换行、空格、零宽字符与 BOM
        return s.filter { it in '\u0021'..'\u007e' }
    }

    private fun readStringSafe(key: String): String? {
        try {
            if (fallbackPrefs.contains(key)) {
                val v = fallbackPrefs.getString(key, null)
                if (v != null) return v
            }
        } catch (_: Throwable) {}
        return try {
            getPrefs().getString(key, null) ?: fallbackPrefs.getString(key, null)
        } catch (_: Throwable) {
            prefs = fallbackPrefs
            fallbackPrefs.getString(key, null)
        }
    }

    private fun loadInternal(): AgentConfig = try {
        var pid = readStringSafe(KEY_PROVIDER) ?: "deepseek"
        val rawKey = sanitizeApiKey(readStringSafe(KEY_AK).orEmpty())
        // 如果非自定义且 Key 前缀明确对应特定提供商，自动校准
        if (pid != "custom" && rawKey.isNotEmpty()) {
            AiProvider.detectFromKeyPrefix(rawKey)?.let { detected ->
                pid = detected.id
            }
        }
        val provider = AiProvider.byId(pid)
        val rawUrl = readStringSafe(KEY_BASE)?.trim().orEmpty()
        val resolvedUrl = if (pid == "custom") {
            rawUrl.ifBlank { "https://api.deepseek.com/v1" }
        } else {
            rawUrl.ifBlank { provider.baseUrl.ifBlank { "https://api.deepseek.com/v1" } }
        }
        val rawModel = readStringSafe(KEY_MODEL)?.trim().orEmpty()
        val resolvedModel = rawModel.ifBlank {
            provider.defaultModel.ifBlank {
                provider.models.firstOrNull() ?: "deepseek-chat"
            }
        }
        val savedMode = readStringSafe(KEY_EXECUTION_MODE) ?: ExecutionMode.FAST.id
        AgentConfig(
            providerId = pid,
            baseUrl = resolvedUrl,
            apiKey = rawKey,
            model = resolvedModel,
            temperature = (readStringSafe(KEY_TEMP) ?: "0.6").toDoubleOrNull() ?: 0.6,
            systemExtra = readStringSafe(KEY_SYS) ?: "",
            maxRounds = (readStringSafe(KEY_MAX_ROUNDS) ?: "100").toIntOrNull() ?: 100,
            executionMode = savedMode
        )
    } catch (e: Throwable) {
        Log.e("SettingsStore", "读取失败", e)
        AgentConfig()
    }

    fun save(cfg: AgentConfig) {
        val cleanKey = sanitizeApiKey(cfg.apiKey)
        val provider = AiProvider.byId(cfg.providerId)
        val cleanUrl = cfg.baseUrl.trim().ifBlank {
            provider.baseUrl.ifBlank { "https://api.deepseek.com/v1" }
        }
        val cleanModel = cfg.model.trim().ifBlank {
            provider.defaultModel.ifBlank {
                provider.models.firstOrNull() ?: "deepseek-chat"
            }
        }
        val cleanMode = if (cfg.executionMode.equals("safe", ignoreCase = true)) "safe" else "fast"
        val normalized = cfg.copy(
            baseUrl = cleanUrl,
            apiKey = cleanKey,
            model = cleanModel,
            executionMode = cleanMode
        )

        val writeTo: (SharedPreferences) -> Unit = { sp ->
            sp.edit()
                .putString(KEY_PROVIDER, normalized.providerId)
                .putString(KEY_BASE, normalized.baseUrl)
                .putString(KEY_AK, normalized.apiKey)
                .putString(KEY_MODEL, normalized.model)
                .putString(KEY_TEMP, normalized.temperature.toString())
                .putString(KEY_SYS, normalized.systemExtra)
                .putString(KEY_MAX_ROUNDS, normalized.maxRounds.toString())
                .putString(KEY_EXECUTION_MODE, normalized.executionMode)
                .commit()
        }

        try {
            writeTo(fallbackPrefs)
        } catch (_: Throwable) {}
        try {
            writeTo(getPrefs())
        } catch (e: Throwable) {
            Log.e("SettingsStore", "主存储写入失败，使用回退存储", e)
            prefs = fallbackPrefs
        }

        _config.value = normalized
    }

    // ──── 首次启动引导 ────

    fun isSetupCompleted(): Boolean = try {
        fallbackPrefs.getBoolean(KEY_SETUP_DONE, false) || getPrefs().getBoolean(KEY_SETUP_DONE, false)
    } catch (_: Throwable) {
        fallbackPrefs.getBoolean(KEY_SETUP_DONE, false)
    }

    fun markSetupCompleted() {
        try { fallbackPrefs.edit().putBoolean(KEY_SETUP_DONE, true).apply() } catch (_: Throwable) {}
        try { getPrefs().edit().putBoolean(KEY_SETUP_DONE, true).apply() } catch (_: Throwable) {}
    }

    fun isPythonInstalled(): Boolean = try {
        fallbackPrefs.getBoolean(KEY_PYTHON_INSTALLED, false) || getPrefs().getBoolean(KEY_PYTHON_INSTALLED, false)
    } catch (_: Throwable) { false }

    fun setPythonInstalled(installed: Boolean) {
        try { fallbackPrefs.edit().putBoolean(KEY_PYTHON_INSTALLED, installed).apply() } catch (_: Throwable) {}
        try { getPrefs().edit().putBoolean(KEY_PYTHON_INSTALLED, installed).apply() } catch (_: Throwable) {}
    }

    fun isNodeInstalled(): Boolean = try {
        fallbackPrefs.getBoolean(KEY_NODE_INSTALLED, false) || getPrefs().getBoolean(KEY_NODE_INSTALLED, false)
    } catch (_: Throwable) { false }

    fun setNodeInstalled(installed: Boolean) {
        try { fallbackPrefs.edit().putBoolean(KEY_NODE_INSTALLED, installed).apply() } catch (_: Throwable) {}
        try { getPrefs().edit().putBoolean(KEY_NODE_INSTALLED, installed).apply() } catch (_: Throwable) {}
    }

    // ──── Language / 语言 ────

    fun getLanguage(): AppLanguage = try {
        AppLanguage.fromId(readStringSafe(KEY_LANGUAGE) ?: AppLanguage.SYSTEM.id)
    } catch (_: Throwable) {
        AppLanguage.SYSTEM
    }

    fun setLanguage(lang: AppLanguage) {
        try { fallbackPrefs.edit().putString(KEY_LANGUAGE, lang.id).apply() } catch (_: Throwable) {}
        try { getPrefs().edit().putString(KEY_LANGUAGE, lang.id).apply() } catch (_: Throwable) {}
        LocaleManager.setLanguage(lang)
    }

    fun getExecutionMode(): ExecutionMode = ExecutionMode.fromId(readStringSafe(KEY_EXECUTION_MODE) ?: ExecutionMode.FAST.id)

    fun setExecutionMode(mode: ExecutionMode) {
        val cur = _config.value
        save(cur.copy(executionMode = mode.id))
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE = "base_url"
        private const val KEY_AK = "apikey"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMP = "temperature"
        private const val KEY_SYS = "system_extra"
        private const val KEY_SETUP_DONE = "setup_completed"
        private const val KEY_PYTHON_INSTALLED = "python_installed"
        private const val KEY_NODE_INSTALLED = "node_installed"
        private const val KEY_MAX_ROUNDS = "max_rounds"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_EXECUTION_MODE = "execution_mode"
    }
}
