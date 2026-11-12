package com.bendourthe.milestone.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bendourthe.milestone.domain.CatalogRepository
import com.bendourthe.milestone.domain.Program
import com.bendourthe.milestone.sync.webview.LoginBridge
import com.bendourthe.milestone.ui.auth.SignInHost
import com.bendourthe.milestone.ui.auth.SignInMenuItems
import com.bendourthe.milestone.ui.auth.SignInViewModel
import com.bendourthe.milestone.ui.catalog.CatalogViewModel
import com.bendourthe.milestone.ui.catalog.ProgramCatalogScreen
import com.bendourthe.milestone.ui.dashboard.DashboardScreen
import com.bendourthe.milestone.ui.dashboard.DashboardViewModel
import com.bendourthe.milestone.ui.settings.SettingsScreen
import com.bendourthe.milestone.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object Dashboard

@Serializable
data object Settings

@Serializable
data object Catalog

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val topLevels = listOf(
    TopLevel(Dashboard, Dashboard::class, "Rewards", Icons.Filled.Loyalty, Icons.Outlined.Loyalty),
    TopLevel(Settings, Settings::class, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun MileStoneApp(
    dashboardViewModel: DashboardViewModel,
    catalogViewModel: CatalogViewModel,
    settingsViewModel: SettingsViewModel,
    catalog: CatalogRepository,
    versionName: String,
    navController: NavHostController = rememberNavController(),
    signIn: SignInViewModel? = null,
    createBridge: ((Context, Program) -> LoginBridge)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBar = destination?.hasRoute(Catalog::class) != true

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    topLevels.forEach { top ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(top.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) top.selected else top.unselected, contentDescription = null) },
                            label = { Text(top.label) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(navController, startDestination = Dashboard, modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            composable<Dashboard> {
                DashboardScreen(
                    dashboardViewModel,
                    onAddProgram = { navController.navigate(Catalog) { launchSingleTop = true } },
                    snackbarHostState = snackbarHostState,
                    tileMenuItems = { tile, close ->
                        if (tile.canSync) {
                            DropdownMenuItem(text = { Text("Refresh") }, enabled = !tile.isSyncing, onClick = {
                                close()
                                if (tile.isConnected || tile.hasSavedSignIn) dashboardViewModel.refresh(tile.programId)
                                else signIn?.connect(tile.programId)
                            })
                        }
                        signIn?.let { SignInMenuItems(tile, it, close) }
                    },
                    onSignIn = { programId -> signIn?.connect(programId) },
                )
            }
            composable<Settings> {
                SettingsScreen(settingsViewModel, versionName, onBack = {
                    navController.navigate(Dashboard) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable<Catalog> {
                ProgramCatalogScreen(
                    viewModel = catalogViewModel,
                    onBack = { navController.popBackStack() },
                    onConnect = { programId -> signIn?.connect(programId) },
                )
            }
        }
    }

    if (signIn != null && createBridge != null) {
        SignInHost(signIn, createBridge, snackbarHostState, onReturnToDashboard = { navController.popBackStack(Dashboard, inclusive = false) })
    }
}
