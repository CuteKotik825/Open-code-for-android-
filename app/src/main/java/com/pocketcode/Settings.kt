package com.pocketcode

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Provider(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com", "claude-sonnet-5-5"),
    OPENAI("OpenAI-совместимый", "https://api.openai.com/v1", "gpt-4o"),
}

data class Settings(
    val provider: Provider = Provider.ANTHROPIC,
    val baseUrl: String = Provider.ANTHROPIC.defaultBaseUrl,
    val model: String = Provider.ANTHROPIC.defaultModel,
    val apiKey: String = "",
    val githubToken: String = "",
    val gitName: String = "PocketCode",
    val gitEmail: String = "pocketcode@users.noreply.github.com",
    val autoApprove: Boolean = false,
    val contextTokens: Int = 128_000,
)

const val DEFAULT_PROJECT = "main"

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pocketcode_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Если Keystore недоступен на устройстве — обычное хранилище приложения
        context.getSharedPreferences("pocketcode_plain", Context.MODE_PRIVATE)
    }

    fun load(): Settings {
        val d = Settings()
        val provider = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }
            .getOrDefault(d.provider)
        return Settings(
            provider = provider,
            baseUrl = prefs.getString("baseUrl", null) ?: provider.defaultBaseUrl,
            model = prefs.getString("model", null) ?: provider.defaultModel,
            apiKey = prefs.getString("apiKey", null) ?: "",
            githubToken = prefs.getString("githubToken", null) ?: "",
            gitName = prefs.getString("gitName", null) ?: d.gitName,
            gitEmail = prefs.getString("gitEmail", null) ?: d.gitEmail,
            autoApprove = prefs.getBoolean("autoApprove", false),
            contextTokens = prefs.getInt("contextTokens", d.contextTokens),
        )
    }

    fun loadProject(): String = prefs.getString("project", null) ?: DEFAULT_PROJECT

    fun saveProject(name: String) {
        prefs.edit().putString("project", name).apply()
    }

    /** true — проекты в общей папке PocketCode, видимой в файловых менеджерах. */
    fun loadSharedStorage(): Boolean = prefs.getBoolean("sharedStorage", false)

    fun saveSharedStorage(shared: Boolean) {
        prefs.edit().putBoolean("sharedStorage", shared).apply()
    }

    /** Существующие папки на телефоне, добавленные как проекты: имя -> путь. */
    fun loadExternalProjects(): Map<String, String> = try {
        Json.decodeFromString<Map<String, String>>(prefs.getString("externalProjects", null) ?: "{}")
    } catch (e: Exception) {
        emptyMap()
    }

    fun saveExternalProjects(map: Map<String, String>) {
        prefs.edit().putString("externalProjects", Json.encodeToString(map)).apply()
    }

    /** Использовать ли Linux (proot) для bash, если он установлен. */
    fun loadLinuxEnabled(): Boolean = prefs.getBoolean("linuxEnabled", true)

    fun saveLinuxEnabled(v: Boolean) {
        prefs.edit().putBoolean("linuxEnabled", v).apply()
    }

    fun loadLinuxNoSeccomp(): Boolean = prefs.getBoolean("linuxNoSeccomp", false)

    fun saveLinuxNoSeccomp(v: Boolean) {
        prefs.edit().putBoolean("linuxNoSeccomp", v).apply()
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("provider", s.provider.name)
            .putString("baseUrl", s.baseUrl.trim())
            .putString("model", s.model.trim())
            .putString("apiKey", s.apiKey.trim())
            .putString("githubToken", s.githubToken.trim())
            .putString("gitName", s.gitName.trim())
            .putString("gitEmail", s.gitEmail.trim())
            .putBoolean("autoApprove", s.autoApprove)
            .putInt("contextTokens", s.contextTokens)
            .apply()
    }
}
