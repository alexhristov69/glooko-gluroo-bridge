package com.glookogluroo.bridge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonFormatterTest {
    @Test
    fun prettify_formatsCompactJson() {
        val raw = """{"eventType":"Meal Bolus","insulin":2.0}"""
        val pretty = JsonFormatter.prettify(raw)

        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"eventType\""))
        assertTrue(pretty.contains("2.0"))
    }

    @Test
    fun prettify_returnsOriginalWhenNotJson() {
        val raw = "not json"
        assertEquals(raw, JsonFormatter.prettify(raw))
    }
}
