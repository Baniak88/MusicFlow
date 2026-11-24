package app.musicflow.core.designsystem.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track

/**
 * Navigation entry points shared by all features. Implemented once in the app module, so
 * feature modules never depend on each other.
 */
interface AppNavigator {
    fun openCollection(id: MediaId)
    fun openArtist(id: MediaId)
    fun openUserPlaylist(id: Long)
    fun openCategory(name: String)
    fun openLibrarySection(section: LibrarySection)
    fun openSearch(query: String? = null)
    fun openSettings()
    fun openDiagnostics()
    fun openLicenses()
    fun openEqualizer()
    fun openPlayer()
    fun back()
}

enum class LibrarySection { FAVORITES, RECENT, MOST_PLAYED, HISTORY, LOCAL }

/** Opens the contextual track menu (play next, add to playlist, go to artist…). */
fun interface TrackMenu {
    fun open(track: Track)
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator not provided") }
val LocalTrackMenu = staticCompositionLocalOf { TrackMenu { } }

/** App-wide snackbar host, so any screen can show transient feedback. */
val LocalSnackbarHost = staticCompositionLocalOf { androidx.compose.material3.SnackbarHostState() }
