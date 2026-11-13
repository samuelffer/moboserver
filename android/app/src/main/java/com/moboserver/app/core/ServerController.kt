package com.moboserver.app.core

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 */
object ServerController {
    private const val TAG = "MoboServer"
    private const val MAX_LOG_LINES = 1200
    private const val LOG_FILE_MAX_BYTES = 4L * 1024 * 1024

    private var appContext: Context? = null
    private var serverProcess: ServerProcess? = null
    private var stopRequested = false
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
        get() = _state.value.status == ServerStatus.STARTING || _state.value.status == ServerStatus.RUNNING ||
            _state.value.status == ServerStatus.STOPPING

    /** Starts the server with the settings currently stored in [Store]. */
    @Synchronized
    fun start(): Boolean {
        val context = appContext ?: return false
        if (isBusy) return true

        val config = Store.config.value
        val repository = WorldRepository(context)

        var worldId = config.activeWorldId
        if (worldId.isBlank() || !repository.exists(worldId)) {
            val created = try {
                repository.create("Mundo principal")
            } catch (e: Exception) {
                fail("Não foi possível criar o mundo: ${e.message}")
                return false
            }
            worldId = created.id
            Store.update { it.copy(activeWorldId = worldId) }
            appendLog("info", "Novo mundo criado: ${created.name}")
        }

        val worldDir = repository.directory(worldId)
        if (!worldDir.exists() && !worldDir.mkdirs()) {
            fail("Não foi possível preparar a pasta do mundo")
            return false
        }

        synchronized(logs) { logs.clear() }
        stopRequested = false
        _state.value = RuntimeState(status = ServerStatus.STARTING)
        appendLog("info", "Iniciando servidor \"${config.serverName}\" na porta UDP ${config.port}")

        val binary = try {
            ServerProcess.resolveBinary(context)
        } catch (e: Exception) {
            fail("O servidor embutido não foi encontrado no aplicativo: ${e.message}")
            return false
        }

        val process = ServerProcess(
            binary = binary,
            workDir = context.filesDir,
            args = buildArgs(context, config, worldDir),
            environment = buildEnvironment(context),
            listener = ::onEvent,
        )
        serverProcess = process

        return try {
            process.start()
            watchStartup(process)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start server", e)
            fail(
                "Não foi possível executar o servidor neste aparelho (${e.message}). " +
                    "Isso pode acontecer em ROMs muito restritivas.",
            )
            serverProcess = null
            false
        }
    }

    /** Asks the server to shut down gracefully, saving the world. */
    @Synchronized
    fun stop() {
        val process = serverProcess
        if (process == null) {
            _state.value = _state.value.copy(
                status = ServerStatus.STOPPED,
                players = emptyList(),
                info = null,
                startedAt = null,
            )
            return
        }
        if (stopRequested) return
        stopRequested = true
        _state.value = _state.value.copy(status = ServerStatus.STOPPING)
        appendLog("info", "Desligando o servidor e salvando o mundo...")

        val sent = process.send(command("stop"))
        scope.launch {
            delay(2_000)
            if (!sent) process.destroy()
            delay(18_000)
            if (process.isAlive) {
                appendLog("warn", "O servidor não respondeu a tempo, encerrando à força.")
                process.destroy()
            }
            if (_state.value.status == ServerStatus.STOPPING) {
                serverProcess = null
                stopRequested = false
                _state.value = _state.value.copy(
                    status = ServerStatus.STOPPED,
                    players = emptyList(),
                    info = null,
                    startedAt = null,
                )
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

    fun saveWorld() {
        sendCommand(command("save"))
    }

    fun requestPlayers() {
        sendCommand(command("players"))
    }

    fun clearLogs() {
        synchronized(logs) { logs.clear() }
        _state.value = _state.value.copy(logs = emptyList())
    }

    fun logText(): String {
        val snapshot = synchronized(logs) { logs.toList() }
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return snapshot.joinToString("\n") { "${format.format(Date(it.time))} [${it.level}] ${it.message}" }
    }

    /** Detects the network situation and (optionally) forwards the port. */
    fun refreshExposure(forward: Boolean) {
        val context = appContext ?: return
        if (_state.value.exposure.working) return
        scope.launch {
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(
                    working = true,
                    upnpMessage = if (forward) "Conversando com o roteador..." else _state.value.exposure.upnpMessage,
                ),
            )
            val lanIp = Net.lanIp()
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(lanIp = lanIp),
            )

            if (forward && !lanIp.isNullOrBlank()) {
                val port = _state.value.info?.port ?: Store.config.value.port
                val result = Upnp.forward(context, port, lanIp)
                appendLog(if (result.ok) "info" else "warn", result.message)
                _state.value = _state.value.copy(
                    exposure = _state.value.exposure.copy(
                        upnpOk = result.ok,
                        upnpMessage = result.message,
                        publicIp = result.externalIp ?: _state.value.exposure.publicIp,
                    ),
                )
            }

            val publicIp = _state.value.exposure.publicIp ?: Net.publicIp()
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(working = false, publicIp = publicIp),
            )
        }
    }

