package com.glookogluroo.bridge.util

object ErrorFormatter {
    fun describe(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            if (depth > 0) {
                append("\nCaused by: ")
            }
            append(current::class.java.simpleName)
            val message = current.message
            if (!message.isNullOrBlank()) {
                append(": ")
                append(message)
            } else {
                append(": (no message)")
            }
            current = current.cause
            depth++
        }
    }

    fun describeOrDefault(throwable: Throwable, fallback: String): String {
        val description = describe(throwable)
        return if (description.contains("(no message)") && throwable.cause == null) {
            fallback
        } else {
            description
        }
    }
}
