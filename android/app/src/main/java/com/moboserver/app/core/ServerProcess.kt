package com.moboserver.app.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Events emitted by the embedded server process on its stdout. */
sealed class ServerEvent {
    data class Ready(
        val port: Int,
        val version: String,
        val protocol: Int,
        val maxPlayers: Int,
        val authEnabled: Boolean,
        // Etapas verificadas separadamente pelo servidor: abrir o listener,
        // responder a sinalizacao e negociar ICE sao coisas diferentes.
        val raknetOk: Boolean = false,
        val signalingOk: Boolean = false,
        val signalingName: String = "",
        val signalingProtocol: Int = 0,
        val iceOk: Boolean = false,
        val iceCandidates: Int = 0,
        val iceHosts: List<String> = emptyList(),
        val iceInterfaceCount: Int = 0,
        val iceError: String = "",
    ) : ServerEvent()
    data class Log(val level: String, val message: String) : ServerEvent()
    data class Lan(val targets: List<LanTarget>) : ServerEvent()
    data class Tunnel(val state: String, val detail: String, val address: String, val port: Int) : ServerEvent()
    data class Join(val player: PlayerInfo) : ServerEvent()
    data class Quit(val name: String) : ServerEvent()
    data class Players(val players: List<PlayerInfo>) : ServerEvent()
    data class Fatal(val message: String) : ServerEvent()
    data object Stopped : ServerEvent()
    data class Exited(val code: Int) : ServerEvent()
}

/**
 * Runs the embedded MoboServer binary (written in Go, shipped inside the APK as
 * a native library so Android allows executing it) and speaks a line based JSON
 * protocol with it:
 *
 *  - server -> app : one JSON object per line on stdout
 *  - app -> server : one JSON command per line on stdin
 */
