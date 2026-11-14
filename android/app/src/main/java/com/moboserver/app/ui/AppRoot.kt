package com.moboserver.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moboserver.design.MoboPanel
import com.moboserver.design.Phase
import com.moboserver.design.moboPalette
import kotlinx.coroutines.delay

/**
 * Raiz da interface: hospeda o painel do kit (`com.moboserver.design`) ligado ao
 * adaptador observável [PanelViewModel], mais as subpáginas e diálogos que o kit
 * não desenha (opções do mundo, internet, jogo, aparência, avançado, ajuda).
 *
 * O adaptador é o único elo com o servidor: esta função não lê Store nem chama
 * o controlador diretamente.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val vm: PanelViewModel = viewModel()
    val panel by vm.panel.collectAsState()
    val extras by vm.extras.collectAsState()
    val bans by vm.bans.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val dark = when (extras.theme) {
        UiPrefs.LIGHT -> false
        UiPrefs.DARK -> true
        else -> systemDark
    }
    val palette = moboPalette(dark)

    var pendingExport by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            vm.importWorld(uri)
        } else {
            vm.showMessage("Importação cancelada.")
        }
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val id = pendingExport
        pendingExport = null
        if (uri != null && id != null) {
            vm.exportWorld(id, uri)
        } else {
            vm.showMessage("Exportação cancelada.")
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is PanelEvent.OpenImportPicker -> importer.launch(arrayOf("*/*"))
                is PanelEvent.ExportWorld -> {
                    pendingExport = event.id
                    exporter.launch(event.suggestedName)
                }
                is PanelEvent.ShareText -> shareText(context, event.subject, event.body)
            }
        }
    }

    // A mensagem de confirmação some sozinha; o texto de erro do servidor não.
    LaunchedEffect(extras.message) {
        if (extras.message != null) {
            delay(4_000)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        MoboPanel(panel, vm.actions, dark)

        if (extras.advancedOpen) {
            AdvancedPage(
                dark = dark,
                extras = extras,
                bans = bans,
                online = panel.players.map { it.name to it.id },
                onBack = { vm.closeAdvanced() },
                onUpdate = { block -> vm.updateConfig(block) },
                onDiagnostics = { vm.actions.diagnostics() },
                onUnban = { xuid -> vm.unban(xuid) },
                onKick = { name -> vm.kick(name) },
                onBan = { name, key -> vm.ban(name, if (key == name.lowercase()) "" else key) },
                onRefreshBans = { vm.refreshBans() },
                onSaveWorld = { vm.saveWorld() },
                onTestBinary = { vm.testBinary() },
                serverRunning = panel.phase == Phase.Running,
            )
        }

        extras.dialog?.let { dialog ->
            val close = { vm.openDialog(null) }
            when (dialog) {
                PanelDialog.Internet -> InternetDialog(
                    dark = dark,
                    info = extras.internet,
                    onRefresh = { vm.refreshExposure(true) },
                    onCheck = { vm.checkExternalAccess() },
                    onDismiss = close,
                )
                PanelDialog.Help -> HelpDialog(
                    dark = dark,
                    address = panel.localAddress,
                    port = panel.port,
                    onCopyAddress = { vm.actions.copyAddress() },
                    onDismiss = close,
                )
                PanelDialog.EditName -> EditNameDialog(
                    dark = dark,
                    current = panel.serverName,
                    onSave = { vm.saveName(it); close() },
                    onDismiss = close,
                )
                PanelDialog.EditPort -> EditPortDialog(
                    dark = dark,
                    current = panel.port,
                    onSave = { vm.savePort(it); close() },
                    onDismiss = close,
                )
                PanelDialog.Game -> GameDialog(
                    dark = dark,
                    gameMode = extras.gameMode,
                    difficulty = extras.difficulty,
                    onSave = { mode, diff -> vm.saveGame(mode, diff); close() },
                    onDismiss = close,
                )
                PanelDialog.Appearance -> AppearanceDialog(
                    dark = dark,
                    theme = extras.theme,
                    onPick = { vm.setTheme(it) },
                    onDismiss = close,
                )
                is PanelDialog.WorldMenu -> WorldMenuDialog(
                    dark = dark,
                    name = dialog.name,
                    running = panel.selectedWorldId == dialog.id &&
                        (panel.phase == Phase.Starting || panel.phase == Phase.Running || panel.phase == Phase.Stopping),
                    onExport = { vm.requestExport(dialog.id); close() },
                    onValidate = { vm.validateWorld(dialog.id); close() },
                    onDelete = { vm.confirmDelete(dialog.id) },
                    onDismiss = close,
                )
                is PanelDialog.Text -> TextDialog(
                    dark = dark,
                    title = dialog.title,
                    body = dialog.body,
                    onDismiss = close,
                )
                is PanelDialog.ConfirmDelete -> ConfirmDeleteDialog(
                    dark = dark,
                    name = dialog.name,
                    onConfirm = { vm.deleteWorld(dialog.id); close() },
                    onDismiss = close,
                )
            }
        }

        extras.message?.let { message ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 92.dp),
            ) {
                Text(
                    message,
                    color = palette.card,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.ink)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun shareText(context: Context, subject: String, body: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (e: Exception) {
        Log.w("MoboPanel", "compartilhar: ${e.message}")
    }
}
