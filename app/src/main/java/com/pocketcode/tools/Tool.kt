package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.io.File

interface Tool {
    val name: String
    val description: String
    val schema: JsonObject

    /** Нужно ли спрашивать пользователя перед запуском. */
    fun needsApproval(input: JsonObject): Boolean

    /** true — спрашивать всегда, даже если включено «выполнять без подтверждения». */
    val alwaysAsk: Boolean get() = false

    /** Короткое описание вызова для интерфейса и диалога подтверждения. */
    fun describe(input: JsonObject): String

    /** Выполняется на IO-потоке. Ошибки — исключениями, агент вернёт их модели. */
    suspend fun run(input: JsonObject): String
}

data class Prop(
    val name: String,
    val type: String,
    val desc: String,
    val required: Boolean = true,
)

fun objSchema(vararg props: Prop): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for (p in props) {
            putJsonObject(p.name) {
                put("type", p.type)
                put("description", p.desc)
            }
        }
    }
    putJsonArray("required") {
        for (p in props) if (p.required) add(p.name)
    }
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.need(key: String): String =
    str(key) ?: throw IllegalArgumentException("Не указан обязательный параметр «$key»")

/** Рабочая папка агента. Файловые инструменты не выходят за её пределы. */
class Workspace(initialRoot: File) {
    /** Корень текущего проекта; меняется при переключении проекта. */
    var root: File = initialRoot.also { it.mkdirs() }
        set(value) {
            value.mkdirs()
            field = value
        }

    fun resolve(path: String): File {
        val f = if (path.startsWith("/")) File(path) else File(root, path)
        val canonical = f.canonicalFile
        val base = root.canonicalPath
        require(canonical.path == base || canonical.path.startsWith(base + File.separator)) {
            "Путь вне рабочей папки: $path"
        }
        return canonical
    }

    fun rel(f: File): String {
        val r = f.canonicalFile.relativeTo(root.canonicalFile).path
        return r.ifEmpty { "." }
    }
}

fun looksBinary(f: File): Boolean {
    f.inputStream().use { s ->
        val buf = ByteArray(1024)
        val n = s.read(buf)
        for (i in 0 until maxOf(n, 0)) if (buf[i] == 0.toByte()) return true
    }
    return false
}
