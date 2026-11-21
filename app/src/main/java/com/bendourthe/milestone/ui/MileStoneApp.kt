package com.bendourthe.milestone.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import com.bendourthe.milestone.sync.background.AndroidReminderNotifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.bendourthe.milestone.domain.SearchTarget
import com.bendourthe.milestone.ui.search.SearchValidation
import com.bendourthe.milestone.ui.search.message
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
data object Search

@Serializable
data object Benefits

@Serializable
data class ProgramBenefits(val programId: String)

/** One card's benefits page; the card is named by its program and last digits only. */
@Serializable
data class CardBenefits(val programId: String, val cardId: String)

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
    TopLevel(Benefits, Benefits::class, "Benefits", Icons.Filled.CreditCard, Icons.Outlined.CreditCard, listOf(ProgramBenefits::class, CardBenefits::class)),
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
    createSearchViewModel: (() -> com.bendourthe.milestone.ui.search.SearchViewModel)? = null,
    /** Issuer sign-ins for Personal and Business pages (ADR-0009); null hides them (tests). */
    connections: com.bendourthe.milestone.ui.benefits.ConnectionsViewModel? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBar = destination?.hasRoute(Catalog::class) != true && destination?.hasRoute(Search::class) != true
    val benefits = benefitsViewModel ?: appBenefitsViewModel()

    val navigationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val openSearch: () -> Unit = { navController.navigate(Search) { launchSingleTop = true } }
    val activity = LocalActivity.current

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar(modifier = Modifier.onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }) {
                    topLevels.forEach { top ->
                        val selected = destination?.hierarchy?.any { d -> (listOf(top.routeClass) + top.children).any { d.hasRoute(it) } } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Benefits always opens on its program page; the other tabs come back where they were.
                                val fresh = top.route == Benefits
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = !fresh
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
        // The outgoing page keeps its viewport when Search hides the bar, so end-of-list positions cannot clamp.
        val bottom = PaddingValues(bottom = maxOf(bottomBarHeight, padding.calculateBottomPadding()))
        val systemBottom = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        val swipes = rememberPageSwipes(navController) { entry -> topLevels.any { entry.destination.hasRoute(it.routeClass) } }
        NavHost(navController, startDestination = Dashboard, modifier = swipes) {
            composable<Dashboard> { entry ->
                DestinationInsets(bottom) {
                    val resultSearch = resultSearchViewModel(navController, entry, createSearchViewModel)
                    val request = resultSearch?.reveal?.takeIf { it.target is SearchTarget.ProgramTarget }

                    // A benefit reminder opens its card page once, from the start destination so the graph is set;
                    // clearing the action keeps a rotation or a return to Rewards from opening it again.
                    LaunchedEffect(Unit) {
                        val intent = activity?.intent ?: return@LaunchedEffect
                        if (intent.action != AndroidReminderNotifier.ACTION_OPEN_CARD) return@LaunchedEffect
                        val programId = intent.getStringExtra(AndroidReminderNotifier.EXTRA_PROGRAM_ID)
                        val digits = intent.getStringExtra(AndroidReminderNotifier.EXTRA_LAST_DIGITS)
                        intent.action = null
                        // The extra carries the card's id: its last digits for Amex, a local card id for Chase (ADR-0009).
                        if (programId != null && digits != null && CardKey.isValidCardId(digits)) {
                            navController.navigate(Benefits) { launchSingleTop = true }
                            if (CardKey.isValidLastDigits(digits)) navController.navigate(ProgramBenefits(programId))
                            navController.navigate(CardBenefits(programId, digits))
                        }
                    }
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
                        benefitsPrograms = catalog.programs.filter { it.benefits != null }.map { it.programId }.toSet(),
                        onOpenBenefits = { programId ->
                            navController.navigate(Benefits) { launchSingleTop = true }
                            navController.navigate(ProgramBenefits(programId)) { launchSingleTop = true }
                        },
                        onSignIn = { programId -> signIn?.connect(programId) },
                        onSearch = openSearch,
                        reveal = request,
                        validateReveal = { resultSearch?.validateReveal(it) ?: SearchValidation.UNAVAILABLE },
                        onRevealInvalid = { result -> navigationScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            request?.invalidate()
                            resultSearch?.report(result)
                            if (navController.currentBackStackEntry == entry) navController.popBackStack()
                        } },
                    )
                }
            }
            composable<Benefits> {
                DestinationInsets(bottom) {
                    BenefitsScreen(
                        benefits,
                        onOpenCard = { key -> navController.navigate(CardBenefits(key.programId, key.cardId)) { launchSingleTop = true } },
                        snackbarHostState = snackbarHostState,
                        onOpenProgram = { programId -> navController.navigate(ProgramBenefits(programId)) { launchSingleTop = true } },
                        onSearch = openSearch,
                    )
                }
            }
            composable<ProgramBenefits> { entry ->
                DestinationInsets(bottom) {
                    val route = entry.toRoute<ProgramBenefits>()
                    BenefitsScreen(benefits, onOpenCard = { key -> navController.navigate(CardBenefits(key.programId, key.cardId)) { launchSingleTop = true } },
                        snackbarHostState = snackbarHostState, programId = route.programId, onBackToPrograms = { navController.popBackStack() }, onSearch = openSearch,
                        connections = connections, onAddSignIn = { programId, connectionId -> signIn?.openConnection(programId, connectionId) })
                }
            }
            composable<CardBenefits> { entry ->
                DestinationInsets(bottom) {
                    val route = entry.toRoute<CardBenefits>()
                    val key = remember(route) { runCatching { CardKey(route.programId, route.cardId) }.getOrNull() }
                    val resultSearch = resultSearchViewModel(navController, entry, createSearchViewModel)
                    val request = resultSearch?.reveal?.takeIf { request -> when (val target = request.target) {
                        is SearchTarget.ProgramTarget -> false
                        is SearchTarget.CardTarget -> target.card == key
                        is SearchTarget.BenefitTarget -> target.card == key
                    } }
                    val invalid: (SearchValidation) -> Unit = { result -> navigationScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        val alreadyShown = request?.consumed == true
                        request?.invalidate()
                        if (!alreadyShown) {
                            resultSearch?.report(result)
                            if (navController.currentBackStackEntry == entry) navController.popBackStack()
                        } else snackbarHostState.showSnackbar(result.message())
                    } }
                    val gone: () -> Unit = { navigationScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        if (resultSearch != null) {
                            request?.invalidate()
                            resultSearch.report(SearchValidation.UNAVAILABLE)
                            if (navController.currentBackStackEntry == entry) navController.popBackStack()
                        } else {
                            if (!navController.popBackStack(ProgramBenefits(route.programId), inclusive = false)) navController.popBackStack(Benefits, inclusive = false)
                            snackbarHostState.showSnackbar("That card is no longer on your account")
                        }
                    } }
                    if (key == null) LaunchedEffect(Unit) { gone() } else {
                        CardBenefitsScreen(benefits, key, onBack = { navController.popBackStack() }, onGone = gone, onSearch = openSearch,
                            reveal = request, validateReveal = { visit, source -> resultSearch?.validateReveal(visit, source) ?: SearchValidation.UNAVAILABLE }, onRevealInvalid = invalid, snackbarHostState = snackbarHostState)
                    }
                }
            }
            composable<Search> { entry ->
                DestinationInsets(systemBottom) {
                    val search = appSearchViewModel(entry, createSearchViewModel)
                    val scope = rememberCoroutineScope()
                    var selecting by remember { mutableStateOf(false) }
                    com.bendourthe.milestone.ui.search.SearchScreen(search, onBack = { navController.popBackStack() }, selecting = selecting, onSelect = { target ->
                        if (!selecting && navController.currentBackStackEntry == entry) {
                            selecting = true
                            scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                val queryVersion = search.queryVersion
                                try {
                                    val result = search.prepare(target)
                                    if (search.queryVersion == queryVersion && navController.currentBackStackEntry == entry) {
                                        if (result != SearchValidation.CURRENT) search.report(result) else {
                                            val route: Any = when (target) {
                                                is SearchTarget.ProgramTarget -> Dashboard
                                                is SearchTarget.CardTarget -> CardBenefits(target.card.programId, target.card.cardId)
                                                is SearchTarget.BenefitTarget -> CardBenefits(target.card.programId, target.card.cardId)
                                            }
                                            navController.navigate(route) { launchSingleTop = true }
                                        }
                                    }
                                } finally { selecting = false }
                            }
                        }
                    })
                }
            }

            composable<Settings> {
                DestinationInsets(bottom) {
                    val reminders by benefits.remindersEnabled.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settingsViewModel,
                        versionName,
                        onBack = {
                            navController.navigate(Dashboard) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        benefitReminders = reminders.takeIf { benefits.hasReminderSetting },
                        onBenefitReminders = benefits::setRemindersEnabled,
                    )
                }
            }
            composable<Catalog> {
                DestinationInsets(systemBottom) {
                    ProgramCatalogScreen(
                        viewModel = catalogViewModel,
                        onBack = { navController.popBackStack() },
                        onConnect = { programId -> signIn?.connect(programId) },
                    )
                }
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
    return viewModel(viewModelStoreOwner = owner) { BenefitsViewModel(container.cards, container.catalog, container.accounts, reminders = container.reminderSettings, displaySettings = container.benefitsDisplaySettings) }
}

/** The prior Search entry owns the visit through a result page and across configuration changes. */
@Composable
private fun resultSearchViewModel(nav: NavHostController, entry: androidx.navigation.NavBackStackEntry,
    create: (() -> com.bendourthe.milestone.ui.search.SearchViewModel)?): com.bendourthe.milestone.ui.search.SearchViewModel? {
    val owner = remember(entry) { nav.previousBackStackEntry?.takeIf { it.destination.hasRoute(Search::class) } }
    return owner?.let { appSearchViewModel(it, create) }
}

@Composable
private fun appSearchViewModel(owner: ViewModelStoreOwner, create: (() -> com.bendourthe.milestone.ui.search.SearchViewModel)?): com.bendourthe.milestone.ui.search.SearchViewModel {
    val container = if (create == null) (LocalContext.current.applicationContext as MileStoneApplication).container else null
    return viewModel(viewModelStoreOwner = owner) {
        create?.invoke() ?: com.bendourthe.milestone.ui.search.SearchViewModel(container!!.accounts, container.cards, container.catalog, container.benefitsDisplaySettings)
    }
}

@Composable
private fun DestinationInsets(bottom: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) { content() }
}
