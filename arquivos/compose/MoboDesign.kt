package com.moboserver.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// UI pura: nenhuma importação de Store, ServerController ou serviço.
// Integração deve fornecer estado imutável e implementar todos os callbacks.
enum class Page { Home, Worlds, Console, Settings, Players }
enum class Phase { Stopped, Starting, Running, Stopping, Error }
data class WorldUi(val id: String, val name: String, val size: String, val thumbnail: ImageBitmap? = null)
data class PlayerUi(val id: String, val name: String, val latencyMs: Long?, val head: ImageBitmap? = null, val avatarStatus: String = "Prévia pendente")
data class PanelUi(
    val phase: Phase = Phase.Stopped,
    val selectedWorldId: String? = null,
    val worlds: List<WorldUi> = emptyList(),
    val players: List<PlayerUi> = emptyList(),
    val serverName: String = "MoboServer",
    val localAddress: String = "—",
    val port: Int = 19132,
    val maxPlayers: Int = 5,
    val viewDistance: Int = 8,
    val medianRttMs: Long? = null,
    val uptime: String = "—",
    val internetStatus: String = "Não verificado",
    val internetAddress: String = "Aguardando conexão",
    val lastSave: String = "—",
    val gameSummary: String = "Sobrevivência · Normal",
    val chat: List<ChatUi> = emptyList(),
    val logEntries: List<LogUi> = emptyList(),
    val logs: String = "",
    val error: String? = null,
)
data class ChatUi(val id: String, val author: String, val text: String)
data class LogUi(val id: String, val time: String, val level: String, val text: String)

interface PanelActions {
    fun start(); fun stop(); fun selectWorld(id: String); fun importWorld()
    fun copyAddress(); fun internetDetails(); fun help(); fun worldOptions(id: String)
    fun copyLogs(); fun clearVisibleLogs(); fun diagnostics(); fun sendChat(text: String)
    fun editName(); fun editPort(); fun setMaxPlayers(value: Int); fun setViewDistance(value: Int)
    fun gameSettings(); fun advancedSettings(); fun changeAppearance()
}
private data class Palette(val bg: Color, val card: Color, val ink: Color, val muted: Color, val green: Color, val soft: Color, val line: Color)
private val Light = Palette(Color(0xFFF2F3F5), Color.White, Color(0xFF18201C), Color(0xFF747D78), Color(0xFF147D45), Color(0xFFE8F3EB), Color(0xFFECEEe9))
private val Dark = Palette(Color(0xFF101512), Color(0xFF1B231E), Color(0xFFF4F6F1), Color(0xFFAEB8AE), Color(0xFF86DCA6), Color(0xFF253A2B), Color(0xFF343C36))
private val LocalPalette = staticCompositionLocalOf { Light }
private val C: Palette @Composable get() = LocalPalette.current

