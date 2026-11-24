package app.musicflow.data.repositories

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.RetryPolicy
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.ThemeMode
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.PlaylistProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A fake online provider with paginated playlists and search. */
class FakeProvider : MusicProvider, PlaylistProvider, SearchProvider {
    override val id = "yt"
    override val displayName = "Fake"
    override val isExperimental = false
    override val isOnlineCatalog = true
    override val ownedPrefixes = setOf("yt", "ytpl")
    override val playlists: PlaylistProvider get() = this
    override val search: SearchProvider get() = this
    var searchCalls = 0
    var failSearch: Throwable? = null

    fun track(i: Int) = Track(MediaId("yt:v$i"), "Song $i", listOf(ArtistRef(null, "Artist ${i % 3}")), durationMs = 180_000)

    override suspend fun playlist(id: MediaId, pageToken: String?): CollectionPage = when (pageToken) {
        null -> CollectionPage("Imported", "Owner", null, (1..3).map(::track), nextPageToken = "p2")
        "p2" -> CollectionPage("", null, null, (4..5).map(::track), nextPageToken = null)
        else -> error("bad token")
    }
    override fun parsePlaylistUrl(url: String) = if (url.contains("list=")) MediaId("ytpl:" + url.substringAfter("list=")) else null
    override fun parseTrackUrl(url: String) = if (url.contains("watch?v=")) MediaId("yt:" + url.substringAfter("v=")) else null

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        searchCalls++
        failSearch?.let { throw it }
        return SearchPage(query, filter, listOf(MediaItemSummary.TrackItem(track(1))), null)
    }
    override suspend fun suggestions(query: String) = listOf("$query remix", "${query.uppercase()} REMIX")
}

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: MusicFlowDatabase
    private lateinit var fake: FakeProvider
    private lateinit var registry: ProviderRegistry
    private lateinit var playlists: PlaylistRepository
    private lateinit var settings: SettingsRepository
    private lateinit var library: LibraryRepository
    private val dsScope = TestScope()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MusicFlowDatabase::class.java)
            .allowMainThreadQueries().build()
        fake = FakeProvider()
        registry = ProviderRegistry(setOf(fake, DirectUrlProvider()), DiagnosticsLog()).apply {
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 1, jitterRatio = 0.0); sleeper = {}
        }
        playlists = PlaylistRepository(db, registry)
        settings = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb") })
        library = LibraryRepository(db, settings)
    }

    @After fun tearDown() = db.close()

    @Test fun playlistCrudAndDuplicateDetection() = runTest {
        val id = playlists.create("  Road trip ")
        assertThat(playlists.playlists.first().single().name).isEqualTo("Road trip")
        val r1 = playlists.add(id, listOf(fake.track(1), fake.track(2)))
        assertThat(r1.added).isEqualTo(2)
        // Same id and same song under another id (normalised title + artist) are duplicates.
        val sameSong = Track(MediaId("yt:other"), "Song 1 (Official Audio)", listOf(ArtistRef(null, "Artist 1")))
        val r2 = playlists.add(id, listOf(fake.track(1), sameSong, fake.track(3)))
        assertThat(r2.added).isEqualTo(1)
        assertThat(r2.duplicates.map { it.id.value }).containsExactly("yt:v1", "yt:other")
        playlists.rename(id, "Summer")
        assertThat(playlists.playlists.first().single().name).isEqualTo("Summer")
        assertThat(playlists.playlists.first().single().trackCount).isEqualTo(3)

        playlists.move(id, from = 2, to = 0)
        assertThat(playlists.entries(id).first().map { it.track.id.value }).containsExactly("yt:v3", "yt:v1", "yt:v2").inOrder()
        val entry = playlists.entries(id).first()[1]
        playlists.remove(id, entry.entryId)
        assertThat(playlists.entries(id).first().map { it.position }).containsExactly(0, 1).inOrder()
        playlists.delete(id)
        assertThat(playlists.playlists.first()).isEmpty()
    }

    @Test fun importPublicPlaylistPreservesOrderAcrossPages() = runTest {
        val result = playlists.importPublic("https://music.youtube.com/playlist?list=PLxyz")
        assertThat(result.imported).isEqualTo(5)
        assertThat(playlists.entries(result.playlistId).first().map { it.track.id.value })
            .containsExactly("yt:v1", "yt:v2", "yt:v3", "yt:v4", "yt:v5").inOrder()
        assertThat(playlists.playlists.first().single().sourceUrl).isEqualTo("https://music.youtube.com/playlist?list=PLxyz")
        assertThat(runCatching { playlists.importPublic("https://example.com/foo") }.exceptionOrNull())
            .isInstanceOf(ProviderError.NotSupported::class.java)
    }

    @Test fun m3uImportExportRoundTrip() = runTest {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Mixed
            #EXTINF:180,Artist 0 - Song 3
            https://music.youtube.com/watch?v=v3
            #EXTINF:200,Someone - Stream
            https://example.com/audio/stream.mp3
            /storage/emulated/0/Music/unknown.mp3
        """.trimIndent()
        val r = playlists.importM3u(m3u, "fallback")
        assertThat(r.imported).isEqualTo(2)
        assertThat(r.skipped).isEqualTo(1)
        val entries = playlists.entries(r.playlistId).first().map { it.track }
        assertThat(entries.map { it.id.value }).containsExactly("yt:v3", "url:https://example.com/audio/stream.mp3").inOrder()
        assertThat(entries[1].title).isEqualTo("Stream")
        val exported = playlists.exportM3u(r.playlistId)
        assertThat(exported).contains("#PLAYLIST:Mixed")
        assertThat(exported.indexOf("watch?v=v3")).isLessThan(exported.indexOf("stream.mp3"))
    }

    @Test fun backupAndRestore() = runTest {
        val id = playlists.create("A")
        playlists.add(id, listOf(fake.track(1), fake.track(2)))
        val json = playlists.backup(favorites = listOf(fake.track(9)))
        playlists.delete(id)
        val restoredFavs = mutableListOf<Track>()
        val n = playlists.restore(json) { restoredFavs += it }
        assertThat(n).isEqualTo(1)
        val p = playlists.playlists.first().single()
        assertThat(p.name).isEqualTo("A")
        assertThat(playlists.entries(p.id).first().map { it.track.id.value }).containsExactly("yt:v1", "yt:v2").inOrder()
        assertThat(restoredFavs.single().id.value).isEqualTo("yt:v9")
        assertThat(runCatching { playlists.restore("{}") {} }.exceptionOrNull()).isInstanceOf(ProviderError.Parsing::class.java)
    }

    @Test fun searchHistorySuggestionsAndProviderFailure() = runTest {
        val search = SearchRepository(registry, db)
        search.recordQuery("daft punk")
        search.recordQuery("daft punk")
        assertThat(search.history.first()).containsExactly("daft punk")
        val s = search.suggestions("daft")
        assertThat(s.first()).isEqualTo("daft punk")
        assertThat(s).containsNoDuplicates()
        assertThat(s.map { it.lowercase() }).containsNoDuplicates()
        assertThat(search.search("x", SearchFilter.SONGS).items).hasSize(1)
        fake.failSearch = ProviderError.Unavailable("nope")
        val before = fake.searchCalls
        assertThat(runCatching { search.search("x", SearchFilter.SONGS) }.exceptionOrNull()).isInstanceOf(ProviderError.Unavailable::class.java)
        assertThat(fake.searchCalls - before).isEqualTo(1) // permanent: not retried
        fake.failSearch = java.io.IOException("flaky")
        assertThat(runCatching { search.search("x", SearchFilter.SONGS) }.exceptionOrNull()).isInstanceOf(ProviderError.Network::class.java)
    }

    @Test fun searchDedupeAcrossPages() {
        val a = MediaItemSummary.TrackItem(Track(MediaId("yt:1"), "Levels", listOf(ArtistRef(null, "Avicii"))))
        val sameId = MediaItemSummary.TrackItem(Track(MediaId("yt:1"), "Levels", listOf(ArtistRef(null, "Avicii"))))
        val sameSong = MediaItemSummary.TrackItem(Track(MediaId("yt:2"), "Levels (Official Video)", listOf(ArtistRef(null, "Avicii"))))
        val other = MediaItemSummary.TrackItem(Track(MediaId("yt:3"), "Wake Me Up", listOf(ArtistRef(null, "Avicii"))))
        assertThat(SearchRepository.dedupe(listOf(a), listOf(sameId, sameSong, other, other))).containsExactly(other)
    }

    @Test fun settingsPersistAndDefault() = runTest {
        assertThat(settings.current().themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(settings.current().qualityMobile).isEqualTo(AudioQuality.STANDARD)
        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setAmoled(true)
        settings.setQualityWifi(AudioQuality.BEST)
        settings.setCacheSizeMb(10_000)
        settings.setLanguage("pl")
        val s = settings.current()
        assertThat(s.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(s.amoledBlack).isTrue()
        assertThat(s.qualityWifi).isEqualTo(AudioQuality.BEST)
        assertThat(s.cacheSizeMb).isEqualTo(4096)
        assertThat(s.languageTag).isEqualTo("pl")
    }

    @Test fun recordPlayClassifiesSkipsAndCompletionsAndRespectsHistorySetting() = runTest {
        val t = fake.track(1)
        library.recordPlay(t, startedAt = 1, listenedMs = 175_000, userSkipped = false)
        library.recordPlay(t, startedAt = 2, listenedMs = 10_000, userSkipped = true)
        library.recordPlay(t, startedAt = 3, listenedMs = 2_000, userSkipped = false) // too short: ignored
        val stats = db.history().trackStats(0).single()
        assertThat(stats.plays).isEqualTo(2)
        assertThat(stats.completions).isEqualTo(1)
        assertThat(stats.skips).isEqualTo(1)
        settings.setHistoryEnabled(false)
        library.recordPlay(t, startedAt = 4, listenedMs = 175_000, userSkipped = false)
        assertThat(db.history().eventCount()).isEqualTo(2)
        assertThat(library.toggleFavorite(t)).isTrue()
        assertThat(library.favoriteTracks.first().single().id).isEqualTo(t.id)
        assertThat(library.toggleFavorite(t)).isFalse()
    }

    @Test fun queuePersistsAcrossRestore() = runTest {
        library.saveQueue(listOf(fake.track(3), fake.track(1), fake.track(2)))
        assertThat(library.restoreQueue().map { it.id.value }).containsExactly("yt:v3", "yt:v1", "yt:v2").inOrder()
    }
}
