package com.pocketcode.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Сохранённое устройство для ADB: подпись и адрес ip:порт. */
@Serializable
data class Device(val id: String, val label: String, val address: String)

val ADDRESS_RE = Regex("^[A-Za-z0-9._\\-]+:\\d{1,5}$")

/** Каждый вызов самодостаточен: сервер adb стартует, подключается к устройству и останавливается вместе с командой. */
fun wrapAdb(inner: String): String =
    "adb start-server >/dev/null 2>&1; $inner; c=\$?; adb kill-server >/dev/null 2>&1; exit \$c"

private val SAFE_SHELL = setOf("getprop", "dumpsys", "ps", "df", "uptime", "id", "whoami", "getenforce", "date")

/** ADB к другим Android-устройствам (по Wi-Fi) через настоящий adb в Linux. */
class AdbTool(
    private val ws: Workspace,
    private val linux: Shell,
    private val devices: () -> List<Device>,
) : Tool {
    override val name = "adb"
    override val description =
        "Отладка Android-устройств по ADB (как на ПК): shell, logcat, install, push, pull и т.д. " +
            "device — подпись сохранённого устройства или адрес ip:порт (не передавай -s). args — всё после слова adb, например: " +
            "devices | shell getprop ro.product.model | logcat -d -t 200 | install /work/app.apk | push /work/f.txt /sdcard/. " +
            "Файлы проекта лежат в /work. Опасные команды подтверждает пользователь."
    override val schema = objSchema(
        Prop("args", "string", "Аргументы adb"),
        Prop("device", "string", "Подпись устройства или ip:порт", required = false),
        Prop("timeout_sec", "integer", "Таймаут в секундах (по умолчанию 60, максимум 600)", required = false),
    )

    private fun args(input: JsonObject): String {
        val t = input.str("args").orEmpty().trim().removePrefix("adb ").trim()
        // -s подставляем сами
        val m = Regex("^-s\\s+\\S+\\s*").find(t)
        return if (m != null) t.removeRange(m.range).trim() else t
    }

    private fun resolve(input: JsonObject): String? {
        val raw = input.str("device")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val found = devices().firstOrNull { it.label.equals(raw, ignoreCase = true) || it.address == raw }
        val addr = found?.address ?: raw
        require(ADDRESS_RE.matches(addr)) { "Неизвестное устройство «$raw». Укажи подпись из списка или адрес вида 192.168.1.5:5555." }
        return addr
    }

    override fun needsApproval(input: JsonObject): Boolean {
        val a = args(input)
        if (listOf(";", "&", "|", ">", "<", "`", "$(").any { a.contains(it) }) return true
        val t = a.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cmd = t.firstOrNull() ?: return true
        val safe = when (cmd) {
            "devices", "get-state", "get-serialno", "version" -> true
            "logcat" -> "-d" in t && "-c" !in t
            "shell" -> {
                val s = t.getOrNull(1)
                s in SAFE_SHELL || (s == "pm" && t.getOrNull(2) == "list") || (s == "wm" && t.getOrNull(2) in setOf("size", "density"))
            }
            else -> false
        }
        return !safe
    }

    override fun describe(input: JsonObject): String {
        val d = input.str("device")?.let { " [$it]" }.orEmpty()
        return "adb$d " + args(input)
    }

    override suspend fun run(input: JsonObject): String {
        val a = args(input)
        require(a.isNotEmpty()) { "Не указаны аргументы adb" }
        val addr = resolve(input)
        val timeout = (input.int("timeout_sec") ?: 60).coerceIn(1, 600).toLong()
        val connect = if (addr != null) "adb connect $addr >/dev/null 2>&1; " else ""
        val sel = if (addr != null) "-s $addr " else ""
        val out = linux.run(wrapAdb("${connect}adb $sel$a"), ws.root, timeout)
        return if (out.contains("adb: not found") || out.contains("adb: command not found")) {
            "$out\nadb не установлен в Linux. Пользователю нужно нажать «Установить ADB» на экране «Устройства» или выполнить apk add android-tools."
        } else out
    }
}
