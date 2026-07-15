package com.glookogluroo.bridge.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val displayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun formatEpoch(epochMs: Long): String {
    if (epochMs == 0L) return "Never"
    return displayFormatter.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

fun formatInstant(instant: Instant): String {
    return displayFormatter.format(instant.atZone(ZoneId.systemDefault()))
}
