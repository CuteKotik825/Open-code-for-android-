package com.pocketcode.session

import com.pocketcode.llm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Элементы ленты чата на экране. */
@Serializable
sealed class UiItem {
    @Serializable
    data class User(val text: String) : UiItem()

    @Serializable
    data class Assistant(val text: String) : UiItem()

    @Serializable
    data class Tool(
        val name: String,
        val description: String,
        val output: String?,
        val isError: Boolean,
    ) : UiItem()

    @Serializable
    data class Error(val text: String) : UiItem()

    /** Служебная строка: сжатие контекста и т.п. */
    @Serializable
    data class Notice(val text: String) : UiItem()
}

@Serializable
data class SessionMeta(val id: String, val title: String, val updatedAt: Long, val project: String = "")

@Serializable
data class SessionData(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<UiItem>,
    val history: List<Message>,
    val project: String = "",
)

/** Хранит сессии в файлах: index.json (список) и <id>.json (содержимое). */
class SessionStore(private val dir: File) {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private val indexFile get() = File(dir, "index.json")

    fun list(): List<SessionMeta> = try {
        json.decodeFromString<List<SessionMeta>>(indexFile.readText()).sortedByDescending { it.updatedAt }
    } catch (e: Exception) {
        emptyList()
    }

    fun load(id: String): SessionData? = try {
        json.decodeFromString<SessionData>(File(dir, "$id.json").readText())
    } catch (e: Exception) {
        null
    }

    suspend fun save(data: SessionData) = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            writeAtomic(File(dir, "${data.id}.json"), json.encodeToString(data))
            val index = list().filter { it.id != data.id } + SessionMeta(data.id, data.title, data.updatedAt, data.project)
            writeAtomic(indexFile, json.encodeToString(index))
        }
    }

    suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            File(dir, "$id.json").delete()
            if (dir.exists()) {
                writeAtomic(indexFile, json.encodeToString(list().filter { it.id != id }))
            }
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
