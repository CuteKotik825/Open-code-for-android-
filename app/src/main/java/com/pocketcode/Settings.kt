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

/** Именованный набор «провайдер + URL + модель + ключ»; активный профиль определяет, куда идут запросы. */
@kotlinx.serialization.Serializable
data class Profile(
    val id: String,
    val label: String,
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

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
    /** true — при запуске приложения начинать с пустого чата, а не открывать последний. */
    val freshOnStart: Boolean = true,
    /** true — не спрашивать про безопасные команды, которые только читают (ls, cat, git status…). */
    val smartApprove: Boolean = true,
    /** Свои разрешённые начала команд, по одному в строке. */
    val allowRules: String = "",
    /** Кэширование контекста (prompt caching) у провайдеров, которые это поддерживают. */
    val promptCaching: Boolean = true,
    val maxSteps: Int = 60,
    val permissionRules: String = "",
    val fileHooks: String = "",
    val autoContinue: Boolean = true,
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

    fun loadProfiles(): List<Profile> {
        val stored = prefs.getString("profiles", null)
        if (stored != null) {
            runCatching { Json.decodeFromString<List<Profile>>(stored) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val s = load()
        return listOf(Profile("default", "Основной", s.provider, s.baseUrl, s.model, s.apiKey))
    }

    fun loadActiveId(): String = prefs.getString("activeProfile", null) ?: "default"

    fun saveProfiles(list: List<Profile>, activeId: String) {
        prefs.edit()
            .putString("profiles", Json.encodeToString(list))
            .putString("activeProfile", activeId)
            .apply()
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
            freshOnStart = prefs.getBoolean("freshOnStart", d.freshOnStart),
            smartApprove = prefs.getBoolean("smartApprove", d.smartApprove),
            allowRules = prefs.getString("allowRules", null) ?: "",
            promptCaching = prefs.getBoolean("promptCaching", d.promptCaching),
            maxSteps = prefs.getInt("maxSteps", d.maxSteps),
            permissionRules = prefs.getString("permissionRules", d.permissionRules) ?: "",
            fileHooks = prefs.getString("fileHooks", d.fileHooks) ?: "",
            autoContinue = prefs.getBoolean("autoContinue", d.autoContinue),
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

    fun loadRootEnabled(): Boolean = prefs.getBoolean("rootEnabled", false)

    fun saveRootEnabled(v: Boolean) {
        prefs.edit().putBoolean("rootEnabled", v).apply()
    }

    fun loadQueue(): List<String> = try {
        Json.decodeFromString<List<String>>(prefs.getString("queue", null) ?: "[]")
    } catch (e: Exception) {
        emptyList()
    }

    fun saveQueue(list: List<String>) {
        prefs.edit().putString("queue", Json.encodeToString(list)).apply()
    }

    fun loadDevices(): List<com.pocketcode.tools.Device> = try {
        Json.decodeFromString<List<com.pocketcode.tools.Device>>(prefs.getString("devices", null) ?: "[]")
    } catch (e: Exception) {
        emptyList()
    }

    fun loadMcp(): List<com.pocketcode.tools.McpServer> = try {
        Json { ignoreUnknownKeys = true }.decodeFromString(prefs.getString("mcp", null) ?: "[]")
    } catch (e: Exception) {
        emptyList()
    }

    fun saveMcp(list: List<com.pocketcode.tools.McpServer>) {
        prefs.edit().putString("mcp", Json.encodeToString(list)).apply()
    }

    fun saveDevices(list: List<com.pocketcode.tools.Device>) {
        prefs.edit().putString("devices", Json.encodeToString(list)).apply()
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
            .putBoolean("freshOnStart", s.freshOnStart)
            .putBoolean("smartApprove", s.smartApprove)
            .putString("allowRules", s.allowRules)
            .putBoolean("promptCaching", s.promptCaching)
            .putInt("maxSteps", s.maxSteps)
            .putString("permissionRules", s.permissionRules)
            .putString("fileHooks", s.fileHooks)
            .putBoolean("autoContinue", s.autoContinue)
            .apply()
    }
}
