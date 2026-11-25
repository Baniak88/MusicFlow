package app.musicflow.data.providers.youtube

import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.MediaItemSummary

/**
 * Merges catalogue songs with uploads: interleaved (catalogue first), duplicates removed by
 * id and by normalised title + artist, and results that contain every word of the query moved
 * to the front so an exact upload is not buried below loosely related catalogue songs.
 */
object SongMerge {
    fun merge(query: String, songs: List<MediaItemSummary>, uploads: List<MediaItemSummary>): List<MediaItemSummary> {
        val interleaved = buildList {
            val max = maxOf(songs.size, uploads.size)
            for (i in 0 until max) { songs.getOrNull(i)?.let(::add); uploads.getOrNull(i)?.let(::add) }
        }
        val seenIds = HashSet<String>()
        val seenKeys = HashSet<String>()
        val unique = interleaved.filter { item ->
            val key = (item as? MediaItemSummary.TrackItem)?.track?.let { TextMatching.trackKey(it.title, it.primaryArtist) }
            val fresh = seenIds.add(item.id.value) && (key == null || seenKeys.add(key))
            fresh
        }
        val words = TextMatching.normalize(query).split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return unique
        val (exact, rest) = unique.partition { item ->
            val t = (item as? MediaItemSummary.TrackItem)?.track ?: return@partition false
            val hay = TextMatching.normalize("${t.artistLine} ${t.title}").replace(" ", "")
            words.all { hay.contains(it) }
        }
        return exact + rest
    }
}
