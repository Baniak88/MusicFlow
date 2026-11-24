package app.musicflow.data.providers.lyrics

import app.musicflow.core.model.Lyrics
import app.musicflow.core.model.LyricsLine
import app.musicflow.core.model.Track
import app.musicflow.core.common.defaultClassify
import app.musicflow.data.providers.api.LyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lyrics from LRCLIB (https://lrclib.net), a free, open, keyless lyrics database.
 * Only title, artist, album and duration of the current track are sent.
 */
class LrcLibLyricsProvider(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lrclib.net/".toHttpUrl(),
) : LyricsProvider {
    override val name = "LRCLIB"

    @Serializable
    private data class Dto(
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val artist = track.primaryArtist ?: return@withContext null
        val url = baseUrl.newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", artist)
            .apply {
                track.album?.title?.let { addQueryParameter("album_name", it) }
                track.durationMs?.let { addQueryParameter("duration", (it / 1000).toString()) }
            }.build()
        val request = Request.Builder().url(url).header("User-Agent", "MusicFlow (https://github.com/baniak88/musicflow)").build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                if (!resp.isSuccessful) throw java.io.IOException("LRCLIB HTTP ${resp.code}")
                val dto = json.decodeFromString(Dto.serializer(), resp.body?.string().orEmpty())
                if (dto.instrumental) return@withContext Lyrics(plain = null, synced = null, source = name)
                Lyrics(plain = dto.plainLyrics, synced = dto.syncedLyrics?.let(LrcParser::parse)?.ifEmpty { null }, source = name)
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            throw app.musicflow.core.model.ProviderError.Parsing("Invalid lyrics response", e)
        } catch (e: java.io.IOException) {
            throw defaultClassify(e)
        }
    }
}

object LrcParser {
    private val tag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricsLine> = lrc.lineSequence().flatMap { line ->
        val stamps = tag.findAll(line).toList()
        if (stamps.isEmpty()) return@flatMap emptySequence()
        val text = line.substring(stamps.last().range.last + 1).trim()
        stamps.asSequence().map { m ->
            val (mm, ss, frac) = m.destructured
            val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
            LyricsLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, text)
        }
    }.sortedBy { it.timeMs }.toList()
}
