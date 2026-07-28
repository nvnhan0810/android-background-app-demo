package com.nvnhan0810.backgrounddemo.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Test
    fun parsesSpaceEqualsColonAndCommaDecimal() {
        val text = """
            A12 5
            B03=2
            C1:3,5
            ignore this line
            SP_01 10
        """.trimIndent()
        val lines = MessageParser.parse(text)
        assertEquals(4, lines.size)
        assertEquals(ParsedLine("A12", 5.0), lines[0])
        assertEquals(ParsedLine("B03", 2.0), lines[1])
        assertEquals(ParsedLine("C1", 3.5), lines[2])
        assertEquals(ParsedLine("SP_01", 10.0), lines[3])
    }

    @Test
    fun jsonRoundTrip() {
        val original = listOf(ParsedLine("A12", 5.0), ParsedLine("B", 1.25))
        val json = MessageParser.toJson(original)
        val back = MessageParser.fromJson(json)
        assertEquals(original, back)
        assertTrue(MessageParser.summarize(original).contains("A12=5"))
    }
}
