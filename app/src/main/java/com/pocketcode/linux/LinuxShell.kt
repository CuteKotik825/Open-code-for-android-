package com.pocketcode.linux

import com.pocketcode.tools.Shell
import com.pocketcode.tools.Workspace
import com.pocketcode.tools.runProcess
import java.io.File

/** Shell, выполняющий команды внутри Alpine Linux (proot). Папка проекта — /work. */
class LinuxShell(private val env: LinuxEnv, private val ws: Workspace) : Shell {
    override val info =
        "Linux Alpine (proot, эмуляция root): настоящий sh, apk (например `apk add python3 py3-pip nodejs npm git build-base`), " +
            "пакеты ставятся из интернета. Папка проекта смонтирована как /work, команды стартуют в ней. " +
            "Файлы вне /work живут в закрытом rootfs. Нет systemd и демонов, интерактивный ввод не поддерживается."

    override suspend fun run(command: String, cwd: File, timeoutSec: Long): String =
        runProcess(env.processBuilder(command, ws.root, cwd), timeoutSec)
}

/** Переключаемый Shell: Linux, если он установлен и включён, иначе системный sh Android. */
class SwitchableShell(private val android: Shell, private val linux: () -> Shell?) : Shell {
    private val current: Shell get() = linux() ?: android
    override val info: String get() = current.info
    override suspend fun run(command: String, cwd: File, timeoutSec: Long) = current.run(command, cwd, timeoutSec)
}
