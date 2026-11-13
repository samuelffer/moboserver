package com.moboserver.app.core

/** Lifecycle of the embedded Minecraft server process. */
enum class ServerStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** A player that is currently online in the server. */
data class PlayerInfo(
    val name: String,
    val xuid: String = "",
    val device: String = "",
)

/** One line of the server console. [level] is debug, info, warn or error. */
data class LogLine(
    val time: Long,
    val level: String,
    val message: String,
)

/** Static information reported by the server once it is listening. */
data class ServerInfo(
    val port: Int,
    val mcVersion: String,
    val protocol: Int,
    val maxPlayers: Int,
)

/** State of the "can my friends reach this?" checks. */
data class Exposure(
    val lanIp: String? = null,
    val publicIp: String? = null,
    val upnpMessage: String? = null,
    val upnpOk: Boolean = false,
    val working: Boolean = false,
    val externalCheck: String? = null,
)

/** Everything the UI renders, published by [ServerController]. */
data class RuntimeState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val players: List<PlayerInfo> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val info: ServerInfo? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val exposure: Exposure = Exposure(),
)
