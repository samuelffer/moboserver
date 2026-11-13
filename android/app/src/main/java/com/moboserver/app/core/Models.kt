package com.moboserver.app.core

import androidx.compose.ui.graphics.ImageBitmap

/** Lifecycle of the embedded Minecraft server process. */
enum class ServerStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** A player that is currently online in the server. */
data class PlayerInfo(
    val name: String,
    val xuid: String = "",
    val device: String = "",
    /** Endereço visto pelo servidor ("ip:porta"), usado só para o selo LAN. */
    val addr: String = "",
    /**
     * Round-trip medido pelo transporte, em milissegundos. -1 significa que o
     * transporte não expõe medição: a interface mostra "Sem medição" em vez de
     * inventar um número.
     */
    val pingMs: Long = -1,
    /** Transporte que forneceu a medição ("" quando o servidor não sabe). */
    val transport: String = "",
) {
    /** Chave estável no app: XUID quando existe, nome caso contrário. */
    val key: String get() = if (xuid.isNotBlank()) xuid else name.lowercase()
}

/**
 * Miniatura da cabeça do jogador, montada pelo servidor a partir da skin da
 * sessão. [image] fica nula quando a skin não pôde ser lida (Persona,
 * geometria personalizada, textura inconsistente); [reason] explica o motivo.
 */
data class PlayerAvatar(
    val hash: String,
    val image: ImageBitmap?,
    val layout: String = "",
    val reason: String = "",
) {
    val available: Boolean get() = image != null
}

/** Uma amostra da latência dos jogadores, para a aba Conexão. */
data class PingSample(
    val at: Long,
    val players: Int,
    val median: Long,
    val min: Long,
    val max: Long,
)

/** One line of the server console. [level] is debug, info, warn or error. */
data class LogLine(
    val time: Long,
    val level: String,
    val message: String,
)

/** Static information reported by the server once it is listening. */
/** One address the server answered a ping on, seen from the device itself. */
data class LanTarget(val iface: String, val ip: String, val ok: Boolean)

data class ServerInfo(
    val port: Int,
    val mcVersion: String,
    val protocol: Int,
    val maxPlayers: Int,
    val authEnabled: Boolean = true,
    val lanTargets: List<LanTarget> = emptyList(),
    /** Transportes que subiram de fato: "nethernet", "raknet". */
    val transports: List<String> = emptyList(),
    val signalingLatencyMs: Long = -1,
    val probePort: Int = 0,
    // Etapas da entrada por endereco, verificadas pelo processo Go.
    val raknetOk: Boolean = false,
    val signalingOk: Boolean = false,
    val signalingName: String = "",
    val iceOk: Boolean = false,
    val iceCandidates: Int = 0,
    val iceHosts: List<String> = emptyList(),
    val iceInterfaceCount: Int = 0,
    val iceError: String = "",
)

/**
 * State of the "can my friends reach this?" checks.
 *
 * The two addresses are kept apart on purpose: [wanIp] is what the router
 * reports about itself (UPnP), [publicIp] is what an outside server observes
 * through STUN. They disagree when there is another NAT in front of the router.
 * [upnpOk] only means "the router accepted the rule"; it says nothing about
 * whether traffic actually arrives, which is [externalVerified].
 */
data class Exposure(
    val lanIp: String? = null,
    val wanIp: String? = null,
    val publicIp: String? = null,
    val upnpMessage: String? = null,
    val upnpOk: Boolean = false,
    val working: Boolean = false,
    val externalCheck: String? = null,
    /** null = never checked, true = reached from outside, false = not reached. */
    val externalVerified: Boolean? = null,
    /** Tunnel state reported by the server: connecting, connected, reconnecting. */
    val tunnelState: String? = null,
    /** "host:porta" handed out by the relay, when connected. */
    val tunnelAddress: String? = null,
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
    /** Name of the world the current/last session was started with. */
    val worldName: String? = null,
    /** Miniaturas por jogador (chave = [PlayerInfo.key]). */
    val avatars: Map<String, PlayerAvatar> = emptyMap(),
    /** Latência agregada dos últimos 60 s, uma amostra a cada atualização. */
    val pingHistory: List<PingSample> = emptyList(),
)
