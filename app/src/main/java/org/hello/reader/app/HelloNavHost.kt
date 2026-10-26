package org.hello.reader.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.hello.reader.core.engine.audio.NowPlaying
import org.hello.reader.core.util.DeviceProfile
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.hello.reader.core.security.SecurityVault
import org.hello.reader.feature.library.BookDetailScreen
import org.hello.reader.feature.library.CollectionDetailScreen
import org.hello.reader.feature.library.HomeScreen
import org.hello.reader.feature.library.LibraryScreen
import org.hello.reader.feature.library.LibraryViewModel
import org.hello.reader.feature.library.SeriesDetailScreen
import org.hello.reader.feature.notebook.NotebookScreen
import org.hello.reader.feature.player.MiniPlayerHost
import org.hello.reader.feature.player.MiniPlayerViewModel
import org.hello.reader.feature.onboarding.login.LoginScreen
import org.hello.reader.feature.onboarding.qr.QrScanScreen
import org.hello.reader.feature.onboarding.url.ServerUrlScreen
import org.hello.reader.feature.reader.ReaderScreen
import org.hello.reader.feature.settings.SettingsScreen
import org.hello.reader.feature.settings.ThemeEditorScreen
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

@Serializable
data object ServerUrlRoute

@Serializable
data class LoginRoute(
    val baseUrl: String,
    val username: String? = null,
    val sessionExpired: Boolean = false,
)

@Serializable
data object QrScanRoute

@Serializable
data object HomeRoute

@Serializable
data object LibraryRoute

@Serializable
data object NotebookRoute

@Serializable
data object SettingsRoute

@Serializable
data object ThemeEditorRoute

@Serializable
data class BookDetailRoute(
    val bookId: String,
)

@Serializable
data class SeriesDetailRoute(
    val seriesUuid: String,
)

@Serializable
data class CollectionDetailRoute(
    val collectionUuid: String,
)

@Serializable
data class ReaderRoute(
    val bookId: String,
    val openAudioPlayer: Boolean = false,
)

/** The four bottom-bar destinations. */
private enum class Tab(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(HomeRoute, HomeRoute::class, R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    LIBRARY(LibraryRoute, LibraryRoute::class, R.string.library, Icons.Outlined.CollectionsBookmark, Icons.Filled.CollectionsBookmark),
    NOTEBOOK(NotebookRoute, NotebookRoute::class, R.string.notebook, Icons.Outlined.BookmarkBorder, Icons.Filled.Bookmark),
    SETTINGS(SettingsRoute, SettingsRoute::class, R.string.settings, Icons.Outlined.Tune, Icons.Filled.Tune),
}

private fun NavDestination?.isTab(tab: Tab): Boolean = this?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true

@Composable
fun HelloNavHost(
    vault: SecurityVault,
    authExpired: SharedFlow<String>,
    navController: NavHostController = rememberNavController(),
    initialBookId: String? = null,
    onBookOpened: () -> Unit = {},
) {
    LaunchedEffect(initialBookId) {
        if (!initialBookId.isNullOrBlank()) {
            navController.navigate(ReaderRoute(initialBookId))
            onBookOpened()
        }
    }

    // A 401 on the active profile means its token was revoked or expired.
    LaunchedEffect(authExpired) {
        authExpired.collect { profileId ->
            val profile = vault.activeProfile.value?.takeIf { it.id == profileId } ?: return@collect
            navController.navigate(LoginRoute(profile.baseUrl, profile.username, sessionExpired = true)) {
                launchSingleTop = true
            }
        }
    }

    // One library state for Home, Library and the book page (scoped to the activity).
    val library: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = Tab.entries.any { destination.isTab(it) }
    val isInsideReader = destination?.hierarchy?.any { it.hasRoute(ReaderRoute::class) } == true
    val miniPlayer: MiniPlayerViewModel = viewModel(factory = MiniPlayerViewModel.Factory)
    val nowPlaying by miniPlayer.nowPlaying.collectAsStateWithLifecycle()
    val isBottomBarVisible = !isInsideReader && (showBar || nowPlaying != null)
    val isEInk = DeviceProfile.isCurrentDeviceEInk

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (isEInk) {
                if (isBottomBarVisible) {
                    BottomBarContent(
                        showBar = showBar,
                        nowPlaying = nowPlaying,
                        destination = destination,
                        miniPlayer = miniPlayer,
                        onOpenAudioPlayer = { bookId -> navController.navigate(ReaderRoute(bookId, openAudioPlayer = true)) },
                        onSwitchTab = { route -> navController.switchTab(route) },
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = isBottomBarVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    BottomBarContent(
                        showBar = showBar,
                        nowPlaying = nowPlaying,
                        destination = destination,
                        miniPlayer = miniPlayer,
                        onOpenAudioPlayer = { bookId -> navController.navigate(ReaderRoute(bookId, openAudioPlayer = true)) },
                        onSwitchTab = { route -> navController.switchTab(route) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenBook = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = false)) },
                    onOpenAudio = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = true)) },
                    onOpenDetails = { book -> navController.navigate(BookDetailRoute(book.id)) },
                    onSeeAll = { navController.switchTab(LibraryRoute) },
                    onAddServer = { navController.navigate(ServerUrlRoute) },
                    viewModel = library,
                    onOpenSeries = { seriesUuid -> navController.navigate(SeriesDetailRoute(seriesUuid)) },
                    onOpenCollection = { collectionUuid -> navController.navigate(CollectionDetailRoute(collectionUuid)) },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenDetails = { book -> navController.navigate(BookDetailRoute(book.id)) },
                    onOpenBook = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = false)) },
                    onOpenAudio = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = true)) },
                    onAddServer = { navController.navigate(ServerUrlRoute) },
                    viewModel = library,
                    onOpenSeries = { seriesUuid -> navController.navigate(SeriesDetailRoute(seriesUuid)) },
                    onOpenCollection = { collectionUuid -> navController.navigate(CollectionDetailRoute(collectionUuid)) },
                )
            }
            composable<NotebookRoute> {
                NotebookScreen(
                    onOpenBook = { bookId -> navController.navigate(ReaderRoute(bookId)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onAddServer = { navController.navigate(ServerUrlRoute) },
                    onScanQr = { navController.navigate(QrScanRoute) },
                    onOpenThemeEditor = { navController.navigate(ThemeEditorRoute) },
                )
            }
            composable<ThemeEditorRoute> {
                ThemeEditorScreen(onBack = { navController.popBackStack() })
            }
            composable<BookDetailRoute> { entry ->
                val route = entry.toRoute<BookDetailRoute>()
                BookDetailScreen(
                    bookId = route.bookId,
                    viewModel = library,
                    onBack = { navController.popBackStack() },
                    onOpen = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = false)) },
                    onOpenAudio = { book -> navController.navigate(ReaderRoute(book.id, openAudioPlayer = true)) },
                    onOpenSeries = { seriesUuid -> navController.navigate(SeriesDetailRoute(seriesUuid)) },
                    onOpenCollection = { collectionUuid -> navController.navigate(CollectionDetailRoute(collectionUuid)) },
                    onSearchCreator = { creator ->
                        library.setQuery(creator)
                        navController.navigate(LibraryRoute)
                    },
                )
            }
            composable<SeriesDetailRoute> { entry ->
                val route = entry.toRoute<SeriesDetailRoute>()
                SeriesDetailScreen(
                    seriesUuid = route.seriesUuid,
                    viewModel = library,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { book -> navController.navigate(ReaderRoute(book.id)) },
                    onOpenBookDetails = { book -> navController.navigate(BookDetailRoute(book.id)) },
                )
            }
            composable<CollectionDetailRoute> { entry ->
                val route = entry.toRoute<CollectionDetailRoute>()
                CollectionDetailScreen(
                    collectionUuid = route.collectionUuid,
                    viewModel = library,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { book -> navController.navigate(ReaderRoute(book.id)) },
                    onOpenBookDetails = { book -> navController.navigate(BookDetailRoute(book.id)) },
                    onOpenSeries = { seriesUuid -> navController.navigate(SeriesDetailRoute(seriesUuid)) },
                )
            }
            composable<ReaderRoute> { entry ->
                val route = entry.toRoute<ReaderRoute>()
                ReaderScreen(
                    bookId = route.bookId,
                    initialAudioOnlyView = route.openAudioPlayer,
                    onBack = { navController.popBackStack() },
                )
            }
            composable<ServerUrlRoute> {
                val canGoBack = navController.previousBackStackEntry != null
                ServerUrlScreen(
                    onContinue = { baseUrl -> navController.navigate(LoginRoute(baseUrl)) },
                    onScanQr = { navController.navigate(QrScanRoute) },
                    onBack = if (canGoBack) ({ navController.popBackStack() }) else null,
                    onSkipToLocal = { navController.resetTo(HomeRoute) },
                )
            }
            composable<LoginRoute> { entry ->
                val route = entry.toRoute<LoginRoute>()
                LoginScreen(
                    baseUrl = route.baseUrl,
                    initialUsername = route.username,
                    sessionExpired = route.sessionExpired,
                    onLoggedIn = { navController.resetTo(HomeRoute) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<QrScanRoute> {
                QrScanScreen(
                    onConnected = { navController.resetTo(HomeRoute) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Standard bottom-bar navigation: one copy of each tab, state saved and restored per tab. */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Navigates to [route] and clears the entire back stack beneath it. */
private fun NavHostController.resetTo(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun BottomBarContent(
    showBar: Boolean,
    nowPlaying: NowPlaying?,
    destination: NavDestination?,
    miniPlayer: MiniPlayerViewModel,
    onOpenAudioPlayer: (String) -> Unit,
    onSwitchTab: (Any) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (nowPlaying != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                MiniPlayerHost(
                    onOpen = onOpenAudioPlayer,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    viewModel = miniPlayer,
                )
            }
        }
        if (showBar) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = androidx.compose.ui.unit.Dp.Hairline,
            ) {
                Tab.entries.forEach { tab ->
                    val selected = destination.isTab(tab)
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSwitchTab(tab.route) },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        } else if (nowPlaying != null) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
