package app.musicflow.core.common

import java.util.Locale

object Formatting {
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Parses "3:45" / "1:02:03" into milliseconds. */
    fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
    }
}
