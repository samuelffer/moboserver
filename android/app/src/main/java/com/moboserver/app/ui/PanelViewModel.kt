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
import com.moboserver.app.core.AvatarState
import com.moboserver.app.core.BanEntry
import com.moboserver.app.core.Bans
import com.moboserver.app.core.ChatMessage
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
import com.moboserver.design.AvatarStateUi
import com.moboserver.design.ChatUi
import com.moboserver.design.LogUi
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
    data class Text(val title: String, val body: String) : PanelDialog
    /** Explica por que a cabeça de um jogador não aparece (ou mostra o resumo). */
    data class PlayerDetails(val id: String) : PanelDialog
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
    val cgnat: Boolean = false,
    val lastCheckAt: String = "",
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

    /** true quando a lista de mundos já foi lida ao menos uma vez. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private var worldsCache: List<WorldUi> = emptyList()
    private var worldsInfo: List<WorldInfo> = emptyList()
    private var worldsSignature: String = ""
    private val thumbnails = mutableMapOf<String, Thumb>()
    private var lanIp: String? = null
    private var lanIpJob: Job? = null
    private var worldsJob: Job? = null
    private var lastLogId = -1L
    private var logCache: List<LogUi> = emptyList()
    private var chatCache: List<ChatUi> = emptyList()

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
            val avatar = runtime.avatars[player.key]
            PlayerUi(
                id = player.key,
                name = player.name,
                // pingMs < 0 = transporte sem medição; 0 é válido (<1 ms).
                latencyMs = player.pingMs.takeIf { it >= 0 },
                head = avatar?.image,
                avatarState = when (avatar?.state) {
                    AvatarState.READY -> AvatarStateUi.Ready
                    AvatarState.UNSUPPORTED -> AvatarStateUi.Unsupported
                    AvatarState.FAILED -> AvatarStateUi.Failed
                    else -> AvatarStateUi.Loading
                },
                avatarReason = avatar?.reason.orEmpty(),
                avatarInfo = avatar?.info?.summary().orEmpty(),
            )
        }

        val runningId = ServerController.runningWorldId
        val worldsUi = if (runningId == null) {
            worldsCache
        } else {
            worldsCache.map { world ->
                if (world.id == runningId) world.copy(size = "${world.size} • em execução") else world
            }
        }

        val worldsLoaded = worldsSignature.isNotEmpty()
        val selectionMissing = worldsLoaded && config.activeWorldId.isNotBlank() &&
            worldsInfo.none { it.id == config.activeWorldId }

        val address = addressInfo(runtime, config)

        _panel.value = PanelUi(
            phase = phaseOf(runtime.status),
            selectedWorldId = config.activeWorldId.ifBlank { null },
            worlds = worldsUi,
            players = players,
            serverName = config.serverName,
            lanAddress = address.lanIp ?: "—",
            internetAddress = address.share,
            internetKind = address.kind,
            internetStatus = address.status,
            port = runtime.info?.port ?: config.port,
            maxPlayers = config.maxPlayers,
            viewDistance = config.viewDistance,
            uptime = uptimeOf(runtime),
            lastSave = lastSaveText(runtime),
            gameMode = gameModeLabel(config.gameMode),
            difficulty = difficultyLabel(config.difficulty),
            logLines = logsOf(runtime),
            chatMessages = chatOf(runtime),
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
            internet = internetInfo(runtime, address),
            bannedCount = _bans.value.size,
        )
    }

    /** Classifica o endereço: verificado, IP público observado, WAN do roteador ou só LAN. */
    private data class Address(
        val lanIp: String?,
        val share: String,
        val kind: String,
        val status: String,
        val cgnat: Boolean,
    )

    private fun addressInfo(runtime: RuntimeState, config: ServerConfig): Address {
        val exposure = runtime.exposure
        val port = runtime.info?.port ?: config.port
        val local = lanIp ?: exposure.lanIp
        val cgnat = local != null && Net.isCarrierGradeNat(local)
        val publicIp = exposure.publicIp?.takeIf { it.isNotBlank() }
        val wanIp = exposure.wanIp?.takeIf { it.isNotBlank() }
        val verified = exposure.externalVerified == true

        // 100.64/10 é CGNAT: nunca é endereço de entrada para amigos.
        val usablePublic = publicIp?.takeIf { !Net.isCarrierGradeNat(it) }
        val share = when {
            verified && usablePublic != null -> "$usablePublic:$port"
            usablePublic != null -> "$usablePublic:$port"
            wanIp != null && !Net.isCarrierGradeNat(wanIp) -> "$wanIp:$port"
            local != null -> "$local:$port"
            else -> "—"
        }
        val kind = when {
            verified && usablePublic != null -> "verificado"
            usablePublic != null -> "observado"
            wanIp != null && !Net.isCarrierGradeNat(wanIp) -> "wan"
            local != null -> "local"
            else -> "nenhum"
        }
        val status = when {
            verified -> "Teste externo confirmou o acesso"
            publicIp != null -> "Acesso não verificado"
            cgnat -> "Rede da operadora (CGNAT): o endereço 100.64/10 não serve para amigos"
            else -> "Não verificado"
        }
        return Address(local, share, kind, status, cgnat)
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
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    /** Só o evento de salvamento concluído conta; nada de inferir de log. */
    private fun lastSaveText(runtime: RuntimeState): String {
        val at = runtime.lastSaveAt
        if (at <= 0L) return "—"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    private fun logsOf(runtime: RuntimeState): List<LogUi> {
        val last = runtime.logs.lastOrNull()
        if (last == null) {
            if (logCache.isNotEmpty()) logCache = emptyList()
            lastLogId = -1L
            return logCache
        }
        if (last.time == lastLogId && logCache.size == runtime.logs.size) return logCache
        lastLogId = last.time
        logCache = runtime.logs.mapIndexed { index, line ->
            LogUi(id = line.time * 1000 + index, time = line.time, level = line.level, message = line.message)
        }
        return logCache
    }

    private fun chatOf(runtime: RuntimeState): List<ChatUi> {
        val last = runtime.chat.lastOrNull()
        if (last == null) {
            if (chatCache.isNotEmpty()) chatCache = emptyList()
            return chatCache
        }
        if (chatCache.size == runtime.chat.size && chatCache.lastOrNull()?.status == last.status) return chatCache
        chatCache = runtime.chat.map {
            ChatUi(
                id = it.id,
                time = it.at,
                from = it.from,
                text = it.text,
                fromPanel = it.fromPanel,
                status = it.status,
            )
        }
        return chatCache
    }

    private fun internetInfo(runtime: RuntimeState, address: Address): InternetInfo {
        val exposure = runtime.exposure
        val port = runtime.info?.port ?: Store.snapshot().port
        return InternetInfo(
            lanAddress = (address.lanIp ?: "—") + ":" + port,
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
            cgnat = address.cgnat,
            lastCheckAt = if (exposure.externalVerified == null) {
                ""
            } else {
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
            },
        )
    }

    private fun gameModeLabel(value: String): String = when (value) {
        "creative" -> "Criativo"
        "adventure" -> "Aventura"
        "spectator" -> "Espectador"
        else -> "Sobrevivência"
    }

    private fun difficultyLabel(value: String): String = when (value) {
        "peaceful" -> "Pacífico"
        "easy" -> "Fácil"
        "hard" -> "Difícil"
        else -> "Normal"
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
            }
            _ready.value = true
            UiReadiness.ready = true
            rebuild()
        }
    }

    /**
     * O estado do servidor muda a cada linha de log; sem esta guarda cada
     * linha agendaria uma varredura de pastas. Só uma por vez.
     */
    private fun refreshWorldsSoon() {
        if (worldsJob?.isActive == true) return
        worldsJob = viewModelScope.launch {
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
            val panel = _panel.value
            val address = panel.internetAddress
            if (address.isBlank() || address == "—") {
                showMessage("Ainda não há endereço para divulgar. Verifique o acesso externo.")
                return
            }
            copyToClipboard("Endereço do servidor", address)
            showMessage(
                when (panel.internetKind) {
                    "verificado" -> "Endereço copiado (acesso externo confirmado): $address"
                    "observado" -> "Endereço copiado. Acesso externo ainda NÃO foi verificado: $address"
                    "wan" -> "Endereço do roteador copiado. Acesso externo não verificado: $address"
                    else -> "Endereço da rede local copiado (só no mesmo Wi-Fi): $address"
                },
            )
        }

        override fun shareAddress() {
            val panel = _panel.value
            val address = panel.internetAddress
            if (address.isBlank() || address == "—") {
                showMessage("Ainda não há endereço para compartilhar.")
                return
            }
            val note = when (panel.internetKind) {
                "verificado" -> "Acesso externo confirmado por teste."
                "observado" -> "Atenção: acesso externo não verificado (IP observado por STUN)."
                "wan" -> "Atenção: endereço do roteador; acesso externo não verificado."
                else -> "Endereço da rede local: só funciona no mesmo Wi-Fi."
            }
            val text = "${panel.serverName}\nMinecraft Bedrock: $address\n$note\n" +
                "Versão do jogo: 1.26.60 (protocolo 2225)"
            _events.tryEmit(PanelEvent.ShareText("MoboServer — endereço", text))
        }

        override fun internetDetails() {
            openDialog(PanelDialog.Internet)
        }

        override fun verifyAccess() {
            ServerController.refreshExposure(true)
            ServerController.checkExternalAccess()
            showMessage("Verificando o acesso externo… o resultado aparece em “Acesso pela internet”.")
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

        override fun exportLogs() {
            val panel = _panel.value
            val body = buildString {
                appendLine("MoboServer — registros (${panel.phase})")
                panel.logLines.forEach { line ->
                    appendLine("${formatTime(line.time)} [${line.level}] ${line.message}")
                }
            }
            _events.tryEmit(PanelEvent.ShareText("MoboServer — registros", body))
        }

        override fun clearVisibleLogs() {
            ServerController.clearLogs()
            logCache = emptyList()
            lastLogId = -1L
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

        override fun playerDetails(id: String) {
            openDialog(PanelDialog.PlayerDetails(id))
        }

        override fun editName() {
            openDialog(PanelDialog.EditName)
        }

        override fun editPort() {
            openDialog(PanelDialog.EditPort)
        }

        override fun setMaxPlayers(value: Int) {
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

    /** Reenvia a miniatura de um jogador quando o usuário pede no detalhe. */
    fun retryAvatar(id: String) {
        val name = ServerController.state.value.players.firstOrNull { it.key == id }?.name ?: return
        ServerController.requestAvatar(name)
        showMessage("Pedindo a skin de $name de novo…")
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

    /** Pede ao servidor para salvar agora; o horário só aparece com o evento de confirmação. */
    fun saveWorld() {
        ServerController.saveWorld()
        showMessage("Pedido de salvamento enviado ao servidor.")
    }

    /** Roda `mobosrv -version`: confirma que o binário embutido executa neste aparelho. */
    fun testBinary() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Diagnostics.testBinary(context) }
            openDialog(PanelDialog.Text("Teste do binário do servidor", result))
        }
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

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

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
