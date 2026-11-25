package app.musicflow.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.background
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

/**
 * Bottom-bar behaviour (like Spotify / YouTube Music):
 * - each tab keeps its own back stack; switching tabs restores it,
 * - tapping the tab you are already in returns to that tab's root,
 * - the tab stays highlighted on its sub-screens (artist, album, settings…),
 * - system Back walks back through the current tab, then to Home, then exits.
 */
internal class TabNavigation(private val nav: NavHostController, initial: String) {
    var selected by mutableStateOf(initial)
        private set

    fun onDestinationChanged(route: String?) {
        // Landing on a tab root (e.g. via Back) selects that tab.
        tabs.firstOrNull { it.route == route }?.let { selected = it.route }
    }

    fun select(tab: String) {
        val target = tabs.first { it.route == tab }
        if (selected == tab) {
            // Re-selecting the current tab: back to its root.
            if (!nav.popBackStack(target.route, inclusive = false)) nav.navigate(target.target) { launchSingleTop = true }
            return
        }
        selected = tab
        nav.navigate(target.target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private class ShellNavigator(
    private val nav: NavHostController,
    private val tabsNav: TabNavigation,
    private val showPlayer: (Boolean) -> Unit,
) : AppNavigator {
    override fun openCollection(id: MediaId) = go("collection/${Routes.enc(id.value)}")
    override fun openArtist(id: MediaId) = go("artist/${Routes.enc(id.value)}")
    override fun openUserPlaylist(id: Long) = go("playlist/$id")
    override fun openCategory(name: String) = go("category/$name")
    override fun openLibrarySection(section: LibrarySection) = go("section/${section.name}")
    override fun openSearch(query: String?) {
        if (query.isNullOrBlank()) tabsNav.select(Routes.SEARCH) // the search icon switches to the Search tab
        else nav.navigate("search?q=${Routes.enc(query)}") { launchSingleTop = true }
    }
    override fun openSettings() = go(Routes.SETTINGS)
    override fun openDiagnostics() = go(Routes.DIAGNOSTICS)
    override fun openLicenses() = go(Routes.LICENSES)
    override fun openEqualizer() = go(Routes.EQUALIZER)
    override fun openPlayer() = showPlayer(true)
    override fun back() {
        // A double tap on "back" must not pop two screens (or leave the tab).
        if (isIdle()) nav.popBackStack()
    }

    private fun isIdle() = nav.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED

    /** Opens a screen once: taps during a running transition (double taps) are ignored. */
    private fun go(route: String) {
        if (!isIdle()) return
        if (nav.currentBackStackEntry?.let { routeOf(it) } == route) return // already there
        nav.navigate(route)
    }

    private fun routeOf(entry: androidx.navigation.NavBackStackEntry): String {
        var r = entry.destination.route.orEmpty()
        entry.arguments?.let { args -> args.keySet().forEach { k -> r = r.replace("{$k}", args.get(k)?.toString().orEmpty()) } }
        return r
    }
}

@Composable
fun MusicFlowShell(events: Flow<ShellEvent>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var savedTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    val tabsNav = remember(nav) { TabNavigation(nav, savedTab) }
    LaunchedEffect(tabsNav.selected) { savedTab = tabsNav.selected }
    val navigator = remember(nav) { ShellNavigator(nav, tabsNav) { playerOpen = it } }
    val playerVm: PlayerViewModel = hiltViewModel()
    val playback by playerVm.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    LaunchedEffect(backStack?.destination?.route) { tabsNav.onDestinationChanged(backStack?.destination?.route) }
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
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = tabsNav.selected == tab.route,
                                        onClick = { tabsNav.select(tab.route) },
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
                    NavHost(
                        nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize(),
                        // Short, predictable transitions: tab switches are instant, sub-screens
                        // slide in quickly (the library default is a slow 700 ms cross-fade).
                        enterTransition = { if (isTabSwitch()) EnterTransition.None else slideInHorizontally(tween(220)) { it / 8 } + fadeIn(tween(160)) },
                        exitTransition = { if (isTabSwitch()) ExitTransition.None else fadeOut(tween(120)) },
                        popEnterTransition = { if (isTabSwitch()) EnterTransition.None else fadeIn(tween(160)) },
                        popExitTransition = { if (isTabSwitch()) ExitTransition.None else slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(140)) },
                    ) {
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
                // Keeps status-bar icons readable over scrolling content (edge-to-edge).
                Box(
                    Modifier.fillMaxWidth()
                        .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                        .testTag("status_bar_scrim"),
                )
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

private val tabRoutes = setOf(Routes.HOME, Routes.EXPLORE, Routes.SEARCH, Routes.LIBRARY)

/** Both ends of the transition are tab roots → it is a tab switch. */
private fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes
