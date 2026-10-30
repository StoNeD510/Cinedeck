package com.cinedeck.app

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cinedeck.app.data.AddonEntry
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Store
import com.cinedeck.app.ui.BrowseScreen
import com.cinedeck.app.ui.C
import com.cinedeck.app.ui.CineTheme
import com.cinedeck.app.ui.DetailsScreen
import com.cinedeck.app.ui.HomeScreen
import com.cinedeck.app.ui.LibraryScreen
import com.cinedeck.app.ui.LocalIsTv
import com.cinedeck.app.ui.LocalWide
import com.cinedeck.app.ui.RdFilesScreen
import com.cinedeck.app.ui.SearchScreen
import com.cinedeck.app.ui.SettingsScreen
import com.cinedeck.app.ui.StreamsScreen
import com.cinedeck.app.ui.UpcomingScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = isTv(this)
        handleAddonLink(intent)
        setContent {
            CineTheme {
                BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
                    val wide = tv || maxWidth >= 720.dp
                    CompositionLocalProvider(LocalIsTv provides tv, LocalWide provides wide) {
                        AppRoot(wide)
                        CrashReport()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAddonLink(intent)
    }

    /** stremio://…/manifest.json links install an addon, like in Stremio. */
    private fun handleAddonLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "stremio") return
        val url = Addons.normalize(uri.toString())
        (application as CineDeckApp).scope.launch {
            runCatching { Addons.manifest(url) }
                .onSuccess { m ->
                    Store.setAddons(Store.data.value.addons.filterNot { Addons.normalize(it.url) == url } + AddonEntry(url, m))
                    Toast.makeText(this@MainActivity, "Installed ${m.name}", Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(this@MainActivity, "Couldn't install addon: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        fun isTv(ctx: Context): Boolean {
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse"
    const val UPCOMING = "upcoming"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val RD = "rd"
    fun details(type: String, id: String) = "details/$type/${Uri.encode(id)}"
    fun streams(type: String, metaId: String, videoId: String) = "streams/$type/${Uri.encode(metaId)}/${Uri.encode(videoId)}"
    fun browse(catalogKey: String? = null, type: String? = null) =
        "browse?key=${Uri.encode(catalogKey ?: "")}&type=${type ?: ""}"
}

@Composable
private fun CrashReport() {
    var report by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashLog.read()) }
    val r = report ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { CrashLog.clear(); report = null },
        containerColor = C.Surface,
        title = { Text("CineDeck closed unexpectedly last time", color = C.Text) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                item {
                    Text("Take a photo of this screen and send it over so the bug can be fixed.", color = C.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(r, color = C.Text, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { CrashLog.clear(); report = null }) { Text("Dismiss", color = C.Accent) }
        },
    )
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.BROWSE, "Browse", Icons.Default.GridView),
    Tab(Routes.UPCOMING, "Upcoming", Icons.Default.CalendarMonth),
    Tab(Routes.SEARCH, "Search", Icons.Default.Search),
    Tab(Routes.LIBRARY, "My Stuff", Icons.Default.VideoLibrary),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
private fun AppRoot(wide: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore("?") ?: Routes.HOME
    val isTopLevel = tabs.any { it.route == route }

    fun go(r: String) = nav.navigate(r) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    if (wide) {
        Row(Modifier.fillMaxSize().systemBarsPadding()) {
            if (isTopLevel) NavigationRail(containerColor = C.Bg, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                Text("CineDeck", color = C.Accent, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                tabs.forEach { t ->
                    NavigationRailItem(
                        selected = route == t.route, onClick = { go(t.route) },
                        icon = { Icon(t.icon, t.label, modifier = Modifier.size(24.dp)) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = C.Accent, selectedTextColor = C.Accent,
                            indicatorColor = C.Surface2, unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { Host(nav) }
        }
    } else {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) { Host(nav) }
            if (isTopLevel) NavigationBar(containerColor = C.Surface, tonalElevation = 0.dp) {
                tabs.filter { it.route != Routes.SEARCH }.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route, onClick = { go(t.route) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Accent, selectedTextColor = C.Accent,
                            indicatorColor = C.Surface2, unselectedIconColor = C.Muted, unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Host(nav: NavHostController) {
    val open: (String, String) -> Unit = { type, id -> nav.navigate(Routes.details(type, id)) }
    val play: (String, String, String) -> Unit = { type, metaId, videoId -> nav.navigate(Routes.streams(type, metaId, videoId)) }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpen = open, onPlay = play,
                onSeeAll = { key, type -> nav.navigate(Routes.browse(key, type)) },
                onSearch = { nav.navigate(Routes.SEARCH) },
            )
        }
        composable(
            "${Routes.BROWSE}?key={key}&type={type}",
            arguments = listOf(
                navArgument("key") { type = NavType.StringType; defaultValue = "" },
                navArgument("type") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            BrowseScreen(
                initialKey = e.arguments?.getString("key").orEmpty(),
                initialType = e.arguments?.getString("type").orEmpty(),
                onOpen = open,
            )
        }
        composable(Routes.UPCOMING) { UpcomingScreen(onOpen = open, onPlay = play) }
        composable(Routes.SEARCH) { SearchScreen(onOpen = open) }
        composable(Routes.LIBRARY) { LibraryScreen(onOpen = open, onPlay = play) }
        composable(Routes.SETTINGS) { SettingsScreen(onRdFiles = { nav.navigate(Routes.RD) }) }
        composable(Routes.RD) { RdFilesScreen(onBack = { nav.popBackStack() }) }
        composable("details/{type}/{id}") { e ->
            DetailsScreen(
                type = e.arguments?.getString("type").orEmpty(),
                id = Uri.decode(e.arguments?.getString("id").orEmpty()),
                onPlay = play,
                onBack = { nav.popBackStack() },
            )
        }
        composable("streams/{type}/{metaId}/{videoId}") { e ->
            StreamsScreen(
                type = e.arguments?.getString("type").orEmpty(),
                metaId = Uri.decode(e.arguments?.getString("metaId").orEmpty()),
                videoId = Uri.decode(e.arguments?.getString("videoId").orEmpty()),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
