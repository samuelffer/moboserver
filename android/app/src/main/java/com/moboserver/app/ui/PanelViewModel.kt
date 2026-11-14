package com.moboserver.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moboserver.app.core.BanEntry
import com.moboserver.app.core.Bans
import com.moboserver.app.core.Diagnostics
import com.moboserver.app.core.Net
import com.moboserver.app.core.RuntimeState
import com.moboserver.app.core.ServerConfig
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.ServerService
import com.moboserver.app.core.ServerStatus
import com.moboserver.app.core.Store
import com.moboserver.app.core.WorldInfo
import com.moboserver.app.core.WorldRepository
import com.moboserver.design.PanelActions
import com.moboserver.design.PanelUi
import com.moboserver.design.Phase
import com.moboserver.design.PlayerUi
import com.moboserver.design.WorldUi
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diálogos/subpáginas que a camada visual não desenha sozinha. */
sealed interface PanelDialog {
    data object Internet : PanelDialog
    data object Help : PanelDialog
    data object EditName : PanelDialog
    data object EditPort : PanelDialog
    data object Game : PanelDialog
    data object Appearance : PanelDialog
    data class WorldMenu(val id: String, val name: String) : PanelDialog
    data class ConfirmDelete(val id: String, val name: String) : PanelDialog
}

/** Ações que precisam da Activity (seletor de arquivos, compartilhar). */
sealed interface PanelEvent {
    data object OpenImportPicker : PanelEvent
    data class ShareText(val subject: String, val body: String) : PanelEvent
    data class ExportWorld(val id: String, val suggestedName: String) : PanelEvent
}

/** Situação de rede mostrada na subpágina "Acesso pela internet". */
data class InternetInfo(
    val lanAddress: String = "—",
    val wanAddress: String = "—",
    val publicAddress: String = "—",
    val routerNote: String = "",
    val externalStatus: String = "Não verificado",
    val externalDetail: String = "",
    val tunnelState: String = "",
    val tunnelAddress: String = "",
    val checking: Boolean = false,
)

/** Dados que o kit não modela e as subpáginas usam. */
data class PanelExtras(
    val worlds: List<WorldInfo> = emptyList(),
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val authEnabled: Boolean = true,
    val allowOpenMode: Boolean = false,
    val autoForward: Boolean = true,
    val rakNet: Boolean = false,
    val advancedDiagnostics: Boolean = false,
    val relayAddr: String = "",
    val relayToken: String = "",
    val relayTls: Boolean = true,
    val externalHost: String = "",
    val internet: InternetInfo = InternetInfo(),
    val bannedCount: Int = 0,
    val dialog: PanelDialog? = null,
    val advancedOpen: Boolean = false,
    val theme: String = UiPrefs.SYSTEM,
    val message: String? = null,
)

/**
 * Adaptador observável entre o servidor real e a camada visual do kit
 * (`com.moboserver.design`). Não controla o servidor por conta própria: tudo
 * passa por [ServerController]/[ServerService], que continuam sendo o único
 * controlador do processo. A camada visual nunca lê [Store] diretamente.
 */
class PanelViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app.applicationContext
    private val repository = WorldRepository(context)

    private val _panel = MutableStateFlow(PanelUi())
    val panel: StateFlow<PanelUi> = _panel

    private val _extras = MutableStateFlow(PanelExtras())
    val extras: StateFlow<PanelExtras> = _extras

    private val _bans = MutableStateFlow<List<BanEntry>>(emptyList())
    val bans: StateFlow<List<BanEntry>> = _bans

    private val _events = MutableSharedFlow<PanelEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PanelEvent> = _events

    private var worldsCache: List<WorldUi> = emptyList()
    private var worldsInfo: List<WorldInfo> = emptyList()
    private var worldsSignature: String = ""
    private val thumbnails = mutableMapOf<String, Thumb>()
    private var lanIp: String? = null
    private var lanIpJob: Job? = null
    private var lastLogCount = -1
    private var lastLogsText = ""

    private data class Thumb(val modified: Long, val image: ImageBitmap)

    init {
        ServerController.init(context)
        Store.init(context)
        _extras.value = _extras.value.copy(theme = UiPrefs.theme(context))
        refreshWorlds()
        refreshLanIp()
        viewModelScope.launch {
            while (isActive) {
                rebuild()
                delay(1_000)
            }
        }
        viewModelScope.launch {
            ServerController.state.collect {
                rebuild()
                refreshLanIp()
                refreshWorldsSoon()
            }
        }
        viewModelScope.launch {
            Store.config.collect { rebuild() }
        }
    }

    // ------------------------------------------------------------------ mapeamento

    private fun rebuild() {
        val runtime = ServerController.state.value
        val config = Store.snapshot()

        val players = runtime.players.map { player ->
            PlayerUi(
                id = player.key,
                name = player.name,
                // pingMs < 0 = transporte sem medição: a interface mostra "—".
                latencyMs = player.pingMs.takeIf { it > 0 },
                head = runtime.avatars[player.key]?.image,
            )
        }
        // "Ping dos jogadores" = mediana dos RTT válidos, não a média nem um
        // valor inventado quando nenhum jogador foi medido.
        val validRtt = runtime.players.map { it.pingMs }.filter { it > 0 }.sorted()
        val median = when {
            validRtt.isEmpty() -> null
            validRtt.size % 2 == 1 -> validRtt[validRtt.size / 2]
            else -> (validRtt[validRtt.size / 2 - 1] + validRtt[validRtt.size / 2]) / 2
        }

        // O kit mostra "Selecionado" no botão do mundo; "em execução" entra na
        // linha secundária do cartão, que é o mesmo lugar do tamanho.
        val runningId = ServerController.runningWorldId
        val worldsUi = if (runningId == null) {
            worldsCache
        } else {
            worldsCache.map { world ->
                if (world.id == runningId) world.copy(size = "${world.size} • em execução") else world
            }
        }

        // Seleção apontando para uma pasta que não existe mais: a mensagem
        // precisa dizer isso, não "selecione um mundo".
        val worldsLoaded = worldsSignature.isNotEmpty()
        val selectionMissing = worldsLoaded && config.activeWorldId.isNotBlank() &&
            worldsInfo.none { it.id == config.activeWorldId }

        _panel.value = PanelUi(
            phase = phaseOf(runtime.status),
            selectedWorldId = config.activeWorldId.ifBlank { null },
            worlds = worldsUi,
            players = players,
            serverName = config.serverName,
            localAddress = lanIp ?: runtime.exposure.lanIp ?: "—",
            port = runtime.info?.port ?: config.port,
            maxPlayers = config.maxPlayers,
            viewDistance = config.viewDistance,
            medianRttMs = median,
            uptime = uptimeOf(runtime),
            internetStatus = internetStatus(runtime),
            logs = logsText(runtime),
            error = runtime.failure?.let { "${it.title}. ${it.message}" }
                ?: if (selectionMissing) {
                    "O mundo selecionado não está mais no aparelho. Importe de novo ou escolha outro em Mundos."
                } else {
                    null
                },
        )

        val previous = _extras.value
        _extras.value = previous.copy(
            worlds = worldsInfo,
            gameMode = config.gameMode,
            difficulty = config.difficulty,
            authEnabled = config.authEnabled,
            allowOpenMode = config.allowOpenMode,
            autoForward = config.autoForward,
            rakNet = config.rakNet,
            advancedDiagnostics = config.advancedDiagnostics,
            relayAddr = config.relayAddr,
            relayToken = config.relayToken,
            relayTls = config.relayTls,
            externalHost = config.externalHost,
            internet = internetInfo(runtime),
            bannedCount = _bans.value.size,
        )
    }

    private fun phaseOf(status: ServerStatus): Phase = when (status) {
        ServerStatus.STOPPED -> Phase.Stopped
        ServerStatus.STARTING -> Phase.Starting
        ServerStatus.RUNNING -> Phase.Running
        ServerStatus.STOPPING -> Phase.Stopping
        ServerStatus.ERROR -> Phase.Error
    }

    private fun uptimeOf(runtime: RuntimeState): String {
        val started = runtime.startedAt ?: return "—"
        if (runtime.status != ServerStatus.RUNNING && runtime.status != ServerStatus.STOPPING) return "—"
        val seconds = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            seconds / 3600,
            (seconds % 3600) / 60,
            seconds % 60,
        )
    }

    private fun internetStatus(runtime: RuntimeState): String = when (runtime.exposure.externalVerified) {
        true -> "Teste externo confirmou o acesso"
        false -> "Teste externo não alcançou o servidor"
        null -> "Não verificado"
    }

    private fun internetInfo(runtime: RuntimeState): InternetInfo {
        val exposure = runtime.exposure
        return InternetInfo(
            lanAddress = (lanIp ?: exposure.lanIp ?: "—") + ":" + (runtime.info?.port ?: Store.snapshot().port),
            wanAddress = exposure.wanIp ?: "—",
            publicAddress = exposure.publicIp ?: "—",
            routerNote = exposure.upnpMessage.orEmpty(),
            externalStatus = when (exposure.externalVerified) {
                true -> "Confirmado"
                false -> "Não confirmado"
                null -> "Não verificado"
            },
            externalDetail = exposure.externalCheck.orEmpty(),
            tunnelState = exposure.tunnelState.orEmpty(),
            tunnelAddress = exposure.tunnelAddress.orEmpty(),
            checking = exposure.working,
        )
    }

    private fun logsText(runtime: RuntimeState): String {
        if (runtime.logs.size == lastLogCount) return lastLogsText
        lastLogCount = runtime.logs.size
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lastLogsText = runtime.logs.takeLast(300).joinToString("\n") { line ->
            "${format.format(Date(line.time))}  ${line.message}"
        }
        return lastLogsText
    }

    // --------------------------------------------------------------------- mundos

    fun refreshWorlds() {
        viewModelScope.launch {
            val payload = withContext(Dispatchers.IO) {
                val infos = repository.list()
                val signature = infos.joinToString("|") { "${it.id}:${it.updatedAt}:${it.sizeBytes}" }
                if (signature == worldsSignature) return@withContext null
                val ui = infos.map { info ->
                    WorldUi(
                        id = info.id,
                        name = info.name,
                        size = formatSize(info.sizeBytes),
                        thumbnail = thumbnailFor(info),
                    )
                }
                Triple(signature, infos, ui)
            }
            if (payload != null) {
                worldsSignature = payload.first
                worldsInfo = payload.second
                worldsCache = payload.third
                rebuild()
            }
        }
    }

    private fun refreshWorldsSoon() {
        viewModelScope.launch {
            delay(400)
            refreshWorlds()
        }
    }

    private fun refreshLanIp() {
        if (lanIpJob?.isActive == true) return
        lanIpJob = viewModelScope.launch {
            val value = withContext(Dispatchers.IO) { Net.lanIp() }
            if (value != lanIp) {
                lanIp = value
                rebuild()
            }
        }
    }

    /** Ícone do mundo (`world_icon.jpeg`), reduzido e cacheado pela data do arquivo. */
    private fun thumbnailFor(info: WorldInfo): ImageBitmap? {
        val file = File(repository.directory(info.id), "world_icon.jpeg")
        if (!file.isFile) return null
        val cached = thumbnails[info.id]
        if (cached != null && cached.modified == file.lastModified()) return cached.image
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 480) sample *= 2
            val bitmap: Bitmap? = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
            val image = bitmap?.asImageBitmap()
            if (image != null) thumbnails[info.id] = Thumb(file.lastModified(), image)
            image
        } catch (e: Exception) {
            Log.w(TAG, "ícone do mundo ${info.id}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------- importar

    fun importWorld(uri: Uri) {
        ServerController.beginImport()
        viewModelScope.launch {
            val result: Any = try {
                withContext(Dispatchers.IO) { repository.importWorld(uri, "Mundo importado") }
            } catch (e: Exception) {
                e
            } finally {
                // Libera o início do servidor mesmo com a importação cancelada.
                ServerController.endImport()
            }
            if (result is WorldInfo) {
                val outcome = ServerController.selectWorld(result.id)
                refreshWorlds()
                showMessage(
                    if (outcome.ok) {
                        "Mundo \"${result.name}\" importado e selecionado." +
                            if (result.originalBytes > 0) " O .mcworld original ficou guardado." else ""
                    } else {
                        "Mundo \"${result.name}\" importado, mas não foi possível selecioná-lo: " +
                            (outcome.failure?.message ?: "erro desconhecido")
                    },
                )
            } else {
                refreshWorlds()
                showMessage((result as? Exception)?.message ?: "Não foi possível importar o mundo.")
            }
        }
    }

    fun exportWorld(id: String, uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val target: OutputStream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext false
                    target.use { output ->
                        val original = repository.originalFile(id)
                        if (original.isFile) {
                            original.inputStream().use { input -> input.copyTo(output) }
                        } else {
                            zipDirectory(repository.directory(id), output)
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "exportar mundo $id: ${e.message}")
                    false
                }
            }
            showMessage(if (ok) "Mundo exportado." else "Não foi possível exportar o mundo.")
        }
    }

    private fun zipDirectory(dir: File, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val root = dir.toPath()
            dir.walkTopDown().filter { it.isFile && it.name != ORIGINAL_NAME }.forEach { file ->
                val entry = ZipEntry(root.relativize(file.toPath()).toString().replace(File.separatorChar, '/'))
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ---------------------------------------------------------------------- ações

    val actions: PanelActions = object : PanelActions {
        override fun start() {
            clearMessage()
            ServerService.start(context)
        }

        override fun stop() {
            ServerService.stop(context)
        }

        override fun selectWorld(id: String) {
            val outcome = ServerController.selectWorld(id)
            if (outcome.ok) {
                clearMessage()
                refreshWorlds()
            } else {
                showMessage(
                    outcome.failure?.let { "${it.title}. ${it.message}" }
                        ?: "Não foi possível selecionar o mundo.",
                )
            }
        }

        override fun importWorld() {
            _events.tryEmit(PanelEvent.OpenImportPicker)
        }

        override fun copyAddress() {
            val address = _panel.value.localAddress
            copyToClipboard("Endereço do servidor", address)
            showMessage("Endereço copiado: $address")
        }

        override fun internetDetails() {
            openDialog(PanelDialog.Internet)
        }

        override fun help() {
            openDialog(PanelDialog.Help)
        }

        override fun worldOptions(id: String) {
            val world = worldsInfo.firstOrNull { it.id == id }
            openDialog(PanelDialog.WorldMenu(id, world?.name ?: id))
        }

        override fun copyLogs() {
            copyToClipboard("Log do MoboServer", ServerController.logText())
            showMessage("Log copiado.")
        }

        override fun clearVisibleLogs() {
            ServerController.clearLogs()
            lastLogCount = -1
            rebuild()
        }

        override fun diagnostics() {
            viewModelScope.launch {
                val report = withContext(Dispatchers.IO) { Diagnostics.report(context) }
                _events.tryEmit(PanelEvent.ShareText("MoboServer — diagnóstico", report))
            }
        }

        override fun sendChat(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            ServerController.say(clean)
        }

        override fun editName() {
            openDialog(PanelDialog.EditName)
        }

        override fun editPort() {
            openDialog(PanelDialog.EditPort)
        }

        override fun setMaxPlayers(value: Int) {
            // Atualização de um campo só: nunca reescreve a seleção do mundo.
            Store.update { it.copy(maxPlayers = value.coerceIn(1, 30)) }
        }

        override fun setViewDistance(value: Int) {
            Store.update { it.copy(viewDistance = value.coerceIn(4, 24)) }
        }

        override fun gameSettings() {
            openDialog(PanelDialog.Game)
        }

        override fun advancedSettings() {
            _extras.value = _extras.value.copy(advancedOpen = true)
            refreshBans()
        }

        override fun changeAppearance() {
            openDialog(PanelDialog.Appearance)
        }
    }

    // ------------------------------------------------------------------ diálogos

    fun openDialog(dialog: PanelDialog?) {
        _extras.value = _extras.value.copy(dialog = dialog)
    }

    fun closeAdvanced() {
        _extras.value = _extras.value.copy(advancedOpen = false)
    }

    fun setTheme(value: String) {
        UiPrefs.setTheme(context, value)
        _extras.value = _extras.value.copy(theme = value)
    }

    fun showMessage(text: String) {
        _extras.value = _extras.value.copy(message = text)
    }

    fun clearMessage() {
        if (_extras.value.message == null) return
        _extras.value = _extras.value.copy(message = null)
    }

    // --------------------------------------------------------- mundo (menu/ações)

    fun validateWorld(id: String) {
        viewModelScope.launch {
            val validation = withContext(Dispatchers.IO) { repository.validate(id) }
            refreshWorlds()
            showMessage(
                if (validation.ok) {
                    "Mundo validado: level.dat legível, ${validation.dbFiles} arquivo(s) em db/"
                } else {
                    validation.message(worldsInfo.firstOrNull { it.id == id }?.name.orEmpty())
                },
            )
        }
    }

    fun requestExport(id: String) {
        val name = worldsInfo.firstOrNull { it.id == id }?.name ?: "mundo"
        _events.tryEmit(PanelEvent.ExportWorld(id, safeFileName(name)))
    }

    fun confirmDelete(id: String) {
        val world = worldsInfo.firstOrNull { it.id == id }
        openDialog(PanelDialog.ConfirmDelete(id, world?.name ?: id))
    }

    fun deleteWorld(id: String) {
        viewModelScope.launch {
            if (id == ServerController.runningWorldId) {
                showMessage("Pare o servidor antes de excluir o mundo que está em execução.")
                return@launch
            }
            withContext(Dispatchers.IO) { repository.delete(id) }
            Store.clearWorldIf(id)
            refreshWorlds()
            showMessage("Mundo excluído.")
        }
    }

    // ------------------------------------------------------------ ajustes campos

    fun saveName(name: String) {
        val clean = name.trim().take(40).ifBlank { "MoboServer" }
        Store.update { it.copy(serverName = clean) }
        showMessage("Nome do servidor: $clean")
    }

    fun savePort(port: Int) {
        val clamped = port.coerceIn(1024, 65535)
        Store.update { it.copy(port = clamped) }
        showMessage("Porta: $clamped (vale no próximo início)")
    }

    fun saveGame(gameMode: String, difficulty: String) {
        Store.update { it.copy(gameMode = gameMode, difficulty = difficulty) }
        showMessage("Jogo: $gameMode • $difficulty")
    }

    /** Usado pela subpágina Avançado; cada chamada grava só os campos alterados. */
    fun updateConfig(block: (ServerConfig) -> ServerConfig) {
        Store.update(block)
    }

    fun checkExternalAccess() {
        ServerController.checkExternalAccess()
    }

    fun refreshExposure(forward: Boolean) {
        ServerController.refreshExposure(forward)
    }

    // ---------------------------------------------------------------------- bans

    fun refreshBans() {
        viewModelScope.launch {
            val bans = withContext(Dispatchers.IO) { Bans.list(context) }
            _bans.value = bans
            _extras.value = _extras.value.copy(bannedCount = bans.size)
        }
    }

    fun unban(xuid: String) {
        ServerController.unban(xuid)
        viewModelScope.launch {
            delay(600)
            refreshBans()
        }
    }

    fun kick(playerName: String) {
        ServerController.kick(playerName)
        showMessage("Pedido de expulsão enviado para $playerName.")
    }

    fun ban(playerName: String, xuid: String) {
        ServerController.ban(playerName, xuid)
        showMessage("Pedido de banimento enviado para $playerName.")
        viewModelScope.launch {
            delay(600)
            refreshBans()
        }
    }

    // ------------------------------------------------------------------ utilidade

    private fun copyToClipboard(label: String, text: String) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            Log.w(TAG, "clipboard: ${e.message}")
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifBlank { "mundo" }.take(40)

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 KB"
        bytes >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        else -> "${bytes / 1024} KB"
    }

    companion object {
        private const val TAG = "MoboPanel"
        private const val ORIGINAL_NAME = "original.mcworld"
    }
}
