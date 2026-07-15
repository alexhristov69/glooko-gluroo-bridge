package com.glookogluroo.bridge.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object JsonFormatter {
    private val parser = Json { ignoreUnknownKeys = true }

    private val pretty = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun prettify(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return raw
        return runCatching {
            val element: JsonElement = parser.parseToJsonElement(trimmed)
            pretty.encodeToString(JsonElement.serializer(), element)
        }.getOrElse { raw }
    }
}
