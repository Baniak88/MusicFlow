package app.musicflow.core.database

import androidx.room.TypeConverter
import app.musicflow.core.model.ArtistRef
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ArtistRef.serializer())

    @TypeConverter fun fromArtists(list: List<ArtistRef>): String = json.encodeToString(serializer, list)
    @TypeConverter fun toArtists(raw: String): List<ArtistRef> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}
