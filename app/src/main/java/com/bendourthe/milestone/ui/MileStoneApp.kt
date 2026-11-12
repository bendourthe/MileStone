package com.bendourthe.milestone.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import com.bendourthe.milestone.MileStoneApplication
import com.bendourthe.milestone.domain.CardKey
import com.bendourthe.milestone.ui.benefits.BenefitsScreen
import com.bendourthe.milestone.ui.benefits.BenefitsViewModel
import com.bendourthe.milestone.ui.benefits.CardBenefitsScreen
import kotlinx.coroutines.launch
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

@Serializable
data object Benefits

/** One card's benefits page; the card is named by its program and last digits only. */
@Serializable
data class CardBenefits(val programId: String, val lastDigits: String)

private data class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val selected: ImageVector,
    val unselected: ImageVector,
    /** Routes below this tab that keep it selected. */
    val children: List<KClass<*>> = emptyList(),
)

private val topLevels = listOf(
    TopLevel(Dashboard, Dashboard::class, "Rewards", Icons.Filled.Loyalty, Icons.Outlined.Loyalty),
    TopLevel(Benefits, Benefits::class, "Benefits", Icons.Filled.CreditCard, Icons.Outlined.CreditCard, listOf(CardBenefits::class)),
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
    /** Built from the application's container when not given (tests pass their own). */
    benefitsViewModel: BenefitsViewModel? = null,
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
                        val selected = destination?.hierarchy?.any { d -> (listOf(top.routeClass) + top.children).any { d.hasRoute(it) } } == true
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
            composable<Benefits> {
                BenefitsScreen(
                    benefitsViewModel ?: appBenefitsViewModel(),
                    onOpenCard = { key -> navController.navigate(CardBenefits(key.programId, key.lastDigits)) { launchSingleTop = true } },
                    snackbarHostState = snackbarHostState,
                )
            }
            composable<CardBenefits> { entry ->
                val route = entry.toRoute<CardBenefits>()
                val key = remember(route) { runCatching { CardKey(route.programId, route.lastDigits) }.getOrNull() }
                val scope = rememberCoroutineScope()
                val gone: () -> Unit = {
                    navController.popBackStack(Benefits, inclusive = false)
                    scope.launch { snackbarHostState.showSnackbar("That card is no longer on your account") }
                }
                if (key == null) LaunchedEffect(Unit) { gone() } else {
                    CardBenefitsScreen(benefitsViewModel ?: appBenefitsViewModel(), key, onBack = { navController.popBackStack() }, onGone = gone)
                }
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

/** The activity-scoped Benefits view model, built from the application's container. */
@Composable
private fun appBenefitsViewModel(): BenefitsViewModel {
    val container = (LocalContext.current.applicationContext as MileStoneApplication).container
    val owner = LocalActivity.current as ViewModelStoreOwner
    return viewModel(viewModelStoreOwner = owner) { BenefitsViewModel(container.cards, container.catalog, container.accounts) }
}
