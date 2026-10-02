package com.kuyamcliff.compressor.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kuyamcliff.compressor.CompressorApp
import com.kuyamcliff.compressor.R
import com.kuyamcliff.compressor.ui.compare.CompareScreen
import com.kuyamcliff.compressor.ui.configure.ConfigureScreen
import com.kuyamcliff.compressor.ui.configure.ConfigureViewModel
import com.kuyamcliff.compressor.ui.history.HistoryScreen
import com.kuyamcliff.compressor.ui.history.HistoryViewModel
import com.kuyamcliff.compressor.ui.home.HomeScreen
import com.kuyamcliff.compressor.ui.home.HomeViewModel
import com.kuyamcliff.compressor.ui.info.AboutScreen
import com.kuyamcliff.compressor.ui.info.DiagnosticsScreen
import com.kuyamcliff.compressor.ui.info.DiagnosticsViewModel
import com.kuyamcliff.compressor.ui.info.HelpScreen
import com.kuyamcliff.compressor.ui.info.LicensesScreen
import com.kuyamcliff.compressor.ui.info.PrivacyScreen
import com.kuyamcliff.compressor.ui.presets.PresetsScreen
import com.kuyamcliff.compressor.ui.presets.PresetsViewModel
import com.kuyamcliff.compressor.ui.queue.QueueScreen
import com.kuyamcliff.compressor.ui.queue.QueueViewModel
import com.kuyamcliff.compressor.ui.report.ReportScreen
import com.kuyamcliff.compressor.ui.report.ReportViewModel
import com.kuyamcliff.compressor.ui.settings.SettingsScreen
import com.kuyamcliff.compressor.ui.settings.SettingsViewModel

object Routes {
    const val HOME = "home"
    const val QUEUE = "queue"
    const val PRESETS = "presets"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CONFIGURE = "configure"
    const val COMPARE = "compare"
    const val PICK_PRESET = "pick_preset"
    const val REPORT = "report/{id}"
    fun report(id: Long) = "report/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    Tab(Routes.QUEUE, R.string.queue, Icons.Outlined.Queue),
    Tab(Routes.PRESETS, R.string.presets, Icons.Outlined.Bookmarks),
    Tab(Routes.HISTORY, R.string.history, Icons.Outlined.History),
    Tab(Routes.SETTINGS, R.string.settings, Icons.Outlined.Settings),
)

@Composable
fun AppNavHost(onRequestNotificationPermission: () -> Unit, nav: NavHostController = rememberNavController()) {
    val activity = LocalContext.current as ComponentActivity
    val container = (activity.application as CompressorApp).container
    // One configure session per activity: shared by configure, compare and preset picker.
    val configureVm: ConfigureViewModel = viewModel(viewModelStoreOwner = activity)
    val request by container.session.configure.collectAsState()
    val openJob by container.session.openJob.collectAsState()
    val openConfigure by container.session.openConfigure.collectAsState()
    val queueSnapshot by container.queue.snapshot.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(request) { request?.let { configureVm.load(it) } }
    LaunchedEffect(openJob) {
        openJob?.let { id ->
            container.session.consumeOpenJob()
            if (id > 0) nav.navigate(Routes.report(id)) else nav.navigate(Routes.QUEUE) { launchSingleTop = true }
        }
    }

    LaunchedEffect(openConfigure) {
        if (openConfigure != 0L) {
            container.session.consumeOpenConfigure()
            nav.navigate(Routes.CONFIGURE) { launchSingleTop = true }
        }
    }

    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(bottomBar = {
        if (tabs.any { it.route == route }) {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { go(t.route) },
                        icon = {
                            if (t.route == Routes.QUEUE && !queueSnapshot.idle) {
                                BadgedBox(badge = { Badge { Text((queueSnapshot.running + queueSnapshot.waiting).toString()) } }) { Icon(t.icon, null) }
                            } else Icon(t.icon, null)
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable(Routes.HOME) {
                HomeScreen(viewModel<HomeViewModel>(), onConfigure = { nav.navigate(Routes.CONFIGURE) }, onOpenQueue = { go(Routes.QUEUE) })
            }
            composable(Routes.QUEUE) { QueueScreen(viewModel<QueueViewModel>(), onOpenJob = { nav.navigate(Routes.report(it)) }) }
            composable(Routes.PRESETS) { PresetsScreen(viewModel<PresetsViewModel>(), onBack = null, onPick = null) }
            composable(Routes.HISTORY) {
                HistoryScreen(viewModel<HistoryViewModel>(), onOpenJob = { nav.navigate(Routes.report(it)) }, onReuse = { cfg, uris ->
                    uris.forEach { container.sourceAccess.persistPermission(it) }
                    container.session.startConfigure(ConfigureRequest(uris, initialConfig = cfg))
                    nav.navigate(Routes.CONFIGURE)
                })
            }
            composable(Routes.SETTINGS) { SettingsScreen(viewModel<SettingsViewModel>(), onOpen = { nav.navigate(it) }) }
            composable(Routes.CONFIGURE) {
                ConfigureScreen(
                    configureVm,
                    onBack = { nav.popBackStack() },
                    onStarted = { nav.popBackStack(); go(Routes.QUEUE) },
                    onOpenCompare = { nav.navigate(Routes.COMPARE) },
                    onPickPreset = { nav.navigate(Routes.PICK_PRESET) },
                    onRequestNotificationPermission = onRequestNotificationPermission,
                )
            }
            composable(Routes.COMPARE) { CompareScreen(configureVm, onBack = { nav.popBackStack() }) }
            composable(Routes.PICK_PRESET) {
                PresetsScreen(viewModel<PresetsViewModel>(), onBack = { nav.popBackStack() }, onPick = { p -> configureVm.applyPreset(p); nav.popBackStack() })
            }
            composable(Routes.REPORT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ReportScreen(viewModel<ReportViewModel>(), e.arguments?.getLong("id") ?: -1, onBack = { nav.popBackStack() })
            }
            composable("diagnostics") { DiagnosticsScreen(viewModel<DiagnosticsViewModel>(), onBack = { nav.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) }) }
            composable("licenses") { LicensesScreen(onBack = { nav.popBackStack() }) }
            composable("privacy") { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
