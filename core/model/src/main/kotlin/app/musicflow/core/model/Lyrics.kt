package app.musicflow.core.model

data class LyricsLine(val timeMs: Long, val text: String)

data class Lyrics(
    val plain: String?,
    val synced: List<LyricsLine>?,
    val source: String,
) {
    val isSynced: Boolean get() = !synced.isNullOrEmpty()
}
