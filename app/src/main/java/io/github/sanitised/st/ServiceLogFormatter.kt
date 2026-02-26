package io.github.sanitised.st

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SERVICE_LOG_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX", Locale.US)
    .withZone(ZoneId.systemDefault())

fun formatServiceLogLine(message: String, nowMs: Long = System.currentTimeMillis()): String {
    val timestamp = SERVICE_LOG_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(nowMs))
    return "$timestamp: $message\n"
}
