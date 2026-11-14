package com.moboserver.app.core

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Single owner of the embedded server process. Both the UI and the foreground
 * service talk to this object, so the server can never be started twice and the
 * screen always shows the real state of the process.
 *
 * Every start creates a [Session]. Callbacks, watchdogs and network checks carry
 * the session they belong to and are ignored once a newer one exists: a late
 * event from a process that was already stopped must never change the state of
 * the current run.
 */
object ServerController {
    private const val TAG = "MoboServer"
    private const val MAX_LOG_LINES = 1200
    private const val LOG_FILE_MAX_BYTES = 4L * 1024 * 1024
    private const val START_TIMEOUT_MS = 60_000L
    private const val STOP_TIMEOUT_MS = 60_000L
    private const val FORCE_KILL_GRACE_MS = 4_000L
    /** Intervalo mínimo entre dois pedidos de reenvio de avatar do mesmo jogador. */
    private const val AVATAR_REQUEST_INTERVAL_MS = 10_000L
    /** Espera máxima pela confirmação de uma mensagem de chat do painel. */
    private const val CHAT_CONFIRM_TIMEOUT_MS = 6_000L
    private const val MAX_CHAT_MESSAGES = 400

    /** One run of the server process, from "prepare" until the process exits. */
    private class Session(val id: Long) {
        @Volatile var process: ServerProcess? = null
        @Volatile var stopRequested = false
        @Volatile var finished = false
        var startJob: Job? = null
        var stopJob: Job? = null
        /** Sessão informada pelo servidor nos eventos de avatar. */
        var avatarSession: Long = 0L
        /** Quando o processo começou a subir, para medir o tempo até o status. */
        var startedAtMs: Long = 0L
        /** Mundo capturado no início: a sessão não relê a seleção depois. */
        var worldId: String = ""
        var recovery: String = ""
        var stoppedEventSeen = false
    }

    private var appContext: Context? = null
    private var session: Session? = null
    private var nextSessionId = 0L
    private val logs = ArrayDeque<LogLine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state

    private val pendingRecovery = HashMap<String, String>()
    private val pendingSkinExports = HashMap<String, Uri>()
    private val skinExportSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val _skinExports = kotlinx.coroutines.flow.MutableSharedFlow<SkinExportReady>(extraBufferCapacity = 4)
    val skinExports: kotlinx.coroutines.flow.SharedFlow<SkinExportReady> = _skinExports

    /** Ação explícita do usuário: vale para o próximo início deste mundo. */
    @Synchronized
    fun requestGenerationRecovery(id: String, mode: String): Boolean {
        if (isBusy) {
            appendLog("warn", "Recuperação de geração recusada: servidor em execução.")
            return false
        }
        if (mode != "reset" && mode != "adopt-legacy") return false
        val repository = worlds() ?: return false
        if (!repository.exists(id)) return false
        pendingRecovery[id] = mode
        appendLog("info", "Recuperação de geração armada para $id: $mode (só será gravada após o mundo iniciar validado).")
        return true
    }

