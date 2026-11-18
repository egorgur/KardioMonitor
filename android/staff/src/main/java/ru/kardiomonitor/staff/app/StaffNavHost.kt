package ru.kardiomonitor.staff.app

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.kardiomonitor.staff.features.bandcheck.check.BandCheckScreen
import ru.kardiomonitor.staff.features.inventory.bandlist.BandsScreen
import ru.kardiomonitor.staff.features.inventory.domain.BandAddress
import ru.kardiomonitor.staff.features.inventory.labeling.BandDetailScreen
import ru.kardiomonitor.staff.features.inventory.manualentry.ManualEntryScreen
import ru.kardiomonitor.staff.features.issuance.domain.Issuance
import ru.kardiomonitor.staff.features.issuance.issuancelog.IssuanceHistoryScreen
import ru.kardiomonitor.staff.features.issuance.qrdisplay.PairingCodeScreen

private enum class RootDestination(val route: String, val label: String) {
    Bands("bands", "Браслеты"),
    History("history", "Журнал"),
    About("about", "О программе"),
}

@Composable
fun StaffNavHost(viewModel: StaffViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val rootRoute = RootDestination.entries.any { it.route == route }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (rootRoute) RootNavigation(navController, route) },
    ) { padding ->
        StaffGraph(navController, viewModel, Modifier.padding(padding))
    }
}

@Composable
private fun StaffGraph(nav: NavHostController, viewModel: StaffViewModel, modifier: Modifier) {
    val bands by viewModel.bands.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val entryProtocol by viewModel.entryProtocol.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()
    NavHost(navController = nav, startDestination = RootDestination.Bands.route, modifier = modifier) {
        composable(RootDestination.Bands.route) {
            BandsScreen(
                bands,
                query,
                history.filter(Issuance::isOpen).mapTo(mutableSetOf()) { it.address },
                viewModel::search,
                { nav.navigate(detailRoute(it.address)) },
                { nav.navigate("manual") },
            )
        }
        composable(RootDestination.History.route) {
            LaunchedEffect(Unit) { viewModel.refreshHistory() }
            IssuanceHistoryScreen(history)
        }
        composable(RootDestination.About.route) { AboutScreen() }
        manualEntryDestination(nav, viewModel, discovery, entryProtocol)
        composable("band/{address}") { entry ->
            val address = entry.addressArgument()
            val band = bands.firstOrNull { it.address == address }
            if (band != null) {
                val issued = history.any { it.address == address.value && it.isOpen }
                BandDetailScreen(
                    band = band,
                    isIssued = issued,
                    onBack = nav::popBackStack,
                    onSaveLabel = { viewModel.label(address, it) },
                    onInspect = { viewModel.resetCheck(); nav.navigate(checkRoute(address)) },
                    onIssue = {
                        viewModel.prepareCode(
                            address,
                            onReady = { nav.navigate("qr") },
                            onCheckRequired = { nav.navigate(checkRoute(address)) },
                        )
                    },
                    onReturn = { viewModel.returnBand(address) },
                )
            }
        }
        composable("check/{address}") { entry ->
            val address = entry.addressArgument()
            val label = bands.firstOrNull { it.address == address }?.label ?: address.value
            BandCheckScreen(
                label,
                check,
                onBack = { viewModel.cancelCheck(); nav.popBackStack() },
                onInspect = { viewModel.inspect(address) },
                protocol = bands.firstOrNull { it.address == address }?.protocol
                    ?: ru.kardiomonitor.staff.shared.BandProtocol.Unknown,
            )
        }
        composable("qr") {
            val display = code
            if (display != null) {
                PairingCodeScreen(
                    display.code,
                    onDisplayed = viewModel::confirmCodeDisplayed,
                    onClose = { viewModel.clearCode(); nav.popBackStack() },
                    protocol = display.protocol,
                )
            } else {
                LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }
    }
}

private fun NavGraphBuilder.manualEntryDestination(
    nav: NavHostController,
    viewModel: StaffViewModel,
    discovery: BandDiscoveryUiState,
    entryProtocol: ru.kardiomonitor.staff.shared.BandProtocol?,
) {
    composable("manual") {
        val context = LocalContext.current
        ManualEntryScreen(
            discovery = discovery,
            protocol = entryProtocol,
            onSelectProtocol = viewModel::selectProtocol,
            onChoose = viewModel::chooseBondedBand,
            onBack = nav::popBackStack,
            onFind = viewModel::findBand,
            onCancelFind = viewModel::cancelBandDiscovery,
            onSave = { address, keyText, protocol ->
                viewModel.addBand(address, keyText, protocol) {
                    nav.navigate(detailRoute(it)) { popUpTo("manual") { inclusive = true } }
                }
                context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
            },
        )
    }
}

@Composable
private fun RootNavigation(nav: NavHostController, route: String?) {
    NavigationBar {
        RootDestination.entries.forEach { destination ->
            val icon = when (destination) {
                RootDestination.Bands -> Icons.Outlined.Watch
                RootDestination.History -> Icons.Outlined.History
                RootDestination.About -> Icons.Outlined.Info
            }
            NavigationBarItem(
                selected = route == destination.route,
                onClick = {
                    nav.navigate(destination.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun detailRoute(address: BandAddress): String = "band/${Uri.encode(address.value)}"
private fun checkRoute(address: BandAddress): String = "check/${Uri.encode(address.value)}"
private fun androidx.navigation.NavBackStackEntry.addressArgument(): BandAddress =
    BandAddress.of(Uri.decode(requireNotNull(arguments?.getString("address"))))
