package app.musicflow.core.common

/** One entry of an M3U / extended M3U playlist. */
data class M3uEntry(
    val location: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSeconds: Long? = null,
)

data class M3uPlaylist(val name: String?, val entries: List<M3uEntry>)

/** Minimal, order-preserving (extended) M3U reader/writer. */
object M3u {
    private val extInf = Regex("""^#EXTINF:\s*(-?\d+)(?:\s+[^,]*)?,(.*)$""")

    fun parse(text: String): M3uPlaylist {
        var name: String? = null
        var pending: Triple<Long?, String?, String?>? = null
        val entries = mutableListOf<M3uEntry>()
        text.removePrefix("﻿").lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:") -> name = line.removePrefix("#PLAYLIST:").trim()
                line.startsWith("#EXTINF") -> {
                    val m = extInf.find(line)
                    if (m != null) {
                        val seconds = m.groupValues[1].toLongOrNull()?.takeIf { it >= 0 }
                        val display = m.groupValues[2].trim()
                        val sep = display.indexOf(" - ")
                        pending = if (sep > 0) {
                            Triple(seconds, display.substring(sep + 3).trim(), display.substring(0, sep).trim())
                        } else Triple(seconds, display.ifEmpty { null }, null)
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    entries += M3uEntry(line, title = p?.second, artist = p?.third, durationSeconds = p?.first)
                    pending = null
                }
            }
        }
        return M3uPlaylist(name, entries)
    }

    fun write(playlist: M3uPlaylist): String = buildString {
        appendLine("#EXTM3U")
        playlist.name?.let { appendLine("#PLAYLIST:$it") }
        playlist.entries.forEach { e ->
            val display = listOfNotNull(e.artist, e.title).joinToString(" - ")
            appendLine("#EXTINF:${e.durationSeconds ?: -1},$display")
            appendLine(e.location)
        }
    }
}
