package com.moboserver.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.ServerService
import com.moboserver.app.core.Store

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Início", Icons.Filled.Home),
    Tab("Mundos", Icons.Filled.Public),
    Tab("Console", Icons.Filled.List),
    Tab("Conexão", Icons.Filled.SignalCellularAlt),
    Tab("Ajustes", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val state by ServerController.state.collectAsState()
    val config by Store.config.collectAsState()
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = MoboColors.Background,
        bottomBar = {
            NavigationBar(containerColor = MoboColors.Surface) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MoboColors.Lime,
                            selectedTextColor = MoboColors.Lime,
                            indicatorColor = MoboColors.LimeSoft,
                            unselectedIconColor = MoboColors.TextSecondary,
                            unselectedTextColor = MoboColors.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                0 -> HomeScreen(
                    state = state,
                    onOpenWorlds = { tab = 1 },
                    config = config,
                    onStart = { ServerService.start(context) },
                    onStop = { ServerController.stop() },
                    onForwardPort = { ServerController.refreshExposure(forward = true) },
                    onCheckAccess = { ServerController.checkExternalAccess() },
                    onKick = { player -> ServerController.kick(player.name) },
                    onBan = { player -> ServerController.ban(player.name, player.xuid) },
                )
                1 -> WorldsScreen()
                2 -> ConsoleScreen()
                3 -> ConnectionScreen()
                else -> SettingsScreen()
            }
        }
    }
}
