package app.musicflow.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.database.entity.FavoriteTrackEntity
import app.musicflow.core.database.entity.PlayEventEntity
import app.musicflow.core.database.entity.PlaylistEntity
import app.musicflow.core.database.entity.PlaylistEntryEntity
import app.musicflow.core.database.entity.SearchHistoryEntity
import app.musicflow.core.database.entity.TrackEntity
import app.musicflow.core.model.AlbumRef
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTest {
    private lateinit var db: MusicFlowDatabase

    private fun track(i: Int, artist: String = "Artist $i") = Track(
        MediaId("yt:id$i"), "Song $i", listOf(ArtistRef("ytch:$artist", artist)), AlbumRef("ytpl:a", "Album"), 200_000, "https://img/$i",
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MusicFlowDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun trackRoundTripPreservesArtists() = runTest {
        val t = track(1)
        db.tracks().upsert(TrackEntity.from(t, 1))
        assertThat(db.tracks().get("yt:id1")!!.toModel()).isEqualTo(t)
    }

    @Test fun favoritesToggleAndOrderNewestFirst() = runTest {
        (1..3).forEach { db.tracks().upsert(TrackEntity.from(track(it), 0)) }
        db.library().addFavorite(FavoriteTrackEntity("yt:id1", 10))
        db.library().addFavorite(FavoriteTrackEntity("yt:id3", 30))
        db.library().addFavorite(FavoriteTrackEntity("yt:id3", 99)) // ignored duplicate
        assertThat(db.library().favoriteTracks().first().map { it.id }).containsExactly("yt:id3", "yt:id1").inOrder()
        assertThat(db.library().isFavorite("yt:id1").first()).isTrue()
        db.library().removeFavorite("yt:id1")
        assertThat(db.library().isFavorite("yt:id1").first()).isFalse()
    }

    @Test fun playlistEntriesKeepOrderAndCascade() = runTest {
        (1..4).forEach { db.tracks().upsert(TrackEntity.from(track(it), 0)) }
        val id = db.playlists().insert(PlaylistEntity(name = "Mix", createdAt = 0, updatedAt = 0))
        db.playlists().insertEntries(listOf(4, 2, 3).mapIndexed { i, n -> PlaylistEntryEntity(playlistId = id, trackId = "yt:id$n", position = i, addedAt = 0) })
        assertThat(db.playlists().entries(id).first().map { it.id }).containsExactly("yt:id4", "yt:id2", "yt:id3").inOrder()
        val withCount = db.playlists().playlists().first().single()
        assertThat(withCount.trackCount).isEqualTo(3)
        assertThat(withCount.artworkUrl).isEqualTo("https://img/4")
        // Reorder: move last to first.
        val entries = db.playlists().rawEntries(id)
        db.playlists().rewriteOrder(id, listOf(entries[2].entryId, entries[0].entryId, entries[1].entryId), now = 5)
        assertThat(db.playlists().entries(id).first().map { it.id }).containsExactly("yt:id3", "yt:id4", "yt:id2").inOrder()
        assertThat(db.playlists().nextPosition(id)).isEqualTo(3)
        db.playlists().delete(id)
        assertThat(db.playlists().rawEntries(id)).isEmpty()
    }

    @Test fun historyAggregatesAndRecentlyPlayed() = runTest {
        (1..3).forEach { db.tracks().upsert(TrackEntity.from(track(it, if (it < 3) "Same" else "Other"), 0)) }
        val h = db.history()
        h.insert(PlayEventEntity(trackId = "yt:id1", startedAt = 100, listenedMs = 200_000, durationMs = 200_000, completed = true, skipped = false))
        h.insert(PlayEventEntity(trackId = "yt:id1", startedAt = 300, listenedMs = 200_000, durationMs = 200_000, completed = true, skipped = false))
        h.insert(PlayEventEntity(trackId = "yt:id2", startedAt = 200, listenedMs = 5_000, durationMs = 200_000, completed = false, skipped = true))
        h.insert(PlayEventEntity(trackId = "yt:id3", startedAt = 400, listenedMs = 60_000, durationMs = 200_000, completed = false, skipped = false))
        assertThat(h.recentlyPlayed(10).first().map { it.id }).containsExactly("yt:id3", "yt:id1", "yt:id2").inOrder()
        assertThat(h.mostPlayed(1).first().single().id).isEqualTo("yt:id1")
        assertThat(h.unfinished(0, 10).first().map { it.id }).containsExactly("yt:id3")
        val stats = h.trackStats(0).associateBy { it.trackId }
        assertThat(stats["yt:id1"]!!.plays).isEqualTo(2)
        assertThat(stats["yt:id2"]!!.skips).isEqualTo(1)
        val artists = h.artistStats(0)
        assertThat(artists.first().artistName).isEqualTo("Same")
        assertThat(artists.first().plays).isEqualTo(3)
        h.clearHistory()
        assertThat(h.eventCount()).isEqualTo(0)
    }

    @Test fun searchHistoryPrefixLookup() = runTest {
        val h = db.history()
        h.upsertSearch(SearchHistoryEntity("daft punk", 1, 3))
        h.upsertSearch(SearchHistoryEntity("dance", 2, 1))
        h.upsertSearch(SearchHistoryEntity("techno", 3, 1))
        assertThat(h.searchesStartingWith("da", 10).map { it.query }).containsExactly("daft punk", "dance").inOrder()
        assertThat(h.searches(10).first().first().query).isEqualTo("techno")
    }
}
