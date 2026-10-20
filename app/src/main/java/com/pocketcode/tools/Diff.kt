package com.pocketcode.tools

/** Короткая сводка изменений между двумя версиями текста: «−2 +3» и сами строки (до 10 с каждой стороны). */
fun lineDiff(old: String, new: String): String {
    val a = old.lines()
    val b = new.lines()
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
        endA--
        endB--
    }
    val removed = a.subList(start, endA)
    val added = b.subList(start, endB)
    if (removed.isEmpty() && added.isEmpty()) return "без изменений"
    return buildString {
        append("−${removed.size} +${added.size} строк")
        append(" (с ${start + 1}-й)")
        removed.take(10).forEach { append("\n- ").append(it.take(160)) }
        if (removed.size > 10) append("\n- … ещё ${removed.size - 10}")
        added.take(10).forEach { append("\n+ ").append(it.take(160)) }
        if (added.size > 10) append("\n+ … ещё ${added.size - 10}")
    }
}