@Composable
fun MoboPanel(state: PanelUi, actions: PanelActions, dark: Boolean = isSystemInDarkTheme(), navigationEnabled: Boolean = true) {
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    BackHandler(enabled = navigationEnabled && page == Page.Players) {
        page = Page.Home
    }
    val palette = if (dark) Dark else Light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(typography = MoboTypography, colorScheme = if(dark) darkColorScheme(primary=palette.green, background=palette.bg, surface=palette.card, onSurface=palette.ink) else lightColorScheme(primary=palette.green, background=palette.bg, surface=palette.card, onSurface=palette.ink)) {
            Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Crossfade(targetState = page, animationSpec = tween(200), label = "page") { displayedPage ->
                    Column {
                    when(displayedPage) {
                        Page.Home -> Home(state, actions) { page=Page.Players }
                        Page.Worlds -> Worlds(state, actions)
                        Page.Console -> Console(state, actions)
                        Page.Settings -> Settings(state, actions)
                        Page.Players -> Players(state) { page=Page.Home }
                    }
                    }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                BottomBar(if(page==Page.Players) Page.Home else page) { page=it }
            }
        }
    }
}
@Composable private fun Heading(title: String, subtitle: String = "", brand: Boolean = false) {
    Column(Modifier.padding(start=10.dp,end=10.dp,bottom=24.dp)) {
        if(brand) Text("MoboServer",color=C.green,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        Text(title,fontSize=34.sp,lineHeight=41.sp,letterSpacing=(-1.25).sp,fontWeight=FontWeight.Bold,color=C.ink)
        if(subtitle.isNotEmpty()) Text(subtitle,color=C.muted,fontSize=16.sp,modifier=Modifier.padding(top=6.dp))
    }
}
@Composable private fun Group(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).padding(horizontal=16.dp,vertical=6.dp),content=content)
}
@Composable private fun Gap() { Spacer(Modifier.height(16.dp)) }
@Composable private fun Rule() { HorizontalDivider(color=C.line) }
@Composable private fun Entry(icon: ImageVector, label: String, value: String="", onClick: (() -> Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier).heightIn(min=48.dp).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,tint=C.ink,modifier=Modifier.size(22.dp))
        Text(label,color=C.ink,fontSize=15.sp,modifier=Modifier.weight(1f))
        if(value.isNotEmpty()) Text(value,color=C.muted,fontSize=14.sp)
        if(onClick!=null) Icon(Icons.Outlined.ChevronRight,null,tint=C.muted,modifier=Modifier.size(18.dp))
    }
}
@Composable private fun Primary(label: String, enabled: Boolean=true, onClick:()->Unit) {
    Button(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF126A34),contentColor=Color.White),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(label,fontSize=17.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable private fun Label(text: String) { Text(text,color=C.green,fontWeight=FontWeight.Bold,fontSize=13.sp,modifier=Modifier.padding(start=10.dp,top=18.dp,bottom=10.dp)) }
@Composable private fun Note(text: String) { Text(text,color=C.muted,fontSize=13.sp,modifier=Modifier.padding(horizontal=10.dp,vertical=10.dp)) }
@Composable
private fun Home(state: PanelUi, actions: PanelActions, players: () -> Unit) {
    Heading("Servidor", brand = true)
    val world = state.worlds.find { it.id == state.selectedWorldId }
    val busy = state.phase == Phase.Starting || state.phase == Phase.Stopping
    val running = state.phase == Phase.Running
    Group {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) {
            Text(
                text = when (state.phase) {
                    Phase.Running -> "● Servidor online"
                    Phase.Starting -> "● Iniciando servidor"
                    Phase.Stopping -> "● Salvando e parando"
                    Phase.Error -> "● Falha ao iniciar"
                    Phase.Stopped -> "● Servidor desligado"
                },
                color = C.green,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(world?.name ?: "Selecione um mundo", fontSize = 27.sp,
                lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = C.ink)
            Text(state.gameSummary, fontSize = 13.sp, color = C.muted,
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tempo ativo", fontSize = 11.sp, color = C.muted)
                    Text(state.uptime, fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Último salvamento", fontSize = 11.sp, color = C.muted)
                    Text(state.lastSave, fontSize = 18.sp, color = C.ink, fontWeight = FontWeight.SemiBold)
                }
            }
            Primary(
                label = if (running) "Parar servidor" else if (busy) "Aguarde…" else "Ligar servidor",
                enabled = !busy && (world != null || running),
            ) { if (running) actions.stop() else actions.start() }
        }
    }
    state.error?.let { Note(it) }
    Text("Conexão", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        color = C.ink, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
    Group {
        Column(Modifier.clickable(onClick = actions::internetDetails).padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Public, null, tint = C.green)
                Text("Endereço de internet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.ink)
            }
            Text(state.internetAddress, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = C.ink, modifier = Modifier.padding(top = 12.dp))
            Text(state.internetStatus, fontSize = 12.sp, color = C.muted,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
    Gap()
    Group {
        Entry(Icons.Outlined.Wifi, "Rede local", state.localAddress, actions::copyAddress)
        Rule()
        Entry(Icons.Outlined.People, "Jogadores online", "${state.players.size} de ${state.maxPlayers}", players)
        Rule()
        Entry(Icons.Outlined.HelpOutline, "Como entrar", onClick = actions::help)
    }
}
@Composable private fun WorldImage(image:ImageBitmap?,modifier:Modifier) {
    if(image!=null) Image(image,"Imagem do mundo",modifier,contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(C.soft),contentAlignment=Alignment.Center) { Text("Sem imagem do mundo",fontSize=12.sp,color=C.muted) }
}
@Composable private fun Worlds(s:PanelUi,a:PanelActions) {
    Heading("Mundos","Escolha o mundo do servidor.")
    val editable=s.phase==Phase.Stopped || s.phase==Phase.Error
    Primary("Importar mundo",editable,a::importWorld);Gap()
    if(s.worlds.isEmpty()) Note("Exporte um .mcworld pelo Minecraft e importe aqui. Seu arquivo original será preservado.")
    s.worlds.forEach { w ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card)) {
            WorldImage(w.thumbnail,Modifier.fillMaxWidth().height(152.dp))
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(w.name,fontSize=23.sp,fontWeight=FontWeight.Bold,color=C.ink);Text(w.size,color=C.muted,fontSize=14.sp) }
                TextButton(onClick={a.selectWorld(w.id)},enabled=editable && w.id!=s.selectedWorldId) { Text(if(w.id==s.selectedWorldId) "Selecionado" else "Selecionar",color=C.green) }
                IconButton(onClick={a.worldOptions(w.id)},enabled=editable) { Icon(Icons.Outlined.MoreHoriz,"Opções do mundo",tint=C.ink) }
            }
        };Gap()
    }
    Note("Arquivos .mcworld • o original é preservado.")
}
@Composable
private fun Console(state: PanelUi, actions: PanelActions) {
    Heading("Console", "Acompanhe a sessão.")
    var chatSelected by rememberSaveable { mutableStateOf(false) }
    var warningsOnly by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(C.line).padding(3.dp)) {
        listOf("Registros", "Chat").forEachIndexed { index, title ->
            val selected = chatSelected == (index == 1)
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .background(if (selected) C.card else Color.Transparent)
                .clickable { chatSelected = index == 1 }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(title, color = if (selected) C.ink else C.muted,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Gap()
    if (chatSelected) {
        Group(Modifier.heightIn(min = 220.dp)) {
            if (state.chat.isEmpty()) Note("As mensagens desta sessão aparecerão aqui.")
            state.chat.forEach { message ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(message.author, color = C.green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(message.text, color = C.ink, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
        Gap()
        var message by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = message, onValueChange = { message = it },
                placeholder = { Text("Mensagem para os jogadores", fontSize = 13.sp) },
                enabled = state.phase == Phase.Running,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.sendChat(message); message = "" },
                enabled = state.phase == Phase.Running && message.isNotBlank()) {
                Icon(Icons.Outlined.Send, "Enviar mensagem", tint = C.green)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { warningsOnly = !warningsOnly }, modifier = Modifier.weight(1f)) {
                Text(if (warningsOnly) "Avisos e erros" else "Todos os registros", color = C.green)
            }
            IconButton(onClick = actions::copyLogs) { Icon(Icons.Outlined.ContentCopy, "Copiar registros", tint = C.muted) }
            IconButton(onClick = actions::clearVisibleLogs) { Icon(Icons.Outlined.DeleteOutline, "Limpar registros visíveis", tint = C.muted) }
        }
        Group {
            val entries = state.logEntries.filter { !warningsOnly || it.level in listOf("warn", "error") }
            if (entries.isEmpty()) Note("Nenhum registro neste filtro.")
            entries.forEachIndexed { index, entry ->
                if (index > 0) Rule()
                Column(Modifier.padding(vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(entry.time, color = C.muted, fontSize = 10.sp)
                        Text(entry.level.uppercase(), color = C.green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(entry.text, color = C.ink, fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}
@Composable private fun Settings(s:PanelUi,a:PanelActions) {
    Heading("Ajustes","Valem no próximo início do servidor.")
    Label("SERVIDOR");Group {Entry(Icons.Outlined.Dns,"Nome do servidor",s.serverName,a::editName);Rule();Entry(Icons.Outlined.SettingsEthernet,"Porta",s.port.toString(),a::editPort)}
    Label("DESEMPENHO");Group {
        var max by remember(s.maxPlayers) {mutableFloatStateOf(s.maxPlayers.toFloat())}
        Entry(Icons.Outlined.People,"Máximo de jogadores",max.toInt().toString())
        Slider(value=max,onValueChange={max=it},onValueChangeFinished={a.setMaxPlayers(max.toInt())},valueRange=1f..5f,steps=3)
        Rule();var view by remember(s.viewDistance) {mutableFloatStateOf(s.viewDistance.toFloat().coerceIn(4f,16f))}
        Entry(Icons.Outlined.Visibility,"Distância de visão","${view.toInt()} chunks")
        Slider(value=view,onValueChange={view=it},onValueChangeFinished={a.setViewDistance(view.toInt())},valueRange=4f..16f,steps=11)
    }
    Note("Valores menores aliviam o celular.");Gap()
    Group { Entry(Icons.Outlined.SportsEsports,"Jogo",onClick=a::gameSettings);Rule();Entry(Icons.Outlined.DarkMode,"Aparência",onClick=a::changeAppearance);Rule();Entry(Icons.Outlined.Tune,"Avançado",onClick=a::advancedSettings) }
}
@Composable private fun Players(s:PanelUi,back:()->Unit) {
    TextButton(onClick=back){Text("‹ Voltar",color=C.green)};Heading("Jogadores","${s.players.size} de ${s.maxPlayers} conectados")
    if(s.players.isEmpty()) Note("Nenhum jogador conectado.")
    Group { s.players.forEachIndexed { index,p ->
        if(index>0) Rule()
        Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(p.head!=null) Image(p.head,"Cabeça da skin de ${p.name}",Modifier.size(40.dp),filterQuality=androidx.compose.ui.graphics.FilterQuality.None)
            else Box(Modifier.size(40.dp).background(C.soft,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Person,"Prévia da skin indisponível",tint=C.muted)}
            Column(Modifier.weight(1f)) {
                Text(p.name, color=C.ink, fontWeight=FontWeight.SemiBold)
                if (p.head == null) Text(p.avatarStatus, color=C.muted, fontSize=11.sp)
            }
            val rtt=p.latencyMs
            val bars=when{rtt==null->0;rtt<80->3;rtt<160->2;else->1}
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(2.dp)) { (1..3).forEach { i-> Box(Modifier.width(4.dp).height((5+i*4).dp).background(if(i<=bars) C.green else C.line,RoundedCornerShape(1.dp))) } }
            Text(rtt?.let{"$it ms"}?:"—",color=C.muted,fontSize=13.sp)
        }
    }
    Label("LATÊNCIA DOS JOGADORES")
    Group { Entry(Icons.Outlined.Speed, "Mediana", s.medianRttMs?.let { "$it ms" } ?: "—") }
    Note("Medida entre cada jogador e o celular.")
}
@Composable private fun BottomBar(page:Page,onPage:(Page)->Unit) {
    val tabs=listOf(Triple(Page.Home,"Início",Icons.Outlined.Home),Triple(Page.Worlds,"Mundos",Icons.Outlined.Inventory2),Triple(Page.Console,"Console",Icons.Outlined.Terminal),Triple(Page.Settings,"Ajustes",Icons.Outlined.Settings))
    Row(Modifier.padding(horizontal=12.dp,vertical=10.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.card).padding(4.dp)) {
        tabs.forEach { (p,label,icon)->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if(page==p) C.soft else Color.Transparent).clickable {onPage(p)}.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(icon,null,tint=if(page==p) C.green else C.ink,modifier=Modifier.size(24.dp));Text(label,fontSize=12.sp,color=if(page==p) C.green else C.muted,modifier=Modifier.padding(top=4.dp))
            }
        }
    }
}
