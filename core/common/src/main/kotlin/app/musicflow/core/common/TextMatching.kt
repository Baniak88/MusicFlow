package app.musicflow.core.common

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Normalisation used for duplicate detection and cross-provider matching.
 * Removes diacritics, case, punctuation and decorations such as "(Official Video)".
 */
object TextMatching {
    private val decorations = Regex(
        """[(\[](?:official|lyrics?|lyric video|audio|video|visualizer|hd|hq|4k|remaster(?:ed)?(?: \d{4})?|explicit|clean)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val featuring = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonAlnum = Regex("""[^\p{L}\p{N}]+""")
    private val diacritics = Regex("""\p{Mn}+""")

    fun normalize(input: String): String {
        val noDecor = decorations.replace(input, " ")
        val noFeat = featuring.replace(noDecor, "")
        val ascii = diacritics.replace(Normalizer.normalize(noFeat, Normalizer.Form.NFD), "")
        return nonAlnum.replace(ascii.lowercase(), " ").trim()
    }

    /** Key that identifies "the same song by the same artist". */
    fun trackKey(title: String, artist: String?): String =
        normalize(title) + "|" + normalize(artist.orEmpty().split(',', '&').first())

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Similarity in [0, 1] after normalisation. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val longest = max(x.length, y.length)
        return 1.0 - levenshtein(x, y).toDouble() / longest
    }

    /**
     * Whether two tracks can be considered the same recording. Used before falling back to an
     * alternative provider: we only substitute when title, artist and duration all agree.
     */
    fun isReliableMatch(
        titleA: String, artistA: String?, durationA: Long?,
        titleB: String, artistB: String?, durationB: Long?,
        durationToleranceMs: Long = 5_000,
    ): Boolean {
        if (similarity(titleA, titleB) < 0.85) return false
        if (artistA != null && artistB != null && similarity(artistA, artistB) < 0.7) {
            val a = normalize(artistA); val b = normalize(artistB)
            if (!a.contains(b) && !b.contains(a)) return false
        }
        if (durationA != null && durationB != null && kotlin.math.abs(durationA - durationB) > durationToleranceMs) return false
        return true
    }
}
