package com.pocketcode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val build: Int, val title: String, val notes: String, val assetUrl: String, val size: Long)

/**
 * Проверка и установка обновлений из GitHub Releases. CI выкладывает APK в релиз «build-N».
 * Репозиторий приватный, поэтому нужен токен с доступом Contents: read.
 */
class Updater(private val context: Context) {
    companion object {
        const val REPO = "CuteKotik825/Open-code-for-android-"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Suppress("DEPRECATION")
    fun currentBuild(): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    fun currentName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Самая новая сборка из релизов или null, если релизов нет. Ошибки — исключением с понятным текстом. */
    suspend fun latest(token: String): UpdateInfo? = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Репозиторий приватный: добавь GitHub-токен в настройках (раздел Git)." }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=10")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404 || resp.code == 401 || resp.code == 403) {
                error("Нет доступа к репозиторию $REPO (код ${resp.code}). Токену нужно право Contents: read на этот репозиторий.")
            }
            if (!resp.isSuccessful) error("GitHub ответил кодом ${resp.code}")
            val arr: JsonArray = Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
            arr.mapNotNull { el ->
                val o: JsonObject = el.jsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val build = tag.removePrefix("build-").toIntOrNull() ?: return@mapNotNull null
                val asset = o["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return@mapNotNull null
                UpdateInfo(
                    build,
                    o["name"]?.jsonPrimitive?.content ?: tag,
                    (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    asset["url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }.maxByOrNull { it.build }
        }
    }

    suspend fun download(info: UpdateInfo, token: String, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "PocketCode-${info.build}.apk")
        val req = Request.Builder()
            .url(info.assetUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Не удалось скачать (код ${resp.code})")
            val body = resp.body ?: error("Пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            var done = 0L
            var shown = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - shown > 1024 * 1024) {
                            shown = done
                            onProgress("Скачиваю: ${done / 1048576} из ${if (total > 0) total / 1048576 else "?"} МБ")
                        }
                    }
                }
            }
        }
        target
    }

    /** Открывает системный установщик. Если нужно разрешение на установку из этого приложения, открывает его настройки. */
    fun install(apk: File): String {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Разреши установку приложений из PocketCode в открывшихся настройках и нажми «Установить» ещё раз."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return "Открыт установщик Android."
    }
}
