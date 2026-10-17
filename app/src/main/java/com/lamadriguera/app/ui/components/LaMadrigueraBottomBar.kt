package com.lamadriguera.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.lamadriguera.app.ui.navigation.BottomTab
import com.lamadriguera.app.ui.navigation.Destinations

@Composable
fun LaMadrigueraBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?
) {
    NavigationBar {
        BottomTab.entries.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { destination ->
                destination.matches(tab)
            } == true

            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            // Mantiene una sola copia de cada tab y conserva su estado
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = stringResource(tab.labelRes)
                    )
                },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}

private fun NavDestination.matches(tab: BottomTab): Boolean = when (tab) {
    BottomTab.LISTINGS -> hasRoute<Destinations.Listings>()
    BottomTab.AUCTIONS -> hasRoute<Destinations.Auctions>()
    BottomTab.CHAT -> hasRoute<Destinations.Conversations>()
    BottomTab.BETTING -> hasRoute<Destinations.BettingHub>()
    BottomTab.PROFILE -> hasRoute<Destinations.Profile>()
}

private fun BottomTab.icon() = when (this) {
    BottomTab.LISTINGS -> Icons.Filled.Storefront
    BottomTab.AUCTIONS -> Icons.Filled.Gavel
    BottomTab.CHAT -> Icons.Filled.Chat
    BottomTab.BETTING -> Icons.Filled.SportsEsports
    BottomTab.PROFILE -> Icons.Filled.Person
}
