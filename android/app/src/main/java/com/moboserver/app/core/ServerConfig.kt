package com.moboserver.app.core

/**
 * Configuração do servidor escolhida no app. É persistida como JSON em
 * SharedPreferences para que o processo do servidor sempre suba com exatamente
 * o que o usuário configurou.
 *
 * Este arquivo é Kotlin puro (sem Android) de propósito: a mesclagem de campos e
 * a seleção de mundo são testadas na JVM, sem aparelho.
 */
data class ServerConfig(
    val serverName: String = "MoboServer",
    val port: Int = 19132,
    val maxPlayers: Int = 4,
    val authEnabled: Boolean = true,
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val viewDistance: Int = 8,
    val generator: String = "terrain",
    val autoForward: Boolean = true,
    val activeWorldId: String = "",
    /** Relay "host:porta" do túnel UDP. Vazio = túnel desligado. */
    val relayAddr: String = "",
    /** Token aceito pelo relay. */
    val relayToken: String = "",
    /** Usa TLS na conexão de controle do túnel. */
    val relayTls: Boolean = true,
    /** Aceita subir sem verificação de conta quando o login Xbox falha. */
    val allowOpenMode: Boolean = false,
    /**
     * Liga o transporte legado RakNet (UDP + descoberta na aba LAN). Fica
     * desligado por padrão: a entrada por NetherNet é a que foi validada com o
     * Minecraft 26.63 e a aba LAN é o único recurso que depende do RakNet.
     */
    val rakNet: Boolean = false,
    /**
     * "Diagnóstico avançado": libera as linhas DEBUG no console. Sem ele o
     * console mostra INFO, avisos e erros apenas.
     */
    val advancedDiagnostics: Boolean = false,
    /** Domínio próprio (ex.: casa.exemplo.com) que aponta para o endereço público. */
    val externalHost: String = "",
) {
    /** Chave curta usada no log de diagnóstico e nos testes. */
    val worldKey: String get() = if (activeWorldId.isBlank()) "<vazio>" else activeWorldId
}
