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
     * inventar um número. Zero é medição válida (abaixo de 1 ms) e não pode ser
     * confundido com ausência.
     */
    val pingMs: Long = -1,
    /** Transporte que forneceu a medição ("" quando o servidor não sabe). */
    val transport: String = "",
) {
    /** Chave estável no app: XUID quando existe, nome caso contrário. */
    val key: String get() = if (xuid.isNotBlank()) xuid else name.lowercase()
}

/** Estado explícito da miniatura de um jogador (nunca um traço sem explicação). */
enum class AvatarState {
    /** Evento de avatar ainda não chegou para este jogador. */
    LOADING,

    /** PNG decodificado e publicado. */
    READY,

    /** Formato que esta versão não sabe recortar (Persona, geometria própria). */
    UNSUPPORTED,

    /** O formato deveria funcionar, mas o evento/PNG/hash falhou. */
    FAILED,
}

/**
 * O que o servidor informou sobre a skin, sem PNG/base64: dimensões, tamanho dos
 * pixels, geometria resolvida e resultado da renderização.
 */
data class AvatarInfo(
    val persona: Boolean = false,
    val textureWidth: Int = 0,
    val textureHeight: Int = 0,
    val pixelBytes: Int = 0,
    val modelBytes: Int = 0,
    val geometry: String = "",
    val headCube: Boolean = false,
    val rendered: Boolean = false,
) {
    fun summary(): String = listOfNotNull(
        if (textureWidth > 0) "${textureWidth}x$textureHeight" else null,
        if (pixelBytes > 0) "$pixelBytes B de pixel" else null,
        if (modelBytes > 0) "modelo $modelBytes B" else null,
        geometry.takeIf { it.isNotBlank() },
        "Persona".takeIf { persona },
        "cubo de cabeça".takeIf { headCube },
    ).joinToString(" • ")
}

/**
 * Miniatura da cabeça do jogador, montada pelo servidor a partir da skin da
 * sessão. [image] fica nula quando a skin não pôde ser lida; [state] e [reason]
 * explicam exatamente o que aconteceu.
 */
data class PlayerAvatar(
    val hash: String = "",
    val image: ImageBitmap? = null,
    val layout: String = "",
    val reason: String = "",
    val state: AvatarState = AvatarState.LOADING,
    val info: AvatarInfo = AvatarInfo(),
    /** Revisão do servidor: imagem antiga não substitui uma skin mais nova. */
    val rev: Long = 0,
) {
    val available: Boolean get() = image != null
}

/** Uma mensagem de chat do jogo (recebida ou enviada pelo painel). */
data class ChatMessage(
    val id: Long,
    val at: Long,
    val from: String,
    val text: String,
    /** true quando a mensagem partiu do painel do app. */
    val fromPanel: Boolean = false,
    /** PENDING, SENT ou FAILED. */
    val status: String = ChatStatus.SENT,
) {
    val isPlayer: Boolean get() = !fromPanel && from.isNotBlank()
}

object ChatStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"
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
    /**
     * Falha classificada do último início (título + mensagem + detalhe técnico
     * separado). A interface mostra título/mensagem; o detalhe vai só para o
     * diagnóstico.
     */
    val failure: StartFailure? = null,
    val startedAt: Long? = null,
    val exposure: Exposure = Exposure(),
    /** Name of the world the current/last session was started with. */
    val worldName: String? = null,
    /** true enquanto a aba Mundos importa/valida um .mcworld. */
    val importing: Boolean = false,
    /** Miniaturas por jogador (chave = [PlayerInfo.key]). */
    val avatars: Map<String, PlayerAvatar> = emptyMap(),
    /** Latência agregada dos últimos 60 s, uma amostra a cada atualização. */
    val pingHistory: List<PingSample> = emptyList(),
    /** Chat estruturado, do mais antigo para o mais novo (limite na UI). */
    val chat: List<ChatMessage> = emptyList(),
    /** Último salvamento concluído confirmado pelo servidor (0 = desconhecido). */
    val lastSaveAt: Long = 0L,
)

/** Endereço sem a porta, aceitando "[::1]:19132" e "192.168.0.10:54535". */
fun ipOf(addr: String): String {
    val trimmed = addr.trim()
    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf(']')
        if (close > 0) return trimmed.substring(1, close)
    }
    val colon = trimmed.lastIndexOf(':')
    if (colon > 0 && trimmed.count { it == ':' } == 1) return trimmed.substring(0, colon)
    return trimmed
}

/**
 * Diz se dois endereços estão na mesma sub-rede /24. Não existe campo de
 * transporte no protocolo do servidor, então o selo "LAN" da interface usa esta
 * comparação com os endereços locais do aparelho: é uma inferência, não uma
 * informação enviada pelo jogo.
 */
fun sameSubnet(a: String, b: String): Boolean {
    val ipA = ipOf(a).split('.')
    val ipB = ipOf(b).split('.')
    if (ipA.size != 4 || ipB.size != 4) return false
    return ipA[0] == ipB[0] && ipA[1] == ipB[1] && ipA[2] == ipB[2]
}
