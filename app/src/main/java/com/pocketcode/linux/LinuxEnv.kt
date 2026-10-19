package com.pocketcode.linux

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Linux внутри приложения: Alpine rootfs + proot (лежит в jniLibs как libproot.so,
 * так как Android разрешает запускать только файлы из папки нативных библиотек).
 * Rootfs скачивается при установке, а не вшит в APK.
 */
class LinuxEnv(private val context: Context) {
    private val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val marker = File(base, "installed")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)

    /** true — запускать proot без seccomp (нужно на некоторых прошивках). */
    @Volatile
    var noSeccomp = false

    private val prootBin get() = File(nativeDir, "libproot.so")

    fun isSupported(): Boolean = prootBin.exists() && alpineArch() != null
    fun isInstalled(): Boolean = marker.exists() && File(rootfs, "bin/sh").exists()

    private fun alpineArch(): String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
        when (it) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armv7"
            else -> null
        }
    }

    private fun urls(arch: String): List<String> =
        listOf("3.20.3", "3.20.2", "3.19.4").map {
            "https://dl-cdn.alpinelinux.org/alpine/v${it.substringBeforeLast('.')}/releases/$arch/alpine-minirootfs-$it-$arch.tar.gz"
        }

    /** Команда запуска sh -c внутри Linux. Папка проекта видна как /work. */
    fun processBuilder(command: String, projectRoot: File, cwd: File): ProcessBuilder {
        val root = projectRoot.canonicalFile
        val rel = runCatching { cwd.canonicalFile.relativeTo(root).path }.getOrDefault("")
        val guestCwd = if (rel.isEmpty() || rel.startsWith("..")) "/work" else "/work/$rel"
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "root").mkdirs()
        val args = mutableListOf(
            prootBin.absolutePath,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc",
            "-b", "${root.absolutePath}:/work",
            "-w", guestCwd,
            "/bin/sh", "-c", command,
        )
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("TERM", "dumb")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
            put("PROOT_TMP_DIR", File(base, "ptmp").apply { mkdirs() }.absolutePath)
            put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").absolutePath)
            File(nativeDir, "libproot-loader32.so").takeIf { it.exists() }?.let { put("PROOT_LOADER_32", it.absolutePath) }
            if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
        }
        return pb
    }

    /** Скачивает и распаковывает rootfs, затем проверяет, что proot вообще работает. */
    suspend fun install(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val arch = alpineArch() ?: error("Эта архитектура процессора не поддерживается")
        require(prootBin.exists()) { "В приложении нет proot для этого телефона" }
        base.mkdirs()
        marker.delete()
        rootfs.deleteRecursively()
        rootfs.mkdirs()

        val archive = File(base, "rootfs.tar.gz")
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        var lastError: String? = null
        var ok = false
        for (url in urls(arch)) {
            onProgress("Скачиваю Alpine Linux…")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body ?: return@use
                    val total = body.contentLength()
                    var done = 0L
                    var lastShown = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(archive).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastShown > 512 * 1024) {
                                    lastShown = done
                                    onProgress("Скачиваю Alpine Linux: ${done / 1024} из ${if (total > 0) total / 1024 else "?"} КБ")
                                }
                            }
                        }
                    }
                    ok = true
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            if (ok) break
        }
        if (!ok) error("Не удалось скачать Alpine: $lastError")

        onProgress("Распаковываю…")
        extract(archive, rootfs)
        archive.delete()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "root").mkdirs()

        onProgress("Проверяю запуск…")
        noSeccomp = false
        var out = probe()
        if (!out.contains("pocketcode-ok")) {
            noSeccomp = true
            val retry = probe()
            if (retry.contains("pocketcode-ok")) out = retry
            else {
                noSeccomp = false
                error("proot не запустился на этом телефоне:\n" + out.take(1500))
            }
        }
        marker.writeText("ok")
        "Готово" + if (noSeccomp) " (режим без seccomp)" else ""
    }

    private fun probe(): String {
        val pb = processBuilder("echo pocketcode-ok; cat /etc/alpine-release", base, base)
        return try {
            val p = pb.start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
            text
        } catch (e: Exception) {
            "Ошибка запуска: ${e.message}"
        }
    }

    fun uninstall() {
        marker.delete()
        rootfs.deleteRecursively()
    }

    private fun extract(archive: File, dest: File) {
        val destCanon = dest.canonicalPath
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                val target = File(dest, name)
                val canon = target.canonicalFile.parentFile?.canonicalPath ?: continue
                if (canon != destCanon && !canon.startsWith(destCanon + File.separator)) continue
                when {
                    e.isDirectory -> {
                        target.mkdirs()
                        target.setReadable(true, true); target.setWritable(true, true); target.setExecutable(true, true)
                    }
                    e.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        Files.deleteIfExists(target.toPath())
                        Files.createSymbolicLink(target.toPath(), Paths.get(e.linkName))
                    }
                    e.isLink -> {
                        val src = File(dest, e.linkName.removePrefix("./"))
                        if (src.isFile) {
                            target.parentFile?.mkdirs()
                            src.copyTo(target, overwrite = true)
                        }
                    }
                    e.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        target.setReadable(true, true)
                        target.setWritable(true, true)
                        if (e.mode and 0b001_001_001 != 0) target.setExecutable(true, true)
                    }
                }
            }
        }
    }
}
