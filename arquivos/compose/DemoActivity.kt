package com.moboserver.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

/** Activity separada para avaliar UI; não inicia nenhum servidor. */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by remember { mutableStateOf(false) }
            var data by remember { mutableStateOf(PanelUi(
                selectedWorldId="demo1",worlds=listOf(WorldUi("demo1","Meu mundo novo","45 KB"),WorldUi("demo2","TesteServer","147 KB")),
                localAddress="192.168.1.106",maxPlayers=4,logs="19:15:30  Salvando mundo\n19:15:31  Salvando chunks\n19:15:32  Mundo salvo\n19:15:34  Servidor desligado"
            )) }
            val actions=object:PanelActions {
                override fun start(){data=data.copy(phase=Phase.Running,uptime="00:12",medianRttMs=28,players=listOf(PlayerUi("demo","Neescao",28)))}
                override fun stop(){data=data.copy(phase=Phase.Stopped,uptime="—",medianRttMs=null,players=emptyList())}
                override fun selectWorld(id:String){data=data.copy(selectedWorldId=id)}
                override fun importWorld(){notice("Demonstração: conectar ao seletor de .mcworld na integração.")}
                override fun copyAddress(){notice(data.localAddress)}
                override fun internetDetails(){notice("Acesso externo não verificado.")}
                override fun help(){notice("Demonstração visual. Nenhum servidor está sendo executado.")}
                override fun worldOptions(id:String){notice("Integrar exportação, validação e exclusão com confirmação: $id")}
                override fun copyLogs(){notice("Integrar cópia do log.")}
                override fun clearVisibleLogs(){data=data.copy(logs="")}
                override fun diagnostics(){notice("Dados de demonstração: nenhuma conexão foi testada.")}
                override fun sendChat(text:String){notice("Prévia: mensagem não enviada.")}
                override fun editName(){notice("Integrar editor de nome.")}
                override fun editPort(){notice("Integrar editor de porta.")}
                override fun setMaxPlayers(value:Int){data=data.copy(maxPlayers=value)}
                override fun setViewDistance(value:Int){data=data.copy(viewDistance=value)}
                override fun gameSettings(){notice("Integrar modo de jogo e dificuldade.")}
                override fun advancedSettings(){notice("Integrar autenticação, transporte, relay, diagnóstico e bloqueados existentes.")}
                override fun changeAppearance(){dark=!dark}
            }
            MoboPanel(data,actions,dark)
        }
    }
    private fun notice(message:String){android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show()}
}
