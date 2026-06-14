package com.nebulaai.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nebulaai.app.ui.screens.ChatScreen
import com.nebulaai.app.ui.screens.ImageGenScreen
import com.nebulaai.app.ui.screens.SettingsScreen

sealed class NebulaRoute(val route: String, val label: String, val icon: ImageVector) {
    object Chat : NebulaRoute("chat", "Chat", Icons.Default.Chat)
    object ImageGen : NebulaRoute("imagegen", "Generate", Icons.Default.AutoAwesome)
    object Settings : NebulaRoute("settings", "Settings", Icons.Default.Settings)
}

val NAV_ITEMS = listOf(NebulaRoute.Chat, NebulaRoute.ImageGen, NebulaRoute.Settings)

@Composable
fun NebulaNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                NAV_ITEMS.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NebulaRoute.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NebulaRoute.Chat.route) {
                ChatScreen()
            }
            composable(NebulaRoute.ImageGen.route) {
                ImageGenScreen()
            }
            composable(NebulaRoute.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
