package com.nvnhan0810.backgrounddemo.ledger

/**
 * Một dòng đã bóc tách từ tin nhắn thô.
 *
 * Demo pattern (đã chốt):
 *   A12 5
 *   A12=5
 *   A12:5
 *   SP01 3,5   → qty = 3.5
 * Nhiều dòng trong 1 tin → nhiều ParsedLine.
 */
data class ParsedLine(
    val code: String,
    val qty: Double
)

object MessageParser {

    private val LINE = Regex(
        """^\s*([A-Za-z0-9_-]+)(?:\s*[:=]\s*|\s+)(-?\d+(?:[.,]\d+)?)\s*$"""
    )

    fun parse(rawText: String): List<ParsedLine> {
        val out = mutableListOf<ParsedLine>()
        rawText.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val m = LINE.matchEntire(line) ?: return@forEach
            val code = m.groupValues[1].uppercase()
            val qtyRaw = m.groupValues[2].replace(',', '.')
            val qty = qtyRaw.toDoubleOrNull() ?: return@forEach
            out += ParsedLine(code, qty)
        }
        return out
    }

    fun toJson(lines: List<ParsedLine>): String {
        return lines.joinToString(prefix = "[", postfix = "]") { line ->
            """{"code":"${escape(line.code)}","qty":${line.qty}}"""
        }
    }

    fun fromJson(json: String): List<ParsedLine> {
        if (json.isBlank() || json == "[]") return emptyList()
        val out = mutableListOf<ParsedLine>()
        val item = Regex("""\{"code":"([^"]*)","qty":(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)\}""")
        item.findAll(json).forEach { m ->
            val qty = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            out += ParsedLine(m.groupValues[1], qty)
        }
        return out
    }

    fun summarize(lines: List<ParsedLine>): String {
        if (lines.isEmpty()) return "(không khớp cú pháp)"
        return lines.joinToString(" | ") { "${it.code}=${formatQty(it.qty)}" }
    }

    fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) {
            qty.toLong().toString()
        } else {
            qty.toString()
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
