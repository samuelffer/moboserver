package com.moboserver.app.core

/**
 * Regras puras de mesclagem da configuração.
 *
 * O app grava a configuração de vários lugares (Mundos, Ajustes, exposição de
 * rede). Se cada tela gravar o seu próprio snapshot inteiro, uma tela com dados
 * antigos apaga o que outra acabou de escolher — foi assim que a seleção de
 * mundo podia virar vazio sozinha. Aqui cada escrita diz exatamente quais
 * campos ela controla:
 *
 *  - [applySettings] pega do rascunho apenas os campos editáveis em Ajustes e
 *    mantém a seleção de mundo (e o gerador legado) do estado mais recente;
 *  - [selectWorld] e [clearWorldIf] são as únicas formas de mexer na seleção.
 */
object ConfigMerge {

    /** Campos que a tela de Ajustes controla. */
    fun applySettings(current: ServerConfig, draft: ServerConfig): ServerConfig = current.copy(
        serverName = draft.serverName,
        port = draft.port,
        maxPlayers = draft.maxPlayers,
        authEnabled = draft.authEnabled,
        gameMode = draft.gameMode,
        difficulty = draft.difficulty,
        viewDistance = draft.viewDistance,
        autoForward = draft.autoForward,
        relayAddr = draft.relayAddr,
        relayToken = draft.relayToken,
        relayTls = draft.relayTls,
        relayPublicIp = draft.relayPublicIp,
        relayUdpPorts = draft.relayUdpPorts,
        allowOpenMode = draft.allowOpenMode,
        rakNet = draft.rakNet,
        advancedDiagnostics = draft.advancedDiagnostics,
        externalHost = draft.externalHost,
        // activeWorldId e generator NÃO vêm do rascunho: são de outras telas e
        // um rascunho antigo não pode desfazer uma seleção recente.
    )

    /** Troca a seleção, sem tocar em nenhum outro campo. */
    fun selectWorld(current: ServerConfig, id: String): ServerConfig =
        current.copy(activeWorldId = id.trim())

    /**
     * Limpa a seleção somente se ela ainda apontar para [id]. É o que a exclusão
     * precisa: se o usuário selecionou outro mundo enquanto a remoção rodava, a
     * seleção nova é preservada.
     */
    fun clearWorldIf(current: ServerConfig, id: String): ServerConfig =
        if (current.activeWorldId == id) current.copy(activeWorldId = "") else current
}