    /** Asks a public API whether the server is reachable from the internet. */
    fun checkExternalAccess() {
        val ip = _state.value.exposure.publicIp
        val port = _state.value.info?.port ?: Store.config.value.port
        if (ip.isNullOrBlank()) {
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(
                    externalCheck = "Não consegui descobrir o IP público. Confira a internet do celular.",
                ),
            )
            return
        }
        if (_state.value.exposure.working) return
        scope.launch {
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(working = true, externalCheck = "Verificando..."),
            )
            val result = Net.checkBedrockReachable(ip, port)
            appendLog("info", "Teste de acesso externo: $result")
            _state.value = _state.value.copy(
                exposure = _state.value.exposure.copy(working = false, externalCheck = result),
            )
        }
    }

    // ---------------------------------------------------------------- internal

    private fun watchStartup(process: ServerProcess) {
        scope.launch {
            delay(60_000)
            if (_state.value.status == ServerStatus.STARTING) {
                fail(
                    "O servidor não terminou de iniciar. Verifique se o celular está conectado à internet " +
                        "(necessário para validar o login do Xbox) e tentque novamente.",
                )
                process.destroy()
            }
        }
    }

    private fun onEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Ready -> {
                _state.value = _state.value.copy(
                    status = ServerStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                    info = ServerInfo(event.port, event.version, event.protocol, event.maxPlayers),
                    error = null,
                )
                appendLog("info", "Servidor online! Minecraft Bedrock ${event.version} • porta ${event.port}")
                refreshExposure(forward = Store.config.value.autoForward)
            }

            is ServerEvent.Log -> appendLog(event.level, event.message)

            is ServerEvent.Join -> {
                val players = _state.value.players.toMutableList()
                if (players.none { it.name.equals(event.player.name, ignoreCase = true) }) {
                    players.add(event.player)
                }
                _state.value = _state.value.copy(players = players)
            }

            is ServerEvent.Quit -> {
                _state.value = _state.value.copy(
                    players = _state.value.players.filterNot { it.name.equals(event.name, ignoreCase = true) },
                )
            }

            is ServerEvent.Players -> {
                _state.value = _state.value.copy(players = event.players)
            }

            is ServerEvent.Fatal -> fail(event.message)

            ServerEvent.Stopped -> {
                serverProcess = null
                stopRequested = false
                _state.value = _state.value.copy(
                    status = ServerStatus.STOPPED,
                    players = emptyList(),
                    info = null,
                    startedAt = null,
                )
                appendLog("info", "Servidor desligado.")
            }

            is ServerEvent.Exited -> {
                serverProcess = null
                if (stopRequested) {
                    stopRequested = false
                    if (_state.value.status == ServerStatus.STOPPING) {
                        _state.value = _state.value.copy(
                            status = ServerStatus.STOPPED,
                            players = emptyList(),
                            info = null,
                            startedAt = null,
                        )
                    }
                } else if (_state.value.status != ServerStatus.ERROR) {
                    fail(
                        "O servidor foi encerrado inesperadamente (código ${event.code}). " +
                            "Veja o console para descobrir o motivo.",
                    )
                }
            }
        }
    }

    private fun fail(message: String) {
        appendLog("error", message)
        _state.value = _state.value.copy(
            status = ServerStatus.ERROR,
            error = message,
            players = emptyList(),
            info = null,
            startedAt = null,
        )
    }

    private fun sendCommand(json: JSONObject): Boolean {
        val process = serverProcess ?: return false
        return process.send(json.toString())
    }

    private fun command(name: String): JSONObject = JSONObject().put("cmd", name)

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
    )

    /**
     * Keeps the Go runtime friendly to a phone: a memory cap derived from the
     * app heap limit, few OS threads and a temporary folder inside the app.
     */
    private fun buildEnvironment(context: Context): Map<String, String> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = activityManager?.memoryClass ?: 192
        val memoryLimit = (memoryClass / 2).coerceIn(192, 1024)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return mapOf(
            "TMPDIR" to context.cacheDir.absolutePath,
            "HOME" to context.filesDir.absolutePath,
            "GOMEMLIMIT" to "${memoryLimit}MiB",
            "GOMAXPROCS" to cores.coerceAtMost(4).toString(),
            "GOGC" to "80",
        )
    }

    private fun appendLog(level: String, message: String) {
        if (message.isBlank()) return
        val line = LogLine(System.currentTimeMillis(), level, message)
        val snapshot: List<LogLine>
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOG_LINES) logs.removeFirst()
            snapshot = logs.toList()
        }
        _state.value = _state.value.copy(logs = snapshot)
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
}
