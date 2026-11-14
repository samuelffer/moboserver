package com.moboserver.design

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
data class PlayerUi(val id: String, val name: String, val latencyMs: Long?, val head: ImageBitmap? = null)
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
    val logs: String = "",
    val error: String? = null,
)
interface PanelActions {
    fun start(); fun stop(); fun selectWorld(id: String); fun importWorld()
    fun copyAddress(); fun internetDetails(); fun help(); fun worldOptions(id: String)
    fun copyLogs(); fun clearVisibleLogs(); fun diagnostics(); fun sendChat(text: String)
    fun editName(); fun editPort(); fun setMaxPlayers(value: Int); fun setViewDistance(value: Int)
    fun gameSettings(); fun advancedSettings(); fun changeAppearance()
}
private data class Palette(val bg: Color, val card: Color, val ink: Color, val muted: Color, val green: Color, val soft: Color, val line: Color)
private val Light = Palette(Color(0xFFF5F6F3), Color.White, Color(0xFF111311), Color(0xFF60645F), Color(0xFF106B32), Color(0xFFE9F0E1), Color(0xFFECEEe9))
private val Dark = Palette(Color(0xFF151916), Color(0xFF202622), Color(0xFFF4F6F1), Color(0xFFAEB8AE), Color(0xFF80D99A), Color(0xFF293C2D), Color(0xFF343C36))
private val LocalPalette = staticCompositionLocalOf { Light }
private val C: Palette @Composable get() = LocalPalette.current

/** Cores do kit expostas para as subpáginas do app (mesma paleta, sem redesenho). */
data class MoboPalette(
    val bg: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val green: Color,
    val soft: Color,
    val line: Color,
)

@Composable
fun moboPalette(dark: Boolean): MoboPalette {
    val p = if (dark) Dark else Light
    return MoboPalette(p.bg, p.card, p.ink, p.muted, p.green, p.soft, p.line)
}

