package com.moboserver.app.core

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** One run of the server process, from "prepare" until the process exits. */
    private class Session(val id: Long) {
        @Volatile var process: ServerProcess? = null
        @Volatile var stopRequested = false
        @Volatile var finished = false
        var startJob: Job? = null
        var stopJob: Job? = null
    }

    private var appContext: Context? = null
    private var session: Session? = null
    private var nextSessionId = 0L
    private val logs = ArrayDeque<LogLine>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            Store.init(context)
        }
    }

    fun worlds(): WorldRepository? = appContext?.let { WorldRepository(it) }

    val isBusy: Boolean
        get() = session?.let { !it.finished } == true

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
                    fail(
                        "Não foi possível preparar o servidor neste aparelho.\n\n" +
                            "Detalhe: ${e.message}\n\n" +
                            "Abra Ajustes → Diagnóstico e use \"Testar servidor embutido\".",
                    )
                }
            }
        }
        return true
    }

    private fun isCurrent(s: Session): Boolean = session === s && !s.finished && !s.stopRequested

    private fun startInternal(context: Context, s: Session) {
        val config = Store.config.value
        val repository = WorldRepository(context)

        var worldId = config.activeWorldId
        if (worldId.isBlank() || !repository.exists(worldId)) {
            val created = try {
                repository.create("Mundo principal")
            } catch (e: Exception) {
                if (isCurrent(s)) fail("Não foi possível criar o mundo: ${e.message}")
                return
            }
            worldId = created.id
            Store.update { it.copy(activeWorldId = worldId) }
            appendLog("info", "Novo mundo criado: ${created.name}")
        }
        if (!isCurrent(s)) return

        val worldDir = repository.directory(worldId)
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            if (isCurrent(s)) fail("Não foi possível preparar a pasta do mundo")
            return
        }
        if (!isCurrent(s)) return
        val worldName = repository.nameOf(worldId)
        _state.update { it.copy(worldName = worldName) }

        appendLog("info", "Iniciando servidor \"${config.serverName}\" na porta UDP ${config.port}")
        val binary = try {
            ServerProcess.resolveBinary(context)
        } catch (e: Exception) {
            if (isCurrent(s)) fail("O servidor embutido não foi encontrado no aplicativo: ${e.message}")
            return
        }
        // Last checkpoint: stopping while the binary is being prepared must not
        // spawn a process after the app already reported "desligado".
        if (!isCurrent(s)) return

        val process = ServerProcess(
            binary = binary,
            workDir = context.filesDir,
            args = buildArgs(context, config, worldDir),
            environment = buildEnvironment(context),
            listener = { event -> onEvent(s, event) },
        )
        s.process = process
        try {
            process.start()
        } catch (e: Exception) {
            Log.e(TAG, "failed to start server", e)
            if (isCurrent(s)) {
                fail(
                    "Não foi possível executar o servidor neste aparelho.\n\n" +
                        "Detalhe: ${e.message}\n\n" +
                        "Abra Ajustes → Diagnóstico e use \"Testar servidor embutido\" para ver o motivo exato.",
                )
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
        appendLog("info", "Desligando o servidor e salvando o mundo...")

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

    fun say(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        sendCommand(command("say").put("msg", text))
    }

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
                    ),
                )
            }
            appendLog("info", describeInternet(Net.lanIp(), _state.value.exposure))
        }
    }

    /** Asks a public API whether the server is reachable from the internet. */
    fun checkExternalAccess() {
        val owner = session
        val exposure = _state.value.exposure
        val port = _state.value.info?.port ?: Store.config.value.port
        val target = exposure.publicIp ?: exposure.wanIp
        if (target.isNullOrBlank()) {
            _state.update { st ->
                st.copy(
                    exposure = st.exposure.copy(
                        externalVerified = false,
                        externalCheck = "Sem endereço público conhecido: não deu para testar.",
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
        exposure.wanIp?.let { lines.add("Endereço informado pelo roteador (UPnP): $it") }
        exposure.publicIp?.let { lines.add("Endereço visto por um servidor STUN: $it") }
        if (exposure.wanIp != null && exposure.publicIp != null && exposure.wanIp != exposure.publicIp) {
            lines.add(
                "O roteador e o STUN discordam: provavelmente há outro NAT entre o roteador e a internet.",
            )
        }
        exposure.publicIp?.let { ip ->
            if (Net.isPrivateIp(ip)) {
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
                    "O servidor não terminou de iniciar em 60 segundos. Abra o Console para ver a última " +
                        "mensagem do servidor e tente novamente.",
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
                        ),
                        error = null,
                    )
                }
                appendLog("info", "Servidor iniciado no celular • Minecraft Bedrock ${event.version} • porta ${event.port}")
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

            is ServerEvent.Players -> _state.update { it.copy(players = event.players) }

            is ServerEvent.Fatal -> fail(event.message)

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
                        appendLog("info", "Servidor desligado.")
                    }
                    status == ServerStatus.ERROR -> Unit
                    else -> fail(
                        "O servidor foi encerrado inesperadamente (código ${event.code}). " +
                            "Veja o console para descobrir o motivo.",
                    )
                }
            }
        }
    }

    /**
     * Serves a tiny status page so another device can prove the TCP path works.
     * It says nothing about UDP, which is what Minecraft uses.
     */
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

    private fun fail(message: String) {
        appendLog("error", message)
        _state.update {
            it.copy(
                status = ServerStatus.ERROR,
                error = message,
                players = emptyList(),
                info = null,
                startedAt = null,
            )
        }
    }

    private fun sendCommand(json: JSONObject): Boolean {
        val process = session?.process ?: return false
        return process.send(json.toString())
    }

    private fun command(name: String): JSONObject = JSONObject().put("cmd", name)

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

    private fun buildArgs(context: Context, config: ServerConfig, worldDir: File): List<String> = listOf(
        "-world", worldDir.absolutePath,
        "-players", File(context.filesDir, "players").absolutePath,
        "-data", context.filesDir.absolutePath,
        "-bans", File(context.filesDir, "bans.json").absolutePath,
        "-name", config.serverName.ifBlank { "MoboServer" },
        "-port", config.port.toString(),
        "-max", config.maxPlayers.toString(),
        "-auth=${config.authEnabled}",
        "-gamemode", config.gameMode,
        "-difficulty", config.difficulty,
        "-view", config.viewDistance.toString(),
        "-gen", config.generator,
        "-dns", dnsServers(context),
    )

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
