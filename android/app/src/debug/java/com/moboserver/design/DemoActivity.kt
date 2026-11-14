package com.moboserver.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

/**
 * Tela de demonstração do kit visual, com dados fictícios.
 * Existe apenas em builds de depuração (src/debug) e não inicia servidor.
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(false) }
            var data by remember {
                mutableStateOf(
                    PanelUi(
                        selectedWorldId = "demo1",
                        worlds = listOf(
                            WorldUi("demo1", "Meu mundo novo", "45 KB"),
                            WorldUi("demo2", "TesteServer", "147 KB • em execução"),
                        ),
                        lanAddress = "192.168.1.106",
                        internetAddress = "45.160.85.157:19132",
                        internetKind = "observado",
                        internetStatus = "Acesso não verificado",
                        maxPlayers = 4,
                        uptime = "00:12:04",
                        lastSave = "19:15:34",
                        gameMode = "Sobrevivência",
                        difficulty = "Normal",
                        logLines = listOf(
                            LogUi(1, System.currentTimeMillis() - 4000, "info", "Servidor iniciado na porta 19132"),
                            LogUi(2, System.currentTimeMillis() - 3000, "warn", "Nenhum candidato ICE fora da rede local"),
                            LogUi(3, System.currentTimeMillis() - 2000, "info", "Neescao entrou no servidor (1/4)"),
                        ),
                        chatMessages = listOf(
                            ChatUi(1, System.currentTimeMillis() - 1500, "Neescao", "oi, entrou o servidor?"),
                            ChatUi(2, System.currentTimeMillis() - 900, "Painel", "entrou sim, seja bem-vindo", fromPanel = true, status = "sent"),
                            ChatUi(3, System.currentTimeMillis() - 400, "Painel", "teste sem confirmação", fromPanel = true, status = "pending"),
                        ),
                        players = listOf(
                            PlayerUi("neescao", "Neescao", 28, avatarState = AvatarStateUi.Loading, avatarReason = "carregando a skin…"),
                            PlayerUi("steve", "Steve", null, avatarState = AvatarStateUi.Unsupported, avatarReason = "skin Persona: sem cubo de cabeça no modelo"),
                        ),
                    ),
                )
            }
            val actions = object : PanelActions {
                override fun start() {
                    data = data.copy(phase = Phase.Running, uptime = "00:00:01")
                }
                override fun stop() {
                    data = data.copy(phase = Phase.Stopped, uptime = "—")
                }
                override fun selectWorld(id: String) {
                    data = data.copy(selectedWorldId = id)
                }
                override fun importWorld() = notice("Demonstração: conectar ao seletor de .mcworld na integração.")
                override fun copyAddress() = notice(data.internetAddress)
                override fun shareAddress() = notice("Compartilhar ${data.internetAddress}")
                override fun internetDetails() = notice("Acesso externo não verificado.")
                override fun verifyAccess() = notice("Demonstração: nenhum teste real é feito aqui.")
                override fun help() = notice("Demonstração visual. Nenhum servidor está em execução.")
                override fun worldOptions(id: String) = notice("Opções do mundo: $id")
                override fun copyLogs() = notice("Integrar cópia do log.")
                override fun exportLogs() = notice("Integrar exportação do log.")
                override fun clearVisibleLogs() {
                    data = data.copy(logLines = emptyList())
                }
                override fun diagnostics() = notice("Dados de demonstração: nenhuma conexão foi testada.")
                override fun sendChat(text: String) = notice("Prévia: mensagem não enviada.")
                override fun playerDetails(id: String) = notice("Detalhes do jogador $id")
                override fun editName() = notice("Integrar editor de nome.")
                override fun editPort() = notice("Integrar editor de porta.")
                override fun setMaxPlayers(value: Int) {
                    data = data.copy(maxPlayers = value)
                }
                override fun setViewDistance(value: Int) {
                    data = data.copy(viewDistance = value)
                }
                override fun gameSettings() = notice("Integrar modo de jogo e dificuldade.")
                override fun advancedSettings() = notice("Integrar autenticação, transporte, relay, diagnóstico e banidos.")
                override fun changeAppearance() {
                    dark = !dark
                }
            }
            MoboPanel(data, actions, dark)
        }
    }

    private fun notice(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
