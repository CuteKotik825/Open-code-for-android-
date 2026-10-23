package com.pocketcode.tools

import java.io.File
import java.security.MessageDigest

private val WT_SKIP = setOf(".git", "node_modules", "build", "dist", "__pycache__", ".gradle", ".idea", "venv", ".venv", "target", ".pocketcode")

private fun hashOf(f: File): String {
    val md = MessageDigest.getInstance("MD5")
    f.inputStream().use { s ->
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Изолированная копия проекта для параллельного исполнителя; результат потом аккуратно вливается обратно. */
class WorkTree private constructor(val src: File, val copy: File, private val base: Map<String, String>) {
    companion object {
        fun create(src: File, dest: File): WorkTree {
            dest.deleteRecursively()
            dest.mkdirs()
            val base = HashMap<String, String>()
            var count = 0
            var bytes = 0L
            src.walkTopDown().onEnter { it == src || it.name !in WT_SKIP }.forEach { f ->
                if (!f.isFile) return@forEach
                count++
                bytes += f.length()
                require(count <= 8000 && bytes <= 150L * 1024 * 1024) { "Проект слишком большой для изолированной копии" }
                val rel = f.relativeTo(src).path
                val target = File(dest, rel)
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
                base[rel] = hashOf(f)
            }
            return WorkTree(src, dest, base)
        }
    }

    fun merge(): String {
        val merged = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val seen = HashSet<String>()
        copy.walkTopDown().onEnter { it == copy || it.name !in WT_SKIP }.forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(copy).path
            seen += rel
            val baseHash = base[rel]
            val copyHash = hashOf(f)
            if (copyHash == baseHash) return@forEach
            val main = File(src, rel)
            val mainHash = if (main.isFile) hashOf(main) else null
            when {
                mainHash == copyHash -> {}
                mainHash == baseHash -> { main.parentFile?.mkdirs(); f.copyTo(main, overwrite = true); merged += rel }
                else -> {
                    val alt = File(src, ".pocketcode/conflicts/${copy.name}/$rel")
                    alt.parentFile?.mkdirs(); f.copyTo(alt, overwrite = true); conflicts += rel
                }
            }
        }
        for ((rel, h) in base) {
            if (rel in seen) continue
            val main = File(src, rel)
            if (!main.isFile) continue
            if (hashOf(main) == h) { main.delete(); merged += "$rel (удалён)" } else conflicts += "$rel (удалён исполнителем, но изменён в основном)"
        }
        copy.deleteRecursively()
        return buildString {
            append("Изолированная копия влита: файлов ${merged.size}")
            if (merged.isNotEmpty()) append(" (").append(merged.take(15).joinToString(", ")).append(if (merged.size > 15) ", …" else "").append(")")
            if (conflicts.isNotEmpty()) {
                append(". КОНФЛИКТЫ (в основном проекте файл тоже менялся, версия исполнителя лежит в .pocketcode/conflicts/${copy.name}/): ")
                append(conflicts.joinToString(", "))
            }
        }
    }
}
