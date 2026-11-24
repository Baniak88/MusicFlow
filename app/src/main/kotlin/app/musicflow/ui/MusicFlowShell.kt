package app.musicflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.musicflow.R
import app.musicflow.ShellEvent
import app.musicflow.core.designsystem.navigation.AppNavigator
import app.musicflow.core.designsystem.navigation.LibrarySection
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalSnackbarHost
import app.musicflow.core.model.MediaId
import app.musicflow.feature.explore.CategoryScreen
import app.musicflow.feature.explore.ExploreScreen
import app.musicflow.feature.home.HomeScreen
import app.musicflow.feature.library.ArtistScreen
import app.musicflow.feature.library.CollectionScreen
import app.musicflow.feature.library.LibraryScreen
import app.musicflow.feature.library.LibrarySectionScreen
import app.musicflow.feature.library.TrackMenuHost
import app.musicflow.feature.library.UserPlaylistScreen
import app.musicflow.feature.player.EqualizerScreen
import app.musicflow.feature.player.FullPlayer
import app.musicflow.feature.player.MiniPlayer
import app.musicflow.feature.player.PlayerViewModel
import app.musicflow.feature.search.SearchScreen
import app.musicflow.feature.settings.DiagnosticsScreen
import app.musicflow.feature.settings.LicensesScreen
import app.musicflow.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val SEARCH = "search?q={q}"
    const val LIBRARY = "library"
    const val CATEGORY = "category/{name}"
    const val COLLECTION = "collection/{id}"
    const val ARTIST = "artist/{id}"
    const val USER_PLAYLIST = "playlist/{id}"
    const val SECTION = "section/{section}"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val LICENSES = "licenses"
    const val EQUALIZER = "equalizer"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private data class Tab(val route: String, val target: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    Tab(Routes.EXPLORE, Routes.EXPLORE, R.string.nav_explore, Icons.Rounded.Explore),
    Tab(Routes.SEARCH, "search?q=", R.string.nav_search, Icons.Rounded.Search),
    Tab(Routes.LIBRARY, Routes.LIBRARY, R.string.nav_library, Icons.Rounded.LibraryMusic),
)

private class ShellNavigator(private val nav: NavHostController, private val showPlayer: (Boolean) -> Unit) : AppNavigator {
    override fun openCollection(id: MediaId) = nav.navigate("collection/${Routes.enc(id.value)}")
    override fun openArtist(id: MediaId) = nav.navigate("artist/${Routes.enc(id.value)}")
    override fun openUserPlaylist(id: Long) = nav.navigate("playlist/$id")
    override fun openCategory(name: String) = nav.navigate("category/$name")
    override fun openLibrarySection(section: LibrarySection) = nav.navigate("section/${section.name}")
    override fun openSearch(query: String?) = nav.navigate("search?q=${Routes.enc(query.orEmpty())}") { launchSingleTop = true }
    override fun openSettings() = nav.navigate(Routes.SETTINGS)
    override fun openDiagnostics() = nav.navigate(Routes.DIAGNOSTICS)
    override fun openLicenses() = nav.navigate(Routes.LICENSES)
    override fun openEqualizer() = nav.navigate(Routes.EQUALIZER)
    override fun openPlayer() = showPlayer(true)
    override fun back() { nav.popBackStack() }
}

@Composable
fun MusicFlowShell(events: Flow<ShellEvent>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val navigator = remember(nav) { ShellNavigator(nav) { playerOpen = it } }
    val playerVm: PlayerViewModel = hiltViewModel()
    val playback by playerVm.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current

    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is ShellEvent.OpenCollection -> navigator.openCollection(e.id)
                ShellEvent.OpenPlayer -> playerOpen = true
                ShellEvent.Unsupported -> snackbar.showSnackbar(resources.getString(R.string.link_not_supported))
            }
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalSnackbarHost provides snackbar) {
        TrackMenuHost {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        Column {
                            if (playback.current != null) MiniPlayer(playerVm, onExpand = { playerOpen = true })
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                                val currentRoute = backStack?.destination?.route
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = currentRoute == tab.route,
                                        onClick = {
                                            nav.navigate(tab.target) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, null) },
                                        label = { Text(stringResource(tab.label)) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                                        modifier = Modifier.testTag("tab_${tab.route.substringBefore('?')}"),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val content = PaddingValues(bottom = padding.calculateBottomPadding())
                    NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                        composable(Routes.HOME) { HomeScreen(content) }
                        composable(Routes.EXPLORE) { ExploreScreen(content) }
                        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { entry ->
                            SearchScreen(content, initialQuery = entry.arguments?.getString("q")?.let { URLDecoder.decode(it, "UTF-8") }?.ifBlank { null })
                        }
                        composable(Routes.LIBRARY) { LibraryScreen(content) }
                        composable(Routes.CATEGORY) { CategoryScreen(content) }
                        composable(Routes.COLLECTION) { CollectionScreen(content) }
                        composable(Routes.ARTIST) { ArtistScreen(content) }
                        composable(Routes.USER_PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { UserPlaylistScreen(content) }
                        composable(Routes.SECTION) { LibrarySectionScreen(content) }
                        composable(Routes.SETTINGS) { SettingsScreen(content) }
                        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(content) }
                        composable(Routes.LICENSES) { LicensesScreen(content) }
                        composable(Routes.EQUALIZER) { EqualizerScreen(content) }
                    }
                }
                AnimatedVisibility(
                    visible = playerOpen && playback.current != null,
                    enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
                ) {
                    FullPlayer(playerVm, onCollapse = { playerOpen = false })
                }
            }
        }
    }
}
