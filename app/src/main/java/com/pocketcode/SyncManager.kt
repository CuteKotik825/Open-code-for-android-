package com.pocketcode

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.concurrent.TimeUnit

/** Задача, отправленная с пульта на телефон-исполнитель. */
@Serializable
data class RemoteTask(
    val id: String,
    val project: String,
    val prompt: String,
    val from: String,
    val created: Long,
)

/** Состояние задачи: пишет исполнитель. */
@Serializable
data class RemoteState(
    val id: String,
    val status: String, // running, done, error
    val answer: String = "",
    val by: String = "",
    val updated: Long = 0,
)

data class RemoteRow(val task: RemoteTask, val state: RemoteState?)

data class SyncResult(val ok: Boolean, val message: String)

/**
 * Синхронизация проектов между телефонами через приватный репозиторий GitHub (работает из любых сетей, своего сервера не нужно).
 * Рабочая копия лежит в filesDir/sync/work: projects/<имя>/ — копии проектов, remote/ — очередь удалённых задач.
 */
class SyncManager(private val app: Context, private val projectDir: (String) -> File) {
    private val work = File(app.filesDir, "sync/work")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val skipDirs = setOf(".git", "node_modules", "__pycache__", ".gradle", "build", "dist", ".venv", "venv", ".idea", ".cache", ".next", "target")
    private val skipFile = Regex("""(^\.env.*|.*\.(pem|key|keystore|jks|p12|pyc|class|apk|aab|zip|so)$|^id_(rsa|ed25519).*)""", RegexOption.IGNORE_CASE)
    private val maxFile = 5L * 1024 * 1024
    private val maxFiles = 6000

    private fun skipped(rel: String, isDir: Boolean): Boolean {
        val parts = rel.split('/')
        if (parts.dropLast(if (isDir) 0 else 1).any { it in skipDirs }) return true
        return !isDir && skipFile.matches(parts.last())
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        if (a.lastModified() == b.lastModified()) return true
        return runCatching { a.readBytes().contentEquals(b.readBytes()) }.getOrDefault(false)
    }