@Composable
fun MoboPanel(state: PanelUi, actions: PanelActions, dark: Boolean = isSystemInDarkTheme()) {
    var page by remember { mutableStateOf(Page.Home) }
    val palette = if (dark) Dark else Light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = if(dark) darkColorScheme(primary=palette.green, background=palette.bg, surface=palette.card, onSurface=palette.ink) else lightColorScheme(primary=palette.green, background=palette.bg, surface=palette.card, onSurface=palette.ink)) {
            Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp)) {
                    Spacer(Modifier.height(20.dp))
                    when(page) {
                        Page.Home -> Home(state, actions) { page=Page.Players }
                        Page.Worlds -> Worlds(state, actions)
                        Page.Console -> Console(state, actions)
                        Page.Settings -> Settings(state, actions)
                        Page.Players -> Players(state) { page=Page.Home }
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
        if(brand) Text("MoboServer",color=C.green,fontSize=16.sp,fontWeight=FontWeight.Bold)
        Text(title,fontSize=40.sp,lineHeight=44.sp,letterSpacing=(-1.6).sp,fontWeight=FontWeight.Bold,color=C.ink)
        if(subtitle.isNotEmpty()) Text(subtitle,color=C.muted,fontSize=16.sp,modifier=Modifier.padding(top=6.dp))
    }
}
@Composable private fun Group(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card).padding(horizontal=16.dp,vertical=6.dp),content=content)
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
@Composable private fun Home(s:PanelUi,a:PanelActions,players:()->Unit) {
    Heading("Servidor",brand=true)
    val world=s.worlds.find { it.id==s.selectedWorldId }
    val busy=s.phase==Phase.Starting || s.phase==Phase.Stopping
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.soft).padding(18.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(world?.name ?: "Selecione um mundo",fontSize=26.sp,lineHeight=29.sp,fontWeight=FontWeight.Bold,color=C.ink)
                Text(when(s.phase){Phase.Stopped->"● Desligado";Phase.Starting->"● Iniciando";Phase.Running->"● Online";Phase.Stopping->"● Salvando e parando";Phase.Error->"● Falha ao iniciar"},color=C.muted,fontSize=15.sp,modifier=Modifier.padding(top=10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Ping dos jogadores",fontSize=13.sp,color=C.muted)
                Text(s.medianRttMs?.let{"$it ms"}?:"—",color=C.ink,fontSize=17.sp,modifier=Modifier.padding(vertical=6.dp))
                Rule(); Text("Tempo ativo",fontSize=13.sp,color=C.muted,modifier=Modifier.padding(top=8.dp))
                Text(s.uptime,color=C.ink,fontSize=17.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Primary(if(s.phase==Phase.Running) "Parar servidor" else if(busy) "Aguarde…" else "Ligar servidor",!busy && (world!=null || s.phase==Phase.Running)) { if(s.phase==Phase.Running) a.stop() else a.start() }
    }
    s.error?.let { Note(it) }
    Text("Conexão",fontSize=25.sp,fontWeight=FontWeight.Bold,color=C.ink,modifier=Modifier.padding(start=10.dp,top=18.dp,bottom=8.dp))
    Group { Entry(Icons.Outlined.Wifi,"Endereço local",s.localAddress,a::copyAddress);Rule();Entry(Icons.Outlined.SettingsEthernet,"Porta",s.port.toString(),a::editPort) }
    Gap();Group { Entry(Icons.Outlined.People,"Jogadores online","${s.players.size}/${s.maxPlayers}",players);Rule();Entry(Icons.Outlined.Public,"Acesso pela internet",onClick=a::internetDetails);Rule();Entry(Icons.Outlined.HelpOutline,"Como seus amigos entram",onClick=a::help) }
    Note("Internet: ${s.internetStatus}")
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card)) {
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
@Composable private fun Console(s:PanelUi,a:PanelActions) {
    Heading("Console","Registro do servidor.")
    Group {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            Tool(Icons.Outlined.ContentCopy,"Copiar",a::copyLogs)
            Tool(Icons.Outlined.MonitorHeart,"Diagnóstico",a::diagnostics)
            Tool(Icons.Outlined.DeleteOutline,"Limpar",a::clearVisibleLogs)
        }
    };Gap()
    Group(Modifier.heightIn(min=300.dp)) { Label("LOG DO SERVIDOR");Text(s.logs.ifBlank{"Sem registros nesta sessão."},fontFamily=FontFamily.Monospace,fontSize=13.sp,lineHeight=22.sp,color=C.ink,modifier=Modifier.padding(bottom=16.dp)) }
    Gap();var message by remember { mutableStateOf("") }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=message,onValueChange={message=it},placeholder={Text("Mensagem no chat")},singleLine=true,shape=RoundedCornerShape(14.dp),modifier=Modifier.weight(1f))
        IconButton(onClick={a.sendChat(message);message=""},enabled=s.phase==Phase.Running && message.isNotBlank()) {Icon(Icons.Outlined.Send,"Enviar mensagem",tint=C.green)}
    }
}
@Composable private fun Tool(icon:ImageVector,label:String,action:()->Unit) {
    Column(Modifier.clickable(onClick=action).padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(icon,null,tint=C.ink);Text(label,color=C.ink,fontSize=12.sp,modifier=Modifier.padding(top=6.dp)) }
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
            else Box(Modifier.size(40.dp).background(C.soft,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center){Text("—",color=C.muted)}
            Text(p.name,color=C.ink,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            val rtt=p.latencyMs
            val bars=when{rtt==null->0;rtt<80->3;rtt<160->2;else->1}
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(2.dp)) { (1..3).forEach { i-> Box(Modifier.width(4.dp).height((5+i*4).dp).background(if(i<=bars) C.green else C.line,RoundedCornerShape(1.dp))) } }
            Text(rtt?.let{"$it ms"}?:"—",color=C.muted,fontSize=13.sp)
        }
    };Note("Latência entre cada jogador e o servidor. Sem medição, mostramos —. As barras são apenas uma indicação visual.")
}
@Composable private fun BottomBar(page:Page,onPage:(Page)->Unit) {
    val tabs=listOf(Triple(Page.Home,"Início",Icons.Outlined.Home),Triple(Page.Worlds,"Mundos",Icons.Outlined.Inventory2),Triple(Page.Console,"Console",Icons.Outlined.Terminal),Triple(Page.Settings,"Ajustes",Icons.Outlined.Settings))
    Row(Modifier.padding(horizontal=12.dp,vertical=10.dp).fillMaxWidth().clip(RoundedCornerShape(27.dp)).background(C.card).padding(4.dp)) {
        tabs.forEach { (p,label,icon)->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if(page==p) C.soft else Color.Transparent).clickable {onPage(p)}.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(icon,null,tint=if(page==p) C.green else C.ink,modifier=Modifier.size(24.dp));Text(label,fontSize=12.sp,color=if(page==p) C.green else C.muted,modifier=Modifier.padding(top=4.dp))
            }
        }
    }
}
