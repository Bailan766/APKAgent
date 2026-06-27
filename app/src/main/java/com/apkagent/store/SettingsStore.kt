package com.apkagent.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentConfig(
    val providerId: String = "deepseek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.6,
    val systemExtra: String = ""
) {
    fun isValid(): Boolean = apiKey.isNotBlank() && model.isNotBlank()
    fun provider(): AiProvider = AiProvider.byId(providerId)
}

class SettingsStore(private val context: Context) {

    @Volatile private var prefs: SharedPreferences? = null

    private fun getPrefs(): SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: createPrefsSafe().also { prefs = it }
        }
    }

    private fun createPrefsSafe(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "apk_agent_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        Log.w("SettingsStore", "加密不可用", e)
        context.getSharedPreferences("apk_agent_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val _config = MutableStateFlow(loadInternal())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    private fun loadInternal(): AgentConfig = try {
        val s = getPrefs()
        val pid = s[KEY_PROVIDER] ?: "deepseek"
        val provider = AiProvider.byId(pid)
        AgentConfig(
            providerId = pid,
            baseUrl = s[KEY_BASE] ?: provider.baseUrl,
            apiKey = s[KEY_AK] ?: "",
            model = s[KEY_MODEL] ?: provider.defaultModel,
            temperature = (s[KEY_TEMP] ?: "0.6").toDoubleOrNull() ?: 0.6,
            systemExtra = s[KEY_SYS] ?: ""
        )
    } catch (e: Throwable) {
        Log.e("SettingsStore", "读取失败", e)
        AgentConfig()
    }

    fun save(cfg: AgentConfig) {
        try {
            getPrefs().edit()
                .putString(KEY_PROVIDER, cfg.providerId)
                .putString(KEY_BASE, cfg.baseUrl)
                .putString(KEY_AK, cfg.apiKey)
                .putString(KEY_MODEL, cfg.model)
                .putString(KEY_TEMP, cfg.temperature.toString())
                .putString(KEY_SYS, cfg.systemExtra)
                .apply()
        } catch (e: Throwable) {
            Log.e("SettingsStore", "保存失败", e)
        }
        _config.value = cfg
    }

    // ──── 首次启动引导 ────

    /** 是否已完成首次设置 */
    fun isSetupCompleted(): Boolean = getPrefs().getBoolean(KEY_SETUP_DONE, false)

    /** 标记设置完成 */
    fun markSetupCompleted() {
        getPrefs().edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** Python 安装状态 */
    fun isPythonInstalled(): Boolean = getPrefs().getBoolean(KEY_PYTHON_INSTALLED, false)
    fun setPythonInstalled(installed: Boolean) {
        getPrefs().edit().putBoolean(KEY_PYTHON_INSTALLED, installed).apply()
    }

    /** Node.js 安装状态 */
    fun isNodeInstalled(): Boolean = getPrefs().getBoolean(KEY_NODE_INSTALLED, false)
    fun setNodeInstalled(installed: Boolean) {
        getPrefs().edit().putBoolean(KEY_NODE_INSTALLED, installed).apply()
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
    }

    private operator fun SharedPreferences.get(key: String): String? =
        getString(key, null)
}
