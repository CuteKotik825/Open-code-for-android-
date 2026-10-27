package com.pocketcode.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SnapshotMeta(val id: Long, val label: String, val files: Int, val bytes: Long)

/**
 * Снимки проекта перед каждой задачей агента, чтобы можно было откатить его изменения.
 * Не входят в снимок: .git (история git сама умеет откат) и тяжёлые служебные папки.
 */
class SnapshotStore(private val dir: File) {
    companion object {
        private val SKIP = setOf(".git", "node_modules", ".gradle", "build", ".venv", "venv", "__pycache__", ".cache")
        private const val MAX_BYTES = 150L * 1024 * 1024
        private const val MAX_FILES = 8000
        private const val KEEP = 10
    }

    private fun projectDir(project: String) = File(dir, project.replace('/', '_').replace('\\', '_').ifBlank { "_" })

    private fun skipped(rel: String) = rel.split('/').any { it in SKIP }

    private fun regularFiles(root: File): List<File>? {
        val out = ArrayList<File>()
        var total = 0L
        val rootPath = root.canonicalFile.toPath()
        var tooBig = false
        root.walkTopDown()
            .onEnter { d -> !skipped(rootPath.relativize(d.canonicalFile.toPath()).toString().replace('\\', '/')) || d == root }
            .forEach { f ->
                if (tooBig) return@forEach
                if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    total += f.length()
                    out += f
                    if (total > MAX_BYTES || out.size > MAX_FILES) tooBig = true
                }
            }
        return if (tooBig) null else out
    }

    /** Возвращает null, если проект слишком большой для снимка. */
    suspend fun create(project: String, root: File, label: String): SnapshotMeta? = withContext(Dispatchers.IO) {
        val files = regularFiles(root) ?: return@withContext null
        val pdir = projectDir(project).apply { mkdirs() }
        val id = System.currentTimeMillis()
        val rootPath = root.canonicalFile.toPath()
        var bytes = 0L
        val zipFile = File(pdir, "$id.zip")
        try {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                for (f in files) {
                    val rel = rootPath.relativize(f.canonicalFile.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    bytes += f.length()
                }
            }
        } catch (e: Exception) {
            zipFile.delete()
            return@withContext null
        }
        File(pdir, "$id.txt").writeText(label.replace('\n', ' ').take(100) + "\n${files.size} $bytes")
        prune(pdir)
        SnapshotMeta(id, label, files.size, bytes)
    }

    private fun prune(pdir: File) {
        val ids = pdir.listFiles { f -> f.extension == "zip" }?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.sortedDescending() ?: return
        ids.drop(KEEP).forEach {
            File(pdir, "$it.zip").delete()
            File(pdir, "$it.txt").delete()
        }
    }

    /** Текст файла из снимка (null, если файла нет или он не читается как текст). */
    suspend fun readFile(project: String, id: Long, path: String): String? = withContext(Dispatchers.IO) {
        val zip = File(projectDir(project), "$id.zip")
        if (!zip.exists()) return@withContext null
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == path) return@withContext runCatching { zin.readBytes().decodeToString() }.getOrNull()
            }
        }
        null
    }

    suspend fun list(project: String): List<SnapshotMeta> = withContext(Dispatchers.IO) {
        val pdir = projectDir(project)
        (pdir.listFiles { f -> f.extension == "zip" } ?: emptyArray())
            .mapNotNull { z ->
                val id = z.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
                val lines = File(pdir, "$id.txt").takeIf { it.exists() }?.readLines().orEmpty()
                val stat = lines.getOrNull(1)?.split(' ')
                SnapshotMeta(
                    id,
                    lines.getOrNull(0).orEmpty(),
                    stat?.getOrNull(0)?.toIntOrNull() ?: 0,
                    stat?.getOrNull(1)?.toLongOrNull() ?: 0L,
                )
            }
            .sortedByDescending { it.id }
    }

    /**
     * Возвращает проект к снимку: удаляет файлы, которых в нём не было (кроме .git и служебных папок),
     * и восстанавливает остальные. Перед этим делает ещё один снимок, чтобы откат можно было отменить.
     */
    suspend fun restore(project: String, root: File, id: Long): String = withContext(Dispatchers.IO) {
        val zip = File(projectDir(project), "$id.zip")
        require(zip.exists()) { "Снимок не найден" }
        create(project, root, "Перед откатом")
        val rootPath = root.canonicalFile.toPath()
        // 1. Убираем всё, что есть сейчас (кроме пропускаемых папок)
        root.listFiles()?.forEach { top -> if (top.name !in SKIP) top.deleteRecursively() }
        // 2. Распаковываем снимок
        var restored = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val target = File(root, e.name)
                val canon = target.canonicalFile.toPath()
                if (!canon.startsWith(rootPath)) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zin.copyTo(it) }
                restored++
            }
        }
        "Восстановлено файлов: $restored"
    }
}
