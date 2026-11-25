package app.musicflow.data.providers.musicbrainz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* Subset of the MusicBrainz JSON web service (ws/2) that MusicFlow reads. */

@Serializable data class MbArtistRef(val id: String = "", val name: String = "")
@Serializable data class MbCredit(val name: String = "", @SerialName("joinphrase") val joinPhrase: String = "", val artist: MbArtistRef = MbArtistRef())
@Serializable data class MbReleaseGroupRef(
    val id: String = "",
    val title: String = "",
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbCredit> = emptyList(),
    val releases: List<MbReleaseRef> = emptyList(),
    val score: Int? = null,
)
@Serializable data class MbReleaseRef(
    val id: String = "",
    val title: String = "",
    val status: String? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("release-group") val releaseGroup: MbReleaseGroupRef? = null,
)
@Serializable data class MbRecording(
    val id: String = "",
    val title: String = "",
    val length: Long? = null,
    val score: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbCredit> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    val releases: List<MbReleaseRef> = emptyList(),
    val video: Boolean = false,
)
@Serializable data class MbTag(val name: String = "", val count: Int = 0)
@Serializable data class MbArea(val name: String = "")
@Serializable data class MbLifeSpan(val begin: String? = null, val end: String? = null, val ended: Boolean? = null)
@Serializable data class MbArtist(
    val id: String = "",
    val name: String = "",
    val type: String? = null,
    val country: String? = null,
    val area: MbArea? = null,
    @SerialName("begin-area") val beginArea: MbArea? = null,
    @SerialName("life-span") val lifeSpan: MbLifeSpan? = null,
    val disambiguation: String? = null,
    val genres: List<MbTag> = emptyList(),
    val tags: List<MbTag> = emptyList(),
    val score: Int? = null,
)
@Serializable data class MbTrack(
    val id: String = "",
    val title: String = "",
    val length: Long? = null,
    val position: Int = 0,
    val recording: MbRecording = MbRecording(),
    @SerialName("artist-credit") val artistCredit: List<MbCredit> = emptyList(),
)
@Serializable data class MbMedium(val position: Int = 0, val tracks: List<MbTrack> = emptyList())
@Serializable data class MbRelease(
    val id: String = "",
    val title: String = "",
    val date: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbCredit> = emptyList(),
    val media: List<MbMedium> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroupRef? = null,
)

@Serializable data class MbRecordingSearch(val count: Int = 0, val offset: Int = 0, val recordings: List<MbRecording> = emptyList())
@Serializable data class MbReleaseGroupSearch(
    val count: Int = 0, val offset: Int = 0,
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroupRef> = emptyList(),
    @SerialName("release-group-count") val browseCount: Int? = null,
)
@Serializable data class MbArtistSearch(val count: Int = 0, val offset: Int = 0, val artists: List<MbArtist> = emptyList())

/** Facts about an artist from MusicBrainz, shown on artist pages ("About"). Only reported values. */
data class ArtistFacts(
    val mbid: String,
    val name: String,
    val type: String?,
    val area: String?,
    val begin: String?,
    val end: String?,
    val ended: Boolean,
    val disambiguation: String?,
    val genres: List<String>,
) {
    val url get() = "https://musicbrainz.org/artist/$mbid"
}