    /** Копирует src в dst (только отличающиеся файлы). Возвращает число изменений. */
    private fun mirror(src: File, dst: File): Int {
        var changed = 0
        var count = 0
        dst.mkdirs()
        src.walkTopDown().onEnter { d -> d == src || !skipped(d.relativeTo(src).invariantSeparatorsPath, true) }.forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (skipped(rel, false) || f.length() > maxFile) return@forEach
            if (++count > maxFiles) return@forEach
            val t = File(dst, rel)
            if (!t.isFile || !sameContent(f, t)) {
                t.parentFile?.mkdirs()
                f.copyTo(t, overwrite = true)
                t.setLastModified(f.lastModified())
                changed++
            }
        }
        // удаляем в dst то, чего нет в src (кроме пропускаемого)
        dst.walkBottomUp().forEach { t ->
            if (t == dst) return@forEach
            val rel = t.relativeTo(dst).invariantSeparatorsPath
            if (skipped(rel, t.isDirectory)) return@forEach
            val s = File(src, rel)
            if (t.isFile && !s.exists()) { t.delete(); changed++ }
            else if (t.isDirectory && !s.exists() && (t.list()?.isEmpty() == true)) t.delete()
        }
        return changed
    }

    private fun creds(token: String) = UsernamePasswordCredentialsProvider(token, "")

    private fun openGit(repoUrl: String): Git {
        work.mkdirs()
        val git = if (File(work, ".git").exists()) Git.open(work) else Git.init().setDirectory(work).setInitialBranch("main").call()
        val cfg = git.repository.config
        if (cfg.getString("remote", "origin", "url") != repoUrl) {
            cfg.setString("remote", "origin", "url", repoUrl)
            cfg.save()
        }
        return git
    }

    /**
     * Полная синхронизация: свои изменения в репозиторий, чужие из репозитория в проекты.
     * При конфликте файла берётся версия с другого телефона, а ваша сохраняется рядом как «файл.conflict-local».
     */
    suspend fun sync(repoUrl: String, token: String, projects: List<String>, device: String): SyncResult = withContext(Dispatchers.IO) {
        if (repoUrl.isBlank() || token.isBlank()) return@withContext SyncResult(false, "Укажи адрес репозитория и GitHub-токен (☰ → Настройки).")
        mutex.withLock {
            try {
                val git = openGit(repoUrl)
                git.use {
                    val author = PersonIdent(device.ifBlank { "pocketcode" }, "pocketcode@local")
                    // 1. проекты → рабочая копия
                    var pushedFiles = 0
                    for (p in projects) {
                        val dir = projectDir(p)
                        if (dir.isDirectory) pushedFiles += mirror(dir, File(work, "projects/$p"))
                    }
                    // 2. коммит своих изменений
                    git.add().addFilepattern(".").call()
                    git.add().setUpdate(true).addFilepattern(".").call()
                    val dirty = !git.status().call().isClean
                    if (dirty) git.commit().setAuthor(author).setCommitter(author).setMessage("sync: $device").setAllowEmpty(false).call()
                    // 3. получаем чужое
                    git.fetch().setRemote("origin").setCredentialsProvider(creds(token))
                        .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/origin/main")).call()
                    val remote = git.repository.resolve("refs/remotes/origin/main")
                    val head = git.repository.resolve("HEAD")
                    var note = ""
                    if (remote != null) {
                        if (head == null) {
                            git.checkout().setCreateBranch(true).setName("main").setStartPoint("origin/main").setForce(true).call()
                        } else if (remote != head) {
                            val oursBlobs = HashMap<String, ByteArray>()
                            val mr = git.merge().include(remote).setCommit(true).setMessage("merge: $device").call()
                            if (mr.mergeStatus == MergeResult.MergeStatus.CONFLICTING) {
                                val paths = mr.conflicts?.keys.orEmpty().toList()
                                for (p in paths) {
                                    runCatching {
                                        val id = git.repository.resolve("$head:$p")
                                        if (id != null) oursBlobs[p] = git.repository.open(id).bytes
                                    }
                                    val ok = runCatching { git.checkout().setStage(CheckoutCommand.Stage.THEIRS).addPath(p).call() }.isSuccess
                                    if (!ok) runCatching { git.add().addFilepattern(p).call() }
                                    oursBlobs[p]?.let { b -> runCatching { File(work, "$p.conflict-local").writeBytes(b) } }
                                }
                                git.add().addFilepattern(".").call()
                                git.commit().setAuthor(author).setCommitter(author).setMessage("merge conflicts: $device").call()
                                note = " Конфликтов: ${paths.size}, ваши версии сохранены как *.conflict-local."
                            } else if (!mr.mergeStatus.isSuccessful) {
                                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                                return@withContext SyncResult(false, "Слияние не удалось: ${mr.mergeStatus}")
                            }
                        }
                    }
                    // 4. отправляем
                    val ahead = git.repository.resolve("HEAD") != null
                    if (ahead) {
                        val res = git.push().setRemote("origin").setCredentialsProvider(creds(token))
                            .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
                        val bad = res.flatMap { it.remoteUpdates }.firstOrNull { it.status.name.startsWith("REJECTED") || it.status.name == "NOT_ATTEMPTED" }
                        if (bad != null) return@withContext SyncResult(false, "GitHub отклонил отправку (${bad.status}). Проверь права токена (repo).")
                    }
                    // 5. рабочая копия → проекты
                    var pulledFiles = 0
                    val base = File(work, "projects")
                    for (p in base.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()) {
                        if (p !in projects) continue
                        val dir = projectDir(p)
                        dir.mkdirs()
                        pulledFiles += mirror(File(base, p), dir)
                    }
                    SyncResult(true, "Готово: отправлено файлов $pushedFiles, получено $pulledFiles.$note")
                }
            } catch (e: Throwable) {
                SyncResult(false, "Ошибка синхронизации: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Названия проектов, которые есть в репозитории (чтобы выбрать на втором телефоне). */
    fun remoteProjects(): List<String> = File(work, "projects").listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()

    // ---------- очередь удалённых задач ----------

    private fun inbox() = File(work, "remote/inbox").also { it.mkdirs() }
    private fun outbox() = File(work, "remote/outbox").also { it.mkdirs() }

    fun addTask(project: String, prompt: String, device: String): RemoteTask {
        val id = System.currentTimeMillis().toString() + "-" + (1000..9999).random()
        val t = RemoteTask(id, project, prompt, device, System.currentTimeMillis())
        File(inbox(), "$id.json").writeText(json.encodeToString(t))
        return t
    }

    fun writeState(st: RemoteState) {
        File(outbox(), "${st.id}.json").writeText(json.encodeToString(st.copy(updated = System.currentTimeMillis())))
    }

    fun rows(): List<RemoteRow> {
        val tasks = inbox().listFiles { f -> f.extension == "json" }?.mapNotNull { f ->
            runCatching { json.decodeFromString<RemoteTask>(f.readText()) }.getOrNull()
        }.orEmpty()
        return tasks.map { t ->
            val st = runCatching { json.decodeFromString<RemoteState>(File(outbox(), "${t.id}.json").readText()) }.getOrNull()
            RemoteRow(t, st)
        }.sortedByDescending { it.task.created }
    }

    /** Задачи, которые ещё никто не выполнил. */
    fun pending(): List<RemoteTask> = rows().filter { it.state == null }.map { it.task }.sortedBy { it.created }

    fun clearFinished() {
        for (r in rows()) if (r.state?.status == "done" || r.state?.status == "error") {
            File(inbox(), "${r.task.id}.json").delete(); File(outbox(), "${r.task.id}.json").delete()
        }
    }
}

object SyncScheduler {
    fun apply(context: Context, intervalMin: Int) {
        val wm = WorkManager.getInstance(context)
        if (intervalMin <= 0) { wm.cancelUniqueWork("pc-sync"); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, intervalMin).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork("pc-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = (applicationContext as PocketCodeApp).controller
        withContext(Dispatchers.Main) { controller.syncTick() }.join()
        return Result.success()
    }
}
