package com.hush.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hush.app.ui.apps.AppPickerScreen
import com.hush.app.ui.components.UiSignals
import com.hush.app.ui.focus.FocusScreen
import com.hush.app.ui.history.DigestLink
import com.hush.app.ui.history.HistoryScreen
import com.hush.app.ui.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILTER = "extra_filter"
        const val FILTER_HELD = "held"
        const val EXTRA_DELIVERED_AT = "extra_delivered_at"
    }

    @Inject lateinit var signals: UiSignals

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from the digest notification. On a restore the saved back stack already holds the tab.
        if (savedInstanceState == null) applyDigestIntent(intent)
        setContent {
            HushTheme {
                val digestLink by signals.digestLink.collectAsStateWithLifecycle()
                HushRoot(
                    digestLink = digestLink,
                    navigationRequests = signals.navigate,
                    onHistoryLeft = { signals.digestLink.value = null },
                )
            }
        }
    }

    /** Warm start: the activity is `singleTop`, so tapping the digest while Hush is open lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDigestIntent(intent)
    }

    private fun applyDigestIntent(intent: Intent?) {
        DigestLink.parse(intent)?.let { signals.digestLink.value = it }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HISTORY, "History", Icons.Filled.History),
    Tab(Routes.FOCUS, "Focus", Icons.Filled.NotificationsPaused),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun HushRoot(
    digestLink: DigestLink? = null,
    navigationRequests: Flow<String> = emptyFlow(),
    onHistoryLeft: () -> Unit = {},
) {
    // rememberNavController saves the back stack (and so the selected tab) across process death.
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Digest deep link (cold or warm): jump to History. HistoryViewModel reads the link itself via UiSignals.
    LaunchedEffect(digestLink) {
        if (digestLink != null) {
            navController.navigate(Routes.HISTORY) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // Screens ask for pushes (Focus → hold-list picker) without the NavHost call sites growing parameters.
    LaunchedEffect(navigationRequests) {
        navigationRequests.collect { route -> navController.navigate(route) { launchSingleTop = true } }
    }
    // Leaving History drops the digest filter; a rotation is not "leaving" (the restored stack is still on History).
    val onHistory = currentDestination?.hierarchy?.any { it.route == Routes.HISTORY } == true
    var wasOnHistory by remember { mutableStateOf(false) }
    LaunchedEffect(onHistory) {
        if (!onHistory && wasOnHistory) onHistoryLeft()
        wasOnHistory = onHistory
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.FOCUS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.FOCUS) { FocusScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenPicker = { navController.navigate(it) }) }
            composable(
                Routes.APP_PICKER_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_LIST) { type = NavType.StringType }),
            ) { entry ->
                AppPickerScreen(
                    list = entry.arguments?.getString(Routes.ARG_LIST).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
