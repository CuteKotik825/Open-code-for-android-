package com.pocketcode.ui

/** Построчное сравнение для разбора правок по блокам. */
sealed class DOp {
    data class Eq(val line: String) : DOp()
    data class Del(val line: String) : DOp()
    data class Ins(val line: String) : DOp()
}

/** null, если файлы слишком большие для точного сравнения. */
fun diffLines(a: List<String>, b: List<String>): List<DOp>? {
    val n = a.size
    val m = b.size
    if (n.toLong() * m > 6_000_000L) return null
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
    val out = ArrayList<DOp>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { out += DOp.Eq(a[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out += DOp.Del(a[i]); i++ }
            else -> { out += DOp.Ins(b[j]); j++ }
        }
    }
    while (i < n) out += DOp.Del(a[i++])
    while (j < m) out += DOp.Ins(b[j++])
    return out
}

/** Диапазоны индексов подряд идущих изменений (блоки). */
fun hunkRanges(ops: List<DOp>): List<IntRange> {
    val res = ArrayList<IntRange>()
    var s = -1
    for (i in ops.indices) {
        val changed = ops[i] !is DOp.Eq
        if (changed && s < 0) s = i
        if (!changed && s >= 0) { res += s until i; s = -1 }
    }
    if (s >= 0) res += s until ops.size
    return res
}

/** Текст, в котором выбранный блок возвращён к старому виду, а остальные правки сохранены. */
fun revertHunk(ops: List<DOp>, range: IntRange): String {
    val out = ArrayList<String>()
    for ((i, op) in ops.withIndex()) {
        val inRange = i in range
        when (op) {
            is DOp.Eq -> out += op.line
            is DOp.Del -> if (inRange) out += op.line
            is DOp.Ins -> if (!inRange) out += op.line
        }
    }
    return out.joinToString("\n")
}