class ServerProcess(
    private val binary: File,
    private val workDir: File,
    private val args: List<String>,
    private val environment: Map<String, String>,
    private val listener: (ServerEvent) -> Unit,
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val alive = AtomicBoolean(false)
    private val writeLock = Any()

    val isAlive: Boolean get() = alive.get()

    @Throws(IOException::class)
    fun start() {
        val command = ArrayList<String>(args.size + 1)
        command.add(binary.absolutePath)
        command.addAll(args)

        val builder = ProcessBuilder(command)
        builder.directory(workDir)
        builder.redirectErrorStream(true)
        for ((key, value) in environment) {
            builder.environment()[key] = value
        }

        val p = builder.start()
        process = p
        alive.set(true)
        writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)

        Thread({ readLoop(p) }, "moboserver-out").apply { isDaemon = true }.start()
    }

    private fun readLoop(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    dispatch(line)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "leitura do servidor encerrada: ${e.message}")
        } finally {
            val code = try {
                p.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
            alive.set(false)
            listener(ServerEvent.Exited(code))
        }
    }

    private fun dispatch(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return

        val obj = try {
            if (line.startsWith("{")) JSONObject(line) else null
        } catch (e: Exception) {
            null
        }
        if (obj == null) {
            listener(ServerEvent.Log("info", line))
            return
        }

        when (obj.optString("t")) {
            "log" -> listener(ServerEvent.Log(obj.optString("lvl", "info"), obj.optString("msg")))
            "ready" -> {
                val signaling = obj.optJSONObject("signaling")
                val ice = obj.optJSONObject("ice")
                val hosts = ArrayList<String>()
                ice?.optJSONArray("hosts")?.let { array ->
                    for (index in 0 until array.length()) {
                        val host = array.optString(index)
                        if (host.isNotBlank()) hosts.add(host)
                    }
                }
                val interfaces = ArrayList<String>()
                ice?.optJSONArray("interfaces")?.let { array ->
                    for (index in 0 until array.length()) {
                        val entry = array.optString(index)
                        if (entry.isNotBlank()) interfaces.add(entry)
                    }
                }
                listener(
                    ServerEvent.Ready(
                        port = obj.optInt("port"),
                        version = obj.optString("version"),
                        protocol = obj.optInt("protocol"),
                        maxPlayers = obj.optInt("max"),
                        authEnabled = obj.optBoolean("auth", true),
                        raknetOk = obj.optBoolean("raknet", false),
                        signalingOk = (signaling?.optInt("status") ?: 0) == 200,
                        signalingName = signaling?.optString("name").orEmpty(),
                        signalingProtocol = signaling?.optInt("protocol") ?: 0,
                        iceOk = ice?.optBoolean("ok", false) ?: false,
                        iceCandidates = ice?.optInt("candidates") ?: 0,
                        iceHosts = hosts,
                        iceInterfaceCount = interfaces.size,
                        iceError = ice?.optString("error").orEmpty(),
                    ),
                )
            }
            "lan" -> {
                val array = obj.optJSONArray("targets")
                val targets = ArrayList<LanTarget>()
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        targets.add(
                            LanTarget(
                                iface = item.optString("iface"),
                                ip = item.optString("ip"),
                                ok = item.optBoolean("ok"),
                            ),
                        )
                    }
                }
                listener(ServerEvent.Lan(targets))
            }
            "tunnel" -> listener(
                ServerEvent.Tunnel(
                    state = obj.optString("state"),
                    detail = obj.optString("detail"),
                    address = obj.optString("address"),
                    port = obj.optInt("port"),
                ),
            )
            "join" -> listener(
                ServerEvent.Join(
                    PlayerInfo(
                        name = obj.optString("name"),
                        xuid = obj.optString("xuid"),
                        device = obj.optString("device"),
                    )
                )
            )
            "quit" -> listener(ServerEvent.Quit(obj.optString("name")))
            "players" -> listener(ServerEvent.Players(readPlayers(obj)))
            "fatal" -> listener(ServerEvent.Fatal(obj.optString("msg")))
            "stopped" -> listener(ServerEvent.Stopped)
            else -> listener(ServerEvent.Log("debug", line))
        }
    }

    private fun readPlayers(obj: JSONObject): List<PlayerInfo> {
        val array = obj.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<PlayerInfo>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            list.add(
                PlayerInfo(
                    name = item.optString("name"),
                    xuid = item.optString("xuid"),
                    device = item.optString("device"),
                )
            )
        }
        return list
    }

    /** Sends one JSON command. Returns false when the process is gone. */
    fun send(json: String): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(writeLock) {
                w.write(json)
                w.write("\n")
                w.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível enviar comando: ${e.message}")
            false
        }
    }

    fun destroy() {
        val p = process ?: return
        try {
            p.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy: ${e.message}")
        }
        try {
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        } catch (e: Exception) {
            p.destroyForcibly()
        }
        alive.set(false)
    }

    companion object {
        private const val TAG = "MoboServer"
        const val BINARY_NAME = "libmobosrv.so"

        /**
         * Android refuses to execute files in the app's writable data folder
         * (W^X), but the native library folder is mounted executable. The Go
         * binary is therefore shipped as a native library and started from
         * there. If it is missing we extract a copy from the APK as a fallback.
         */
        fun resolveBinary(context: Context): File {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            if (!nativeDir.isNullOrBlank()) {
                val inLib = File(nativeDir, BINARY_NAME)
                if (inLib.isFile) return inLib
            }

            val fallback = File(context.filesDir, "bin/moboserver")
            if (fallback.isFile && fallback.length() > 0) return fallback

            fallback.parentFile?.mkdirs()
            val apk = File(context.applicationInfo.sourceDir)
            ZipFile(apk).use { zip ->
                val entry = Collections.list(zip.entries()).firstOrNull {
                    it.name == BINARY_NAME || it.name.endsWith("/$BINARY_NAME")
                } ?: throw IOException("O servidor embutido não foi encontrado no aplicativo")
                zip.getInputStream(entry).use { input ->
                    fallback.outputStream().use { output -> input.copyTo(output) }
                }
            }
            fallback.setExecutable(true)
            return fallback
        }
    }
}
