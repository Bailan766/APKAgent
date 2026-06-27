package com.apkagent.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AI 模型配置（用户可自定义） */
data class AgentConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.6,
    val systemExtra: String = ""
) {
    fun isValid(): Boolean = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 配置存储：优先用 EncryptedSharedPreferences 加密保存 API Key；
 * 若 Android Keystore 不可用（部分设备/模拟器），自动降级为普通 SharedPreferences，
 * 避免因加密初始化失败导致整个 App 启动崩溃。
 */
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
            context,
            "apk_agent_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        Log.w("SettingsStore", "加密存储不可用，降级为明文存储", e)
        context.getSharedPreferences("apk_agent_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val _config = MutableStateFlow(loadInternal())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    private fun loadInternal(): AgentConfig = try {
        val s = getPrefs()
        AgentConfig(
            baseUrl = s.getString(KEY_BASE, "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            apiKey = s.getString(KEY_APIKEY, "") ?: "",
            model = s.getString(KEY_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            temperature = (s.getString(KEY_TEMP, "0.6") ?: "0.6").toDoubleOrNull() ?: 0.6,
            systemExtra = s.getString(KEY_SYS, "") ?: ""
        )
    } catch (e: Throwable) {
        Log.e("SettingsStore", "读取配置失败，使用默认值", e)
        AgentConfig()
    }

    fun save(cfg: AgentConfig) {
        try {
            getPrefs().edit()
                .putString(KEY_BASE, cfg.baseUrl)
                .putString(KEY_APIKEY, cfg.apiKey)
                .putString(KEY_MODEL, cfg.model)
                .putString(KEY_TEMP, cfg.temperature.toString())
                .putString(KEY_SYS, cfg.systemExtra)
                .apply()
        } catch (e: Throwable) {
            Log.e("SettingsStore", "保存配置失败", e)
        }
        _config.value = cfg
    }

    companion object {
        private const val KEY_BASE = "base_url"
        private const val KEY_APIKEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMP = "temperature"
        private const val KEY_SYS = "system_extra"
    }
}
