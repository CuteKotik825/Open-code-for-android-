package com.pocketcode.tools

import com.pocketcode.agent.Agent
import com.pocketcode.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Параллельные агенты-исполнители: каждый со своим контекстом и инструментами, работают одновременно с основным. */
class Workers(private val scope: CoroutineScope) {
    private class W(val name: String, val task: String, val deferred: Deferred<String>)
    private val map = LinkedHashMap<String, W>()

    @Synchronized
    fun spawn(name: String, task: String, factory: () -> Agent) {
        map[name]?.let { require(!it.deferred.isActive) { "Исполнитель «$name» уже работает" } }
        require(map.values.count { it.deferred.isActive } < 3) { "Одновременно можно не более 3 исполнителей" }
        val agent = factory()
        map[name] = W(name, task, scope.async(Dispatchers.Default) {
            val cur = StringBuilder()
            var err: String? = null
            agent.execute(task).collect { ev ->
                when (ev) {
                    is AgentEvent.TextDelta -> cur.append(ev.text)
                    is AgentEvent.ToolCall -> cur.clear()
                    is AgentEvent.Error -> err = ev.message
                    else -> {}
                }
            }
            val text = cur.toString().trim()
            if (text.isEmpty() && err != null) "Ошибка: $err" else text.ifEmpty { "(исполнитель ничего не написал)" }
        })
    }

    @Synchronized fun list(): List<Triple<String, Boolean, String>> = map.values.map { Triple(it.name, it.deferred.isActive, it.task.take(80)) }

    suspend fun result(name: String, waitSec: Int): String {
        val w = synchronized(this) { map[name] } ?: return "Нет исполнителя «$name»."
        if (w.deferred.isActive) {
            withTimeoutOrNull(waitSec * 1000L) { w.deferred.await() } ?: return "Исполнитель «$name» ещё работает. Вызови ещё раз позже."
        }
        return w.deferred.await()
    }

    @Synchronized fun cancelAll() { map.values.forEach { if (it.deferred.isActive) it.deferred.cancel() } }
}

class SpawnTool(private val workers: Workers, private val factory: (String) -> Agent) : Tool {
    override val name = "spawn"
    override val description =
        "Запустить параллельного исполнителя: отдельный агент со своим контекстом, который сам правит файлы и запускает команды, пока ты занят другим. " +
            "Давай исполнителям разные файлы или модули, чтобы они не мешали друг другу. Задание должно быть самодостаточным. " +
            "Результат забирай через worker_result. До 3 одновременно."
    override val schema = objSchema(
        Prop("name", "string", "Короткое имя исполнителя"),
        Prop("task", "string", "Полное задание"),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "исполнитель ${input.str("name")}: ${(input.str("task") ?: "").take(80)}"
    override suspend fun run(input: JsonObject): String {
        val n = input.need("name").trim()
        val task = input.need("task")
        workers.spawn(n, task) { factory(task) }
        return "Исполнитель «$n» запущен. Продолжай свою часть, затем забери результат через worker_result."
    }
}

class WorkerResultTool(private val workers: Workers) : Tool {
    override val name = "worker_result"
    override val description =
        "Результат параллельного исполнителя. name — имя; без name покажет список. Ждёт до 90 секунд; если не готов, вызови снова. " +
            "Перед завершением работы дождись всех исполнителей."
    override val schema = objSchema(Prop("name", "string", "Имя исполнителя", required = false))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "жду исполнителя ${input.str("name") ?: "(список)"}"
    override suspend fun run(input: JsonObject): String {
        val n = input.str("name")
        if (n == null) {
            val l = workers.list()
            return if (l.isEmpty()) "Исполнителей нет." else l.joinToString("\n") { "${it.first}: ${if (it.second) "работает" else "готов"} — ${it.third}" }
        }
        return workers.result(n, 90)
    }
}