    fun clearGenerationRecovery(id: String) {
        synchronized(this) { pendingRecovery.remove(id) }
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Store.init(context)
        }
    }

    fun worlds(): WorldRepository? = appContext?.let { WorldRepository(it) }

    val isBusy: Boolean
        get() = session?.let { !it.finished } == true

    /** Marca o começo de uma importação; iniciar o servidor fica bloqueado. */
    fun beginImport() {
        _state.update { it.copy(importing = true) }
    }

    /** Fim da importação (sucesso ou erro): libera o início. */
    fun endImport() {
        _state.update { it.copy(importing = false) }
    }

    /** ID do mundo carregado pela sessão atual (null quando nada está rodando). */
    val runningWorldId: String?
        get() {
            val s = session ?: return null
            if (s.finished) return null
            return when (_state.value.status) {
                ServerStatus.STARTING, ServerStatus.RUNNING, ServerStatus.STOPPING -> s.worldId
                else -> null
            }
        }

    /**
     * Fonte única de seleção de mundo. Verifica a pasta, persiste o ID e devolve
     * o resultado; em caso de falha a seleção anterior é preservada e nada é
     * apagado.
     */
    @Synchronized
    fun selectWorld(id: String): SelectOutcome {
        val previous = Store.snapshot().activeWorldId
        val cleanId = id.trim()
        if (cleanId.isBlank()) {
            val failure = WorldDiagnosis.inspect("", null) ?: WorldDiagnosis.noRepository()
            appendLog("warn", "Seleção de mundo recusada: ID vazio.")
            return SelectOutcome(ok = false, selectedId = previous, previousId = previous, failure = failure)
        }
        if (isBusy) {
            appendLog("warn", "Seleção de mundo recusada: servidor em execução.")
            return SelectOutcome(
                ok = false,
                selectedId = previous,
                previousId = previous,
                failure = WorldDiagnosis.busy(),
            )
        }
        val repository = worlds()
            ?: return SelectOutcome(
                ok = false,
                selectedId = previous,
                previousId = previous,
                failure = WorldDiagnosis.noRepository(),
            )
        val facts = repository.inspect(cleanId)
        val failure = WorldDiagnosis.inspect(cleanId, facts)
        if (failure != null) {
            appendLog(
                "warn",
                "Seleção de mundo recusada (${WorldDiagnosis.code(failure.problem)}): " +
                    "id=$cleanId pasta=${repository.directory(cleanId).absolutePath} existe=${facts.folderExists}",
            )
            return SelectOutcome(ok = false, selectedId = previous, previousId = previous, failure = failure)
        }
        Store.selectWorld(cleanId)
        appendLog(
            "info",
            "Mundo selecionado: id=$cleanId (anterior=${previous.ifBlank { "<vazio>" }}) " +
                "pasta=${repository.directory(cleanId).absolutePath}",
        )
        return SelectOutcome(ok = true, selectedId = cleanId, previousId = previous)
    }

    /**
     * Starts the server with the settings currently stored in [Store].
     *
     * The heavy part (world folder, native binary and process spawn) runs on the
     * IO dispatcher: this method is called from the foreground service, which
     * runs on the main thread, and extracting a 30 MB binary there would freeze
     * the app.
     */
    @Synchronized
    fun start(): Boolean {
        val context = appContext ?: return false
        if (isBusy) return true

        val created = Session(++nextSessionId)
        session = created
        synchronized(logs) { logs.clear() }
        _state.value = RuntimeState(status = ServerStatus.STARTING, exposure = _state.value.exposure)
        created.startJob = scope.launch {
            try {
                startInternal(context, created)
            } catch (e: Exception) {
                Log.e(TAG, "failed to prepare the server", e)
                if (isCurrent(created)) {
                    fail(created, WorldDiagnosis.binaryFailure("preparo: ${e.message}"))
                }
            }
        }
        return true
    }

    private fun isCurrent(s: Session): Boolean = session === s && !s.finished && !s.stopRequested

    private fun startInternal(context: Context, s: Session) {
        // Importação em andamento: a seleção ainda não foi gravada, então o
        // início recusa com o motivo certo em vez de dizer "nenhum mundo".
        if (_state.value.importing) {
            if (isCurrent(s)) fail(s, WorldDiagnosis.importRunning())
            return
        }

        // Snapshot da sessão: o que chega ao processo é exatamente o que estava
        // selecionado aqui, mesmo que a UI mude de ideia no meio do caminho.
        val config = Store.snapshot()
        val repository = WorldRepository(context)
        val worldId = config.activeWorldId
        s.worldId = worldId
        s.recovery = synchronized(this) { pendingRecovery[worldId].orEmpty() }

        val version = appVersion()
        appendLog(
            "debug",
            "Início: app=${context.packageName} v${version.first}/${version.second} " +
                "mundo=${worldId.ifBlank { "<vazio>" }} porta=${config.port} raknet=${config.rakNet}",
        )

        // Classificação explícita: cada motivo tem mensagem própria e nenhum
        // deles apaga ou recria a pasta do mundo.
        val facts = if (worldId.isBlank()) null else repository.inspect(worldId)
        val selectionFailure = WorldDiagnosis.inspect(worldId, facts)
        if (selectionFailure != null) {
            appendLog(
                "warn",
                "Início recusado (${WorldDiagnosis.code(selectionFailure.problem)}): " +
                    "id=${worldId.ifBlank { "<vazio>" }} " +
                    "dir=${if (worldId.isBlank()) "-" else repository.directory(worldId).absolutePath} " +
                    "existe=${facts?.folderExists ?: false} level.dat=${facts?.hasLevelDat ?: false} " +
                    "db=${facts?.dbFiles ?: 0} arquivos",
            )
            if (isCurrent(s)) fail(s, selectionFailure)
            return
        }
        if (!isCurrent(s)) return

        val validation = repository.validate(worldId)
        if (validation.timeout) {
            appendLog("error", "Validação do mundo: timeout de ${WorldRepository.VALIDATE_TIMEOUT_MS / 1000}s.")
            if (isCurrent(s)) fail(s, WorldDiagnosis.validationTimeout(WorldRepository.VALIDATE_TIMEOUT_MS / 1000))
            return
        }
        if (!validation.ok) {
            if (isCurrent(s)) {
                fail(
                    s,
                    WorldDiagnosis.validationFailure(
                        validation.code,
                        validation.detail,
                        repository.nameOf(worldId),
                    ),
                )
            }
            return
        }
        if (!isCurrent(s)) return
        if (validation.fixedTags.isNotEmpty()) {
            appendLog(
                "warn",
                "Metadados do level.dat preservados (${validation.fixedTags.joinToString(", ")}): " +
                    "eles voltam ao arquivo quando o mundo é salvo.",
            )
        }

        val worldDir = repository.directory(worldId)
        val worldName = validation.levelName.ifBlank { repository.nameOf(worldId) }
        _state.update {
            it.copy(
                worldName = worldName,
                avatars = emptyMap(),
                pingHistory = emptyList(),
            )
        }

        appendLog("info", "Iniciando servidor \"${config.serverName}\" na porta configurada ${config.port} (transportes serão confirmados abaixo)")
        val binary = try {
            ServerProcess.resolveBinary(context)
        } catch (e: Exception) {
            if (isCurrent(s)) fail(s, WorldDiagnosis.binaryFailure("resolveBinary: ${e.message}"))
            return
        }
        // Last checkpoint: stopping while the binary is being prepared must not
        // spawn a process after the app already reported "desligado".
        if (!isCurrent(s)) return

        // Sem esta lista o Pion tenta enumerar interfaces pelo netlink, o que o
        // Android nega, e toda conexao NetherNet morre em
        // "netlinkrib: permission denied".
        val ifaces = IfaceReporter.write(context)
        if (ifaces == null) {
            appendLog(
                "warn",
                "Não foi possível listar as interfaces de rede do aparelho: " +
                    "a negociação por endereço (NetherNet) deve falhar.",
            )
        } else {
            appendLog(
                "info",
                "Interfaces repassadas ao ICE: ${ifaces.interfaces} utilizável(is) — ${ifaces.summary}",
            )
        }

        val process = ServerProcess(
            binary = binary,
            workDir = context.filesDir,
            args = buildArgs(context, config, worldDir, s),
            environment = buildEnvironment(context),
            listener = { event -> onEvent(s, event) },
        )
        s.process = process
        s.startedAtMs = System.currentTimeMillis()
        try {
            process.start()
            synchronized(this) { pendingRecovery.remove(worldId) }
            IfaceReporter.startWatching(context) { process.isAlive }
        } catch (e: Exception) {
            Log.e(TAG, "failed to start server", e)
            if (isCurrent(s)) {
                fail(s, WorldDiagnosis.binaryFailure("start(): ${e.message}"))
            }
            return
        }
        watchStartup(s, process)
    }

    /** Asks the server to shut down gracefully, saving the world. */
    @Synchronized
    fun stop() {
        val s = session
        if (s == null || s.finished) {
            markStopped()
            return
        }
        if (s.stopRequested) return
        s.stopRequested = true
        LanProbe.stop()

        val process = s.process
        if (process == null) {
            // Still preparing: cancel the pending start so it can never create a
            // process after the user asked to stop.
            s.finished = true
            session = null
            s.startJob?.cancel()
            appendLog("info", "Inicialização cancelada antes do servidor subir.")
            markStopped()
            return
        }

        _state.update { it.copy(status = ServerStatus.STOPPING) }
        // A mensagem de desligamento/salvamento vem do processo Go uma única
        // vez. Se ele morrer antes de confirmar, o handler de Exited avisa.
        val sent = process.send(command("stop").toString())
        s.stopJob = scope.launch {
            if (!sent) {
                delay(1_500)
                process.destroy()
            }
            val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
            while (process.isAlive && System.currentTimeMillis() < deadline) delay(500)
            if (process.isAlive) {
                appendLog("warn", "O servidor não respondeu a tempo, encerrando à força.")
                process.destroy()
            }
            // The exit event normally arrives first and finishes the session
            // there; this is only the safety net for a process that never
            // reports its exit.
            delay(FORCE_KILL_GRACE_MS)
            synchronized(this@ServerController) {
                if (session === s && !s.finished) {
                    s.finished = true
                    session = null
                    markStopped()
                }
            }
        }
    }

    fun kick(playerName: String) {
        if (sendCommand(command("kick").put("name", playerName))) {
            appendLog("info", "Pedido de expulsão enviado para $playerName")
        }
    }

    fun ban(playerName: String, xuid: String) {
        if (sendCommand(command("ban").put("name", playerName).put("xuid", xuid))) {
            appendLog("info", "Pedido de banimento enviado para $playerName")
        }
    }

    /**
     * Envia uma mensagem do painel. Ela só vira "enviada" quando o servidor
     * confirma com "chat_sent"; se a confirmação não chegar, o estado vira
     * falha e o texto continua na interface (nada é apagado).
     */
    fun say(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val seq = chatSeq.incrementAndGet()
        val serverId = seq.toString()
        val sent = sendCommand(command("say", serverId).put("msg", text))
        val entry = ChatMessage(
            id = seq,
            at = System.currentTimeMillis(),
            from = "Painel",
            text = text,
            fromPanel = true,
            status = if (sent) ChatStatus.PENDING else ChatStatus.FAILED,
        )
        if (sent) synchronized(pendingChat) { pendingChat[serverId] = seq }
        _state.update { st -> st.copy(chat = (st.chat + entry).takeLast(MAX_CHAT_MESSAGES)) }
        if (!sent) return
        scope.launch {
            delay(CHAT_CONFIRM_TIMEOUT_MS)
            val stillPending = synchronized(pendingChat) { pendingChat.remove(serverId) != null }
            if (stillPending) {
                _state.update { st ->
                    st.copy(
                        chat = st.chat.map {
                            if (it.id == seq && it.status == ChatStatus.PENDING) {
                                it.copy(status = ChatStatus.FAILED)
                            } else {
                                it
                            }
                        },
                    )
                }
            }
        }
    }

    private val chatSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val pendingChat = HashMap<String, Long>()


    fun unban(xuid: String) {
        if (xuid.isBlank()) return
        val sent = sendCommand(command("unban").put("xuid", xuid))
        if (!sent) {
            appContext?.let { Bans.remove(it, xuid) }
        }
        appendLog("info", "Banimento removido.")
    }

    fun saveWorld() {
        sendCommand(command("save"))
    }

    fun requestPlayers() {
        sendCommand(command("players"))
    }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
        _state.update { it.copy(logs = emptyList()) }
    }

    fun logText(): String {
        val snapshot = synchronized(logs) { logs.toList() }
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return snapshot.joinToString("\n") { "${format.format(Date(it.time))} [${it.level}] ${it.message}" }
    }

    /**
     * Detects the network situation and, optionally, asks the router to open
     * the port. Nothing here proves that players outside can join: UPnP only
     * reports that the router *accepted* the rule, and STUN only reports which
     * address the internet sees. The UI keeps the two facts apart.
     */
    fun refreshExposure(forward: Boolean) {
        val context = appContext ?: return
        val owner = session
        if (_state.value.exposure.working) return
        scope.launch {
            _state.update { st ->
                st.copy(
                    exposure = st.exposure.copy(
                        working = true,
                        upnpMessage = if (forward) "Conversando com o roteador..." else st.exposure.upnpMessage,
                    ),
                )
            }

            val lanIp = Net.lanIp()
            if (!stillSame(owner)) return@launch
            _state.update { st -> st.copy(exposure = st.exposure.copy(lanIp = lanIp)) }

            var wanIp: String? = null
            if (forward && !lanIp.isNullOrBlank()) {
                val port = _state.value.info?.port ?: Store.config.value.port
                val result = Upnp.forward(context, port, lanIp)
                appendLog(if (result.ok) "info" else "warn", result.message)
                wanIp = result.externalIp
                if (!stillSame(owner)) return@launch
                _state.update { st ->
                    st.copy(
                        exposure = st.exposure.copy(
                            // "upnpOk" means "the router accepted the mapping",
                            // nothing more.
                            upnpOk = result.ok,
                            upnpMessage = result.message,
                            wanIp = result.externalIp ?: st.exposure.wanIp,
                        ),
                    )
                }
            }

            // Observed address, independent from what the router believes.
            val observed = Net.publicIp()
            if (!stillSame(owner)) return@launch
            _state.update { st ->
                st.copy(
                    exposure = st.exposure.copy(
                        working = false,
                        publicIp = observed ?: st.exposure.publicIp,
                        wanIp = st.exposure.wanIp ?: wanIp,
                        externalVerified = if (observed != null && st.exposure.externalVerifiedIp != null && observed != st.exposure.externalVerifiedIp) null else st.exposure.externalVerified,
                        externalVerifiedIp = if (observed != null && st.exposure.externalVerifiedIp != null && observed != st.exposure.externalVerifiedIp) null else st.exposure.externalVerifiedIp,
                        externalCheck = if (observed != null && st.exposure.externalVerifiedIp != null && observed != st.exposure.externalVerifiedIp) "O endereço observado mudou; o teste anterior foi invalidado." else st.exposure.externalCheck,
                    ),
                )
            }
            appendLog("info", describeInternet(Net.lanIp(), _state.value.exposure))
        }
    }

    /** Asks a public API whether the server is reachable from the internet. */
    fun checkExternalAccess() {
        if (_state.value.status != ServerStatus.RUNNING) {
            _state.update { st -> st.copy(exposure = st.exposure.copy(externalCheck = "Inicie o servidor antes de testar acesso externo.")) }
            return
        }
        val owner = session
        val exposure = _state.value.exposure
        val port = _state.value.info?.port ?: Store.config.value.port
        val target = exposure.publicIp?.takeIf { AddressClassifier.isPublicIpv4(it) }
            ?: exposure.wanIp?.takeIf { AddressClassifier.isPublicIpv4(it) }
        if (target.isNullOrBlank()) {
            _state.update { st ->
                st.copy(
                    exposure = st.exposure.copy(
                        externalVerified = false,
                        externalVerifiedIp = null,
                        externalCheck = "Sem IPv4 público válido e utilizável: não deu para testar. WAN privada/CGNAT continua só no diagnóstico.",
                    ),
                )
            }
            return
        }
        if (exposure.working) return
        scope.launch {
            _state.update { st ->
                st.copy(exposure = st.exposure.copy(working = true, externalCheck = "Testando de fora..."))
            }
            val result = Net.checkBedrockReachable(target, port)
            val verified = result.startsWith("Acessível")
            appendLog("info", "Teste de acesso externo ($target:$port): $result")
            if (!stillSame(owner)) return@launch
            _state.update { st ->
                st.copy(
                    exposure = st.exposure.copy(
                        working = false,
                        externalCheck = result,
                        externalVerified = verified,
                        externalVerifiedIp = if (verified) target else null,
                    ),
                )
            }
        }
    }

    /**
     * Human readable summary of the current network shape. It is deliberately
     * phrased as observation + hypothesis: the app cannot see the router's NAT
     * table or the carrier's setup.
     */
    fun describeInternet(lanIp: String?, exposure: Exposure): String {
        val lines = ArrayList<String>()
        lines.add(if (lanIp != null) "Rede local: $lanIp" else "Sem Wi-Fi ativo")
        exposure.wanIp?.let { lines.add("WAN do roteador (${if (AddressClassifier.looksLikeIpv6(it)) AddressClassifier.ipv6Kind(it) else AddressClassifier.ipv4Kind(it)}): $it") }
        exposure.publicIp?.let { lines.add("Observado por STUN (${if (AddressClassifier.looksLikeIpv6(it)) AddressClassifier.ipv6Kind(it) else AddressClassifier.ipv4Kind(it)}): $it") }
        if (exposure.wanIp != null && exposure.publicIp != null && exposure.wanIp != exposure.publicIp) {
            lines.add(
                "O roteador e o STUN discordam: provavelmente há outro NAT entre o roteador e a internet.",
            )
        }
        exposure.publicIp?.let { ip ->
            if (AddressClassifier.isPrivateIpv4(ip)) {
                lines.add("O endereço observado é privado: o celular não tem caminho direto para a internet.")
            } else if (Net.isCarrierGradeNat(ip)) {
                lines.add("O endereço observado está em 100.64/10: indício de CGNAT do provedor.")
            }
        }
        if (exposure.upnpOk) {
            lines.add("O roteador aceitou a regra de redirecionamento; isso não confirma acesso externo.")
        }
        return lines.joinToString(" • ")
    }

    // ---------------------------------------------------------------- internal

    /** True when [owner] is still the session the caller started working for. */
    private fun stillSame(owner: Session?): Boolean {
        val current = session
        if (owner == null) return current == null || current.finished
        return current === owner && !owner.finished
    }

    private fun markStopped() {
        _state.update {
            it.copy(
                status = ServerStatus.STOPPED,
                players = emptyList(),
                avatars = emptyMap(),
                pingHistory = emptyList(),
                info = null,
                startedAt = null,
            )
        }
    }

    private fun watchStartup(s: Session, process: ServerProcess) {
        scope.launch {
            delay(START_TIMEOUT_MS)
            if (session === s && !s.finished && !s.stopRequested &&
                _state.value.status == ServerStatus.STARTING && s.process === process
            ) {
                appendLog("error", "O servidor não ficou online em 60 segundos.")
                process.destroy()
                s.stopRequested = true
                fail(
                    s,
                    StartFailure(
                        problem = StartProblem.OTHER,
                        title = "O servidor não terminou de iniciar",
                        message = "O servidor não ficou online em 60 segundos. Veja a última mensagem no " +
                            "Console e tente de novo.",
                        technical = "watchStartup: timeout de 60s",
                    ),
                )
            }
        }
    }

    private fun onEvent(s: Session, event: ServerEvent) {
        // A late event from an old process must never touch the current run.
        if (session !== s || s.finished) return

        when (event) {
            is ServerEvent.Ready -> {
                _state.update {
                    it.copy(
                        status = ServerStatus.RUNNING,
                        startedAt = System.currentTimeMillis(),
                        info = ServerInfo(
                            port = event.port,
                            mcVersion = event.version,
                            protocol = event.protocol,
                            maxPlayers = event.maxPlayers,
                            authEnabled = event.authEnabled,
                            raknetOk = event.raknetOk,
                            signalingOk = event.signalingOk,
                            signalingName = event.signalingName,
                            iceOk = event.iceOk,
                            iceCandidates = event.iceCandidates,
                            iceHosts = event.iceHosts,
                            iceInterfaceCount = event.iceInterfaceCount,
                            iceError = event.iceError,
                            transports = event.transports,
                            signalingLatencyMs = event.signalingLatencyMs,
                            generator = event.generator,
                            origin = event.origin,
                            generationEngine = event.generationEngine,
                            generationVersion = event.generationVersion,
                            appVersion = event.appVersion,
                            revision = event.revision,
                            sourceHash = event.sourceHash,
                        ),
                        error = null,
                    )
                }
                val elapsedSeconds = if (s.startedAtMs > 0) {
                    ((System.currentTimeMillis() - s.startedAtMs) / 1000.0)
                } else {
                    -1.0
                }
                appendLog(
                    "info",
                    "Servidor iniciado no celular • Minecraft Bedrock ${event.version} • porta ${event.port}" +
                        if (elapsedSeconds >= 0) {
                            " • status publicado em ${String.format(Locale.getDefault(), "%.1f", elapsedSeconds)} s"
                        } else {
                            ""
                        },
                )
                if (event.generator.isNotBlank()) {
                    appendLog("info", "Geração efetiva deste mundo: ${event.generator} (${event.generationEngine}, versão ${event.generationVersion}); origem ${event.origin}.")
                }
                if (event.appVersion.isNotBlank() || event.revision.isNotBlank()) {
                    appendLog("info", "Servidor embutido: app=${event.appVersion} revision=${event.revision} source=${event.sourceHash}")
                }
                val netherNetActive = event.transports.contains("nethernet") || event.signalingOk
                if (!netherNetActive) {
                    appendLog("info", "NetherNet desligado ou sem sinalização confirmada; não há porta UDP legada ativa por isso.")
                }
                appendLog(
                    if (event.signalingOk) "info" else if (netherNetActive) "error" else "debug",
                    if (event.signalingOk) {
                        "Sinalização NetherNet: GET /v1/join respondeu 200 (nome \"${event.signalingName}\", " +
                            "protocolo ${event.signalingProtocol})"
                    } else {
                        "Sinalização NetherNet: o endpoint HTTP não respondeu — quem entrar pelo endereço " +
                            "vai cair no RakNet depois de esperar"
                    },
                )
                appendLog(
                    if (event.iceOk) "info" else if (netherNetActive) "error" else "debug",
                    if (event.iceOk) {
                        "Negociação ICE: ${event.iceCandidates} candidato(s) local(is) em " +
                            "${event.iceHosts.joinToString(", ").ifBlank { "?" }} " +
                            "(${event.iceInterfaceCount} endereço(s) de interface repassados do Android)"
                    } else {
                        "Negociação ICE falhou: ${event.iceError.ifBlank { "sem detalhes" }}"
                    },
                )
                val transports = if (event.transports.isNotEmpty()) {
                    event.transports.joinToString(" + ")
                } else {
                    when {
                        event.raknetOk -> "raknet"
                        event.signalingOk -> "nethernet"
                        else -> "nenhum"
                    }
                }
                appendLog("info", "Transportes ativos: $transports")
                if (Store.config.value.rakNet && !event.raknetOk) {
                    appendLog(
                        "warn",
                        "Compatibilidade RakNet está ligada, mas o listener UDP não abriu nesta porta — " +
                            "a aba LAN do Minecraft não vai encontrar o servidor.",
                    )
                }
                if (!Store.config.value.rakNet) {
                    appendLog(
                        "info",
                        "Compatibilidade RakNet desligada: o servidor não aparece na aba LAN do Minecraft. " +
                            "Quem entra usa o endereço e a porta (NetherNet).",
                    )
                }
                startProbePage(s, event.port, event.version, event.protocol, event.maxPlayers, event.authEnabled)
                refreshExposure(forward = Store.config.value.autoForward)
            }

            is ServerEvent.Log -> appendLog(event.level, event.message)

            is ServerEvent.Lan -> {
                _state.update { st ->
                    st.copy(info = st.info?.copy(lanTargets = event.targets))
                }
                val reachable = event.targets.filter { it.ok }
                if (reachable.isEmpty() && event.targets.isNotEmpty()) {
                    appendLog(
                        "warn",
                        "O servidor não respondeu em nenhum endereço local a partir do próprio celular. " +
                            "Possíveis causas: VPN ativa ou regra de firewall do sistema.",
                    )
                }
            }

            is ServerEvent.Tunnel -> {
                _state.update { st ->
                    st.copy(
                        exposure = st.exposure.copy(
                            tunnelState = event.state,
                            tunnelAddress = event.address.ifBlank { st.exposure.tunnelAddress },
                        ),
                    )
                }
                if (event.state == "connected") {
                    appendLog("info", "Túnel conectado: ${event.address}:${event.port}")
                }
            }

            is ServerEvent.Join -> {
                val players = _state.value.players.toMutableList()
                if (players.none { it.name.equals(event.player.name, ignoreCase = true) }) {
                    players.add(event.player)
                }
                _state.update { it.copy(players = players) }
            }

            is ServerEvent.Quit -> {
                _state.update { st ->
                    st.copy(players = st.players.filterNot { p -> p.name.equals(event.name, ignoreCase = true) })
                }
            }

            is ServerEvent.Players -> {
                _state.update { st ->
                    val online = event.players
                    // Miniaturas de quem saiu não ficam penduradas na memória.
                    val keys = online.map { it.key }.toSet()
                    st.copy(
                        players = online,
                        avatars = st.avatars.filterKeys { it in keys },
                        pingHistory = recordPings(st.pingHistory, online),
                    )
                }
                // Consumidor sem imagem (evento perdido ou app aberto depois do
                // join) pede o reenvio; o limite por jogador evita inundar o
                // processo e o PNG não vai em todo tick.
                event.players.forEach { player ->
                    val avatar = _state.value.avatars[player.key]
                    if (avatar == null || (!avatar.available && avatar.state == AvatarState.LOADING)) {
                        requestAvatar(player.name)
                    }
                }
            }

            is ServerEvent.PlayerAvatar -> decodeAvatar(s, event)

            is ServerEvent.Chat -> {
                if (s.avatarSession == 0L) s.avatarSession = event.session
                if (event.session != 0L && s.avatarSession != 0L && event.session != s.avatarSession) return
                _state.update { st ->
                    st.copy(
                        chat = (st.chat + ChatMessage(
                            id = System.nanoTime(),
                            at = System.currentTimeMillis(),
                            from = event.from,
                            text = event.msg,
                            status = ChatStatus.SENT,
                        )).takeLast(MAX_CHAT_MESSAGES),
                    )
                }
            }

            is ServerEvent.ChatSent -> {
                val messageId = synchronized(pendingChat) { pendingChat.remove(event.id) }
                if (messageId != null) {
                    val status = if (event.ok) ChatStatus.SENT else ChatStatus.FAILED
                    _state.update { st ->
                        st.copy(
                            chat = st.chat.map { message ->
                                if (message.id == messageId) message.copy(status = status) else message
                            },
                        )
                    }
                }
            }

            is ServerEvent.Saved -> _state.update { st -> st.copy(lastSaveAt = event.at) }

            is ServerEvent.SaveFailed -> appendLog("error", "O servidor não conseguiu salvar o mundo.")

            is ServerEvent.Stopped -> {
                s.stoppedEventSeen = true
                if (!event.saved) {
                    appendLog("error", "O servidor encerrou sem confirmar que o mundo foi salvo por completo.")
                }
            }

            is ServerEvent.Fatal -> fail(
                s,
                if (event.code.startsWith("generation_")) {
                    WorldDiagnosis.generationFailure(event.code, event.message)
                } else {
                    StartFailure(
                        problem = StartProblem.OTHER,
                        title = "O servidor parou",
                        message = event.message,
                        technical = "evento fatal do binário code=${event.code}",
                    )
                },
            )

            is ServerEvent.AvatarExport -> handleAvatarExport(s, event)

            is ServerEvent.Exited -> {
                LanProbe.stop()
                s.finished = true
                if (session === s) session = null
                val status = _state.value.status
                when {
                    s.stopRequested -> {
                        if (status == ServerStatus.STOPPING || status == ServerStatus.RUNNING ||
                            status == ServerStatus.STARTING
                        ) {
                            markStopped()
                        }
                        if (!s.stoppedEventSeen) {
                            appendLog("warn", "O processo encerrou antes de confirmar o desligamento/salvamento do mundo.")
                        }
                    }
                    status == ServerStatus.ERROR -> Unit
                    else -> fail(
                        s,
                        StartFailure(
                            problem = StartProblem.OTHER,
                            title = "O servidor foi encerrado",
                            message = "O servidor foi encerrado inesperadamente (código ${event.code}). " +
                                "Veja o console para descobrir o motivo.",
                            technical = "Exited(code=${event.code}) sem stopRequested",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Serves a tiny status page so another device can prove the TCP path works.
     * It says nothing about UDP, which is what Minecraft uses.
     */
    /**
     * Decodifica o PNG da cabeça fora da thread principal e só aceita 64x64.
     *
     * Duas proteções acontecem **antes de qualquer branch**:
     *  - a sessão do evento precisa ser a sessão atual (imagem de outra execução
     *    não aparece);
     *  - a revisão do servidor precisa ser maior que a já aplicada (uma skin
     *    antiga que terminou de decodificar depois não sobrescreve a nova).
     *
     * O motivo informado pelo servidor é sempre guardado: o jogador nunca fica
     * com um traço sem explicação.
     */
    private fun decodeAvatar(s: Session, event: ServerEvent.PlayerAvatar) {
        val key = if (event.xuid.isNotBlank()) event.xuid else event.name.lowercase()
        if (key.isBlank()) return
        if (s.avatarSession == 0L) s.avatarSession = event.session
        if (event.session != s.avatarSession) return
        if (!stillSame(s)) return

        val previous = _state.value.avatars[key]
        val previousRev = previous?.rev ?: 0L
        if (previous != null && event.rev > 0 && event.rev <= previousRev) {
            // Evento atrasado de uma skin já substituída.
            noteAvatarDiagnostic(
                "avatar descartado: ${event.name} rev ${event.rev} <= $previousRev",
            )
            return
        }
        val base = PlayerAvatar(
            hash = event.hash,
            image = previous?.image,
            layout = event.layout,
            reason = event.reason,
            state = if (event.available) AvatarState.LOADING else AvatarState.UNSUPPORTED,
            info = event.info,
            rev = maxOf(event.rev, previousRev),
        )

        if (!event.available || event.png.isBlank()) {
            val reason = event.reason.ifBlank { "o servidor não conseguiu gerar a imagem" }
            publishAvatar(key, base.copy(state = AvatarState.UNSUPPORTED, reason = reason, image = null))
            noteAvatarDiagnostic("avatar indisponível: ${event.name} — $reason")
            return
        }

        val cached = _state.value.avatars[key]
        if (cached != null && cached.hash == event.hash && cached.state == AvatarState.READY && cached.rev >= base.rev) {
            return
        }
        publishAvatar(key, base.copy(state = AvatarState.LOADING))

        scope.launch {
            val decoded = try {
                withContext(Dispatchers.Default) {
                    val bytes = android.util.Base64.decode(event.png, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null && (bmp.width != 64 || bmp.height != 64)) {
                        bmp.recycle()
                        null
                    } else {
                        bmp
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (!stillSame(s)) {
                decoded?.recycle()
                return@launch
            }
            if (decoded == null) {
                publishAvatar(
                    key,
                    base.copy(
                        state = AvatarState.FAILED,
                        reason = "imagem recebida não pôde ser decodificada (PNG 64x64 esperado)",
                        image = null,
                    ),
                )
                noteAvatarDiagnostic("avatar com PNG inválido: ${event.name}")
                return@launch
            }
            val image = decoded.asImageBitmap()
            var applied = false
            _state.update { st ->
                // Só publica se: o jogador continua online, a sessão é a mesma e
                // ninguém publicou uma revisão maior enquanto decodificávamos.
                val current = st.avatars[key]
                when {
                    st.players.none { it.key == key } -> st
                    current != null && current.rev > base.rev -> st
                    else -> {
                        applied = true
                        st.copy(avatars = st.avatars + (key to base.copy(state = AvatarState.READY, image = image)))
                    }
                }
            }
            if (applied) {
                noteAvatarDiagnostic(
                    "avatar ok: ${event.name} layout=${event.layout} hash=${event.hash}",
                )
            } else {
                decoded.recycle()
            }
        }
    }

    private fun publishAvatar(key: String, avatar: PlayerAvatar) {
        _state.update { st -> st.copy(avatars = st.avatars + (key to avatar)) }
    }

    /** Últimas linhas de avatar para o relatório compartilhado (sem imagem). */
    private val avatarLog = ArrayDeque<String>()

    private fun noteAvatarDiagnostic(line: String) {
        synchronized(avatarLog) {
            avatarLog.addLast("${System.currentTimeMillis()} $line")
            while (avatarLog.size > 24) avatarLog.removeFirst()
        }
    }

    /** Diagnóstico de avatar por jogador, incluindo o motivo do formato. */
    fun avatarDiagnostics(): List<String> {
        val st = _state.value
        return buildList {
            add("Jogadores online: ${st.players.size}")
            st.players.forEach { player ->
                val avatar = st.avatars[player.key]
                add(
                    buildString {
                        append(player.name)
                        append(" (chave ").append(player.key).append(")")
                        append(" estado=").append(avatar?.state ?: AvatarState.LOADING)
                        if (avatar != null) {
                            if (avatar.rev > 0) append(" rev=").append(avatar.rev)
                            if (avatar.hash.isNotBlank()) append(" hash=").append(avatar.hash)
                            if (avatar.layout.isNotBlank()) append(" layout=").append(avatar.layout)
                            if (avatar.reason.isNotBlank()) append(" motivo=").append(avatar.reason)
                            append(" info=").append(avatar.info.summary())
                        }
                    },
                )
            }
            synchronized(avatarLog) {
                if (avatarLog.isNotEmpty()) {
                    add("-- Últimos eventos de avatar --")
                    avatarLog.forEach { add(it) }
                }
            }
        }
    }

    /**
     * Pede ao servidor para reenviar a miniatura. É chamado quando um jogador
     * aparece sem imagem e nenhum evento chegou (app reaberto, evento perdido).
     * Há limite por jogador para não inundar o processo.
     */
    fun requestAvatar(name: String) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(avatarRequests) {
            val last = avatarRequests[name] ?: 0L
            if (now - last < AVATAR_REQUEST_INTERVAL_MS) return
            avatarRequests[name] = now
        }
        sendCommand(JSONObject().put("cmd", "avatar").put("name", name))
    }

    private val avatarRequests = HashMap<String, Long>()

    /** Exportação explícita: o usuário escolhe o destino (.zip) antes do pedido. */
    fun requestAvatarExport(name: String, output: Uri) {
        if (name.isBlank()) return
        if (_state.value.status != ServerStatus.RUNNING) {
            _skinExports.tryEmit(SkinExportReady("", "Inicie o servidor e conecte o jogador antes de exportar a skin.", false))
            return
        }
        val id = "skin-" + skinExportSeq.incrementAndGet()
        synchronized(pendingSkinExports) { pendingSkinExports[id] = output }
        val sent = sendCommand(JSONObject().put("cmd", "avatar_export").put("name", name).put("id", id))
        if (!sent) {
            synchronized(pendingSkinExports) { pendingSkinExports.remove(id) }
            _skinExports.tryEmit(SkinExportReady("", "Não foi possível pedir a skin ao servidor.", false))
        }
    }

    private fun handleAvatarExport(s: Session, event: ServerEvent.AvatarExport) {
        val output = synchronized(pendingSkinExports) { pendingSkinExports.remove(event.id) } ?: return
        val context = appContext ?: return
        if (!stillSame(s)) return
        if (!event.ok) {
            _skinExports.tryEmit(SkinExportReady("", event.reason.ifBlank { "O servidor não conseguiu exportar a skin." }, false))
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val texture = if (event.texture.isBlank()) ByteArray(0) else android.util.Base64.decode(event.texture, android.util.Base64.DEFAULT)
                    val model = if (event.model.isBlank()) ByteArray(0) else android.util.Base64.decode(event.model, android.util.Base64.DEFAULT)
                    if (texture.isEmpty()) error("textura vazia")
                    context.contentResolver.openOutputStream(output)?.use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("texture.png")); zip.write(texture); zip.closeEntry()
                            if (model.isNotEmpty()) {
                                zip.putNextEntry(ZipEntry("model.json")); zip.write(model); zip.closeEntry()
                            }
                            val report = buildString {
                                appendLine("MoboServer — diagnóstico de skin (exportação explícita)")
                                appendLine("Jogador: ${event.name}")
                                appendLine("Motivo: ${event.reason.ifBlank { "miniatura gerada" }}")
                                appendLine(event.info.summary())
                                appendLine("Conteúdo: texture.png" + if (model.isNotEmpty()) ", model.json" else " (modelo não enviado pelo cliente)")
                                appendLine("Não inclui tokens, ClientData, pacote de login, capa ou animações.")
                            }
                            zip.putNextEntry(ZipEntry("diagnostico.txt")); zip.write(report.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        }
                    } ?: error("não foi possível abrir o destino escolhido")
                    SkinExportReady(output.toString(), "Skin exportada: textura" + if (model.isNotEmpty()) " e modelo JSON." else "; o cliente não enviou modelo JSON.", true)
                } catch (e: Exception) {
                    SkinExportReady("", "Falha ao exportar skin: ${e.message}", false)
                }
            }
            _skinExports.tryEmit(result)
        }
    }

    /** Mediana/mínimo/máximo dos pings válidos, guardando os últimos 60 s. */
    private fun recordPings(history: List<PingSample>, players: List<PlayerInfo>): List<PingSample> {
        val valid = players.map { it.pingMs }.filter { it >= 0 }.sorted()
        val now = System.currentTimeMillis()
        val kept = history.filter { now - it.at <= 60_000L }
        if (valid.isEmpty()) {
            // Sem medição: registra a ausência para a aba Conexão poder dizer
            // "sem amostras" em vez de herdar uma amostra antiga.
            return (kept + PingSample(now, players.size, -1, -1, -1)).takeLast(40)
        }
        val median = if (valid.size % 2 == 1) {
            valid[valid.size / 2]
        } else {
            (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2
        }
        return (kept + PingSample(now, players.size, median, valid.first(), valid.last())).takeLast(40)
    }

    private fun startProbePage(
        s: Session,
        port: Int,
        version: String,
        protocol: Int,
        maxPlayers: Int,
        authEnabled: Boolean,
    ) {
        val lanIp = Net.lanIp()
        val probePort = LanProbe.start(8080) {
            buildString {
                appendLine("Servidor: ${Store.config.value.serverName}")
                appendLine("Endereço no Minecraft (UDP): ${lanIp ?: "IP do celular"}:$port")
                appendLine("Versão: $version (protocolo $protocol)")
                appendLine("Jogadores: ${_state.value.players.size}/$maxPlayers")
                appendLine("Modo: ${if (authEnabled) "exige conta Microsoft" else "aberto (sem login)"}")
            }
        }
        if (probePort <= 0) return
        appendLog(
            "info",
            "Teste de TCP: http://${lanIp ?: "IP do celular"}:$probePort (só confirma a rede, o jogo usa UDP).",
        )
        if (session === s) {
            _state.update { st -> st.copy(info = st.info?.copy(probePort = probePort)) }
        }
    }

    /** Falha sem classificação específica (usada por eventos genéricos). */
    private fun fail(message: String) = fail(
        null,
        StartFailure(problem = StartProblem.OTHER, title = "O servidor parou", message = message),
    )

    /**
     * Registra a falha, encerra o processo e LIBERA a sessão: sem isso o app
     * ficava preso em ERROR e a próxima tentativa não abria um processo novo.
     */
    private fun fail(s: Session?, failure: StartFailure) {
        appendLog("error", failure.message)
        if (failure.technical.isNotBlank()) {
            appendLog("debug", "detalhe (${WorldDiagnosis.code(failure.problem)}): ${failure.technical}")
        }
        if (s != null) {
            s.finished = true
            s.process?.let { process ->
                if (process.isAlive) process.destroy()
            }
            s.startJob?.let { if (it.isActive) it.cancel() }
        }
        if (s == null || session === s) {
            session = null
        }
        _state.update {
            it.copy(
                status = ServerStatus.ERROR,
                failure = failure,
                error = failure.message,
                players = emptyList(),
                avatars = emptyMap(),
                pingHistory = emptyList(),
                info = null,
                startedAt = null,
            )
        }
    }

    /** versionName/versionCode do APK instalado, para o diagnóstico. */
    private fun appVersion(): Pair<String, Long> {
        val context = appContext ?: return "?" to 0L
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            (info.versionName ?: "?") to code
        } catch (e: Exception) {
            "?" to 0L
        }
    }

    /** Linhas de diagnóstico da seleção/início para o relatório compartilhado. */
    fun selectionDiagnostics(): List<String> {
        val context = appContext ?: return listOf("config: contexto indisponível")
        val config = Store.snapshot()
        val repository = WorldRepository(context)
        val facts = if (config.activeWorldId.isBlank()) null else repository.inspect(config.activeWorldId)
        val version = appVersion()
        val failure = _state.value.failure
        return buildList {
            add("Pacote: ${context.packageName} v${version.first} (code ${version.second})")
            add("Seleção: ${config.activeWorldId.ifBlank { "<vazia>" }}")
            add(
                "Pasta: " +
                    if (config.activeWorldId.isBlank()) {
                        "-"
                    } else {
                        repository.directory(config.activeWorldId).absolutePath
                    },
            )
            add(
                "Estado: existe=${facts?.folderExists ?: false} level.dat=${facts?.hasLevelDat ?: false} " +
                    "db=${facts?.dbFiles ?: 0} arquivo(s)",
            )
            add("Mundos listados: ${repository.list().size}")
            if (config.activeWorldId.isNotBlank()) {
                val gen = repository.generationFile(config.activeWorldId)
                add("Política de geração: ${gen.absolutePath} existe=${gen.isFile}")
                add("Origem: ${repository.originFile(config.activeWorldId).absolutePath} existe=${repository.originFile(config.activeWorldId).isFile}")
            }
            _state.value.info?.let { info ->
                add("Servidor fonte: app=${info.appVersion} revision=${info.revision} source=${info.sourceHash}")
                add("Geração efetiva: ${info.generator} engine=${info.generationEngine} versão=${info.generationVersion} origem=${info.origin}")
            }
            add("Sessão: status=${_state.value.status} mundo=${runningWorldId ?: "-"}")
            if (failure != null) {
                add("Última falha: ${WorldDiagnosis.code(failure.problem)} — ${failure.title}")
                if (failure.technical.isNotBlank()) add("Detalhe técnico: ${failure.technical}")
            }
        }
    }

    private fun sendCommand(json: JSONObject): Boolean {
        val process = session?.process ?: return false
        return process.send(json.toString())
    }

    private fun command(name: String): JSONObject = JSONObject().put("cmd", name)

    private fun command(name: String, id: String): JSONObject = command(name).put("id", id)

    private fun appendLog(level: String, message: String) {
        if (message.isBlank()) return
        val line = LogLine(System.currentTimeMillis(), level, message)
        val snapshot: List<LogLine>
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOG_LINES) logs.removeFirst()
            snapshot = logs.toList()
        }
        _state.update { it.copy(logs = snapshot) }
        appendToFile(level, message)
    }

    private fun appendToFile(level: String, message: String) {
        val context = appContext ?: return
        try {
            val file = File(context.filesDir, "server.log")
            if (file.length() > LOG_FILE_MAX_BYTES) file.delete()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("$stamp [$level] $message\n")
        } catch (e: Exception) {
            // logging must never break the server
        }
    }

    /**
     * Android resolves names through netd, not /etc/resolv.conf: a pure Go
     * binary ends up asking [::1]:53 and every HTTPS call fails. The servers
     * below are handed to the child process so it can resolve names again.
     */
    private fun dnsServers(context: Context): String {
        val servers = LinkedHashSet<String>()
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = manager?.activeNetwork
            val properties = network?.let { manager.getLinkProperties(it) }
            properties?.dnsServers?.forEach { address ->
                address.hostAddress?.takeIf { it.isNotBlank() }?.let { servers.add(it) }
            }
        } catch (e: Exception) {
            // sem rede ativa: cai nos servidores publicos abaixo
        }
        if (servers.isEmpty()) {
            servers.add("1.1.1.1")
            servers.add("8.8.8.8")
        }
        return servers.joinToString(",")
    }

    private fun buildArgs(context: Context, config: ServerConfig, worldDir: File, s: Session): List<String> = listOf(
        "-world", worldDir.absolutePath,
        "-world-id", s.worldId,
        "-players", File(context.filesDir, "players").absolutePath,
        "-data", context.filesDir.absolutePath,
        "-bans", File(context.filesDir, "bans.json").absolutePath,
        "-name", config.serverName.ifBlank { "MoboServer" },
        "-port", config.port.toString(),
        "-max", config.maxPlayers.toString(),
        "-auth=${config.authEnabled}",
        "-allow-open=${config.allowOpenMode}",
        "-gamemode", config.gameMode,
        "-difficulty", config.difficulty,
        "-view", config.viewDistance.toString(),
        "-gen", config.generator,
        "-dns", dnsServers(context),
        // Android bloqueia netlink: a lista de interfaces vem das APIs Java
        // (IfaceReporter) e e injetada no agente ICE do Pion.
        "-ifaces", IfaceReporter.path(context).absolutePath,
        // Só abre com mundo importado e válido; o binário recusa o contrário.
        "-require-world",
        // RakNet é opcional e desligado por padrão (aba LAN do Minecraft).
        "-raknet=${config.rakNet}",
        // DEBUG só com "Diagnóstico avançado"; o padrão é INFO.
        "-log-level", if (config.advancedDiagnostics) "debug" else "info",
    ) + worldPolicyArgs(context, s) + tunnelArgs(config)

    private fun worldPolicyArgs(context: Context, s: Session): List<String> {
        val repository = WorldRepository(context)
        val args = ArrayList<String>()
        val origin = repository.originFile(s.worldId)
        if (origin.isFile) {
            args += listOf("-world-origin-file", origin.absolutePath)
        }
        if (s.recovery.isNotBlank()) {
            args += listOf("-generation-recovery", s.recovery)
        }
        return args
    }

    /**
     * Flags do túnel. Os dois modos são separados de propósito:
     *
     *  - `-tunnel` aponta para o controle do MOBOTUN/1 (RakNet, uma porta UDP);
     *  - `-relay-udp-ports` liga o MOBOTUN/2 (uma porta pública por conexão ICE)
     *    e exige `-relay-public-ip`, o IPv4 público anunciado nos candidatos.
     *
     * Sem endereço de relay nada é enviado: o servidor sobe e atende só a LAN.
     */
    private fun tunnelArgs(config: ServerConfig): List<String> {
        val relay = config.relayAddr.trim()
        if (relay.isBlank()) return emptyList()
        val args = mutableListOf(
            "-tunnel", relay,
            "-tunnel-token", config.relayToken.trim(),
            "-tunnel-tls=${config.relayTls}",
        )
        val publicIp = config.relayPublicIp.trim()
        val ports = config.relayUdpPorts
        if (ports > 0 && publicIp.isNotBlank()) {
            args += "-relay-udp-ports=$ports"
            args += "-relay-public-ip=$publicIp"
        }
        return args
    }

    /**
     * Keeps the Go runtime friendly to a phone: a memory cap derived from the
     * app heap limit, few OS threads and a temporary folder inside the app.
     */
    private fun buildEnvironment(context: Context): Map<String, String> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = activityManager?.memoryClass ?: 192
        val memoryLimit = memoryClass.coerceIn(256, 1024)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return mapOf(
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to context.filesDir.absolutePath,
            "GOMEMLIMIT" to "${memoryLimit}MiB",
            "GOMAXPROCS" to cores.coerceAtMost(4).toString(),
            "GOGC" to "80",
        )
    }
}
