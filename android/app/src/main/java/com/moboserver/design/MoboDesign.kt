package com.moboserver.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch

enum class Page { Home, Worlds, Console, Settings, Players }
enum class Phase { Stopped, Starting, Running, Stopping, Error }

/** Estado da miniatura de quem está online (nunca um traço sem explicação). */
enum class AvatarStateUi { Loading, Ready, Unsupported, Failed }

data class WorldUi(val id: String, val name: String, val size: String, val thumbnail: ImageBitmap? = null)
data class PlayerUi(
    val id: String,
    val name: String,
    val latencyMs: Long?,
    val head: ImageBitmap? = null,
    val avatarState: AvatarStateUi = AvatarStateUi.Loading,
    val avatarReason: String = "",
    /** Resumo técnico da skin (dimensões, geometria), sem imagem. */
    val avatarInfo: String = "",
)

/** Registro do console como objeto: horário, nível e mensagem. */
data class LogUi(val id: Long, val time: Long, val level: String, val message: String)
/** Mensagem do chat do jogo. [status] é "pending", "sent" ou "failed". */
data class ChatUi(
    val id: Long,
    val time: Long,
    val from: String,
    val text: String,
    val fromPanel: Boolean = false,
    val status: String = "sent",
)

data class PanelUi(
    val phase: Phase = Phase.Stopped,
    val selectedWorldId: String? = null,
    val worlds: List<WorldUi> = emptyList(),
    val players: List<PlayerUi> = emptyList(),
    val serverName: String = "MoboServer",
    /** Endereço da rede local, sem porta. */
    val lanAddress: String = "—",
    /** Endereço a divulgar para quem vai entrar pela internet, com porta. */
    val internetAddress: String = "—",
    /** "verificado", "observado", "wan" ou "nenhum" — explica o tipo do endereço. */
    val internetKind: String = "nenhum",
    val internetStatus: String = "Não verificado",
    val port: Int = 19132,
    val maxPlayers: Int = 5,
    val viewDistance: Int = 8,
    val uptime: String = "—",
    /** Último salvamento confirmado pelo servidor, ou "—". */
    val lastSave: String = "—",
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val logLines: List<LogUi> = emptyList(),
    val chatMessages: List<ChatUi> = emptyList(),
    val error: String? = null,
)

interface PanelActions {
    fun start(); fun stop(); fun selectWorld(id: String); fun importWorld()
    fun copyAddress(); fun shareAddress(); fun internetDetails(); fun verifyAccess(); fun help()
    fun worldOptions(id: String)
    fun copyLogs(); fun exportLogs(); fun clearVisibleLogs(); fun diagnostics()
    fun sendChat(text: String); fun playerDetails(id: String)
    fun editName(); fun editPort(); fun setMaxPlayers(value: Int); fun setViewDistance(value: Int)
    fun gameSettings(); fun advancedSettings(); fun changeAppearance()
}

private data class Palette(val bg: Color, val card: Color, val ink: Color, val muted: Color, val green: Color, val soft: Color, val line: Color)
private val Light = Palette(Color(0xFFF2F3F5), Color.White, Color(0xFF18201C), Color(0xFF747D78), Color(0xFF147D45), Color(0xFFE8F3EB), Color(0xFFE9ECEA))
private val Dark = Palette(Color(0xFF101512), Color(0xFF1B231E), Color(0xFFF3F6F3), Color(0xFFA2ADA6), Color(0xFF86DCA6), Color(0xFF253A2B), Color(0xFF2C3630))
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

/**
 * Painel principal. [page]/[onPageChange] permitem que o app controle a
 * navegação (e o botão Voltar); sem eles a página fica em estado local, o que
 * mantém a demonstração visual funcionando isolada.
 */
@Composable
fun MoboPanel(
    state: PanelUi,
    actions: PanelActions,
    dark: Boolean = isSystemInDarkTheme(),
    page: Page? = null,
    onPageChange: ((Page) -> Unit)? = null,
) {
    var internalPage by rememberSaveable { mutableStateOf(Page.Home) }
    val current = page ?: internalPage
    fun go(target: Page) {
        if (onPageChange != null) onPageChange(target) else internalPage = target
    }
    MoboUiTheme(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding()) {
                // Uma posição de rolagem por aba: a chave do rememberSaveable
                // inclui a página, então cada uma guarda e restaura a sua.
                val scroll = rememberSaveable(key = "rolagem-" + current.name, saver = ScrollState.Saver) {
                    ScrollState(0)
                }
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    // Fade + deslocamento discreto (6 dp) entre abas; a duração
                    // respeita a escala de animação do sistema (0 = sem animação).
                    val duration = motionDuration()
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            (fadeIn(tween(duration)) + slideInVertically(tween(duration)) { it / 50 }) togetherWith
                                fadeOut(tween(duration / 2))
                        },
                        label = "paginas",
                    ) { shown ->
                        // O conteúdo do AnimatedContent é medido como um único
                        // filho: sem esta Column, cada irmão da página (título,
                        // cartões, botões) era posicionado na mesma origem e os
                        // elementos apareciam sobrepostos.
                        Column(Modifier.fillMaxWidth()) {
                            when (shown) {
                                Page.Home -> Home(state, actions) { go(Page.Players) }
                                Page.Worlds -> Worlds(state, actions)
                                Page.Console -> Console(state, actions)
                                Page.Settings -> Settings(state, actions)
                                Page.Players -> Players(state, actions) { go(Page.Home) }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                BottomBar(if (current == Page.Players) Page.Home else current) { go(it) }
            }
        }
}

/** Cores do slider na paleta do app (o padrão do Material usa roxo). */
@Composable
private fun moboSliderColors() = SliderDefaults.colors(
    thumbColor = C.green,
    activeTrackColor = C.green,
    inactiveTrackColor = C.line,
    activeTickColor = C.card,
    inactiveTickColor = C.muted,
)

/** Duração (ms) coerente com a escala de animação do sistema. */
@Composable
private fun motionDuration(): Int {
    val context = LocalContext.current
    val scale = remember(context) {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
    return if (scale <= 0f) 0 else 200
}

/**
 * Tema único do aplicativo: paleta aprovada (clara/escura) + família Inter.
 * Não usa cores dinâmicas: a paleta do design é fixa.
 */
@Composable
fun MoboUiTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) Dark else Light
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            typography = MoboTypography,
            colorScheme = if (dark) {
                darkColorScheme(
                    primary = palette.green,
                    onPrimary = Color(0xFF08210F),
                    primaryContainer = palette.soft,
                    onPrimaryContainer = palette.ink,
                    secondary = palette.green,
                    onSecondary = Color(0xFF08210F),
                    background = palette.bg,
                    onBackground = palette.ink,
                    surface = palette.card,
                    onSurface = palette.ink,
                    surfaceVariant = palette.soft,
                    onSurfaceVariant = palette.muted,
                    outline = palette.line,
                    outlineVariant = palette.line,
                    error = Color(0xFFFF948A),
                    onError = Color(0xFF2A0909),
                    inverseSurface = palette.ink,
                    inverseOnSurface = palette.card,
                    inversePrimary = Color(0xFF126A34),
                )
            } else {
                lightColorScheme(
                    primary = palette.green,
                    onPrimary = Color.White,
                    primaryContainer = palette.soft,
                    onPrimaryContainer = palette.ink,
                    secondary = palette.green,
                    onSecondary = Color.White,
                    background = palette.bg,
                    onBackground = palette.ink,
                    surface = palette.card,
                    onSurface = palette.ink,
                    surfaceVariant = palette.soft,
                    onSurfaceVariant = palette.muted,
                    outline = palette.line,
                    outlineVariant = palette.line,
                    error = Color(0xFFB3261E),
                    onError = Color.White,
                    inverseSurface = palette.ink,
                    inverseOnSurface = palette.card,
                    inversePrimary = Color(0xFF86DCA6),
                )
            },
            content = content,
        )
    }
}

@Composable private fun Heading(title: String, subtitle: String = "", brand: Boolean = false) {
    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 24.dp)) {
        if (brand) Text("MoboServer", color = C.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            title,
            fontSize = 34.sp,
            lineHeight = 41.sp,
            letterSpacing = (-1.25).sp,
            fontWeight = FontWeight.Bold,
            color = C.ink,
        )
        if (subtitle.isNotEmpty()) Text(subtitle, color = C.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).padding(horizontal = 16.dp, vertical = 6.dp), content = content)
}

@Composable private fun Gap() { Spacer(Modifier.height(16.dp)) }
@Composable private fun Rule() { HorizontalDivider(color = C.line) }

@Composable private fun Entry(icon: ImageVector, label: String, value: String = "", onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = C.ink, modifier = Modifier.size(22.dp))
        Text(label, color = C.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = C.muted, fontSize = 14.sp)
        if (onClick != null) Icon(Icons.Outlined.MoreHoriz, null, tint = C.line, modifier = Modifier.size(18.dp))
    }
}

@Composable private fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.green, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable private fun Label(text: String) {
    Text(text, color = C.green, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 10.dp))
}

@Composable private fun Note(text: String) {
    Text(text, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
}

@Composable private fun Home(s: PanelUi, a: PanelActions, players: () -> Unit) {
    Heading("Servidor", brand = true)
    val world = s.worlds.find { it.id == s.selectedWorldId }
    val busy = s.phase == Phase.Starting || s.phase == Phase.Stopping
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.soft).padding(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(world?.name ?: "Selecione um mundo", fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, color = C.ink)
                Text(
                    when (s.phase) {
                        Phase.Stopped -> "● Desligado"
                        Phase.Starting -> "● Iniciando"
                        Phase.Running -> "● Online"
                        Phase.Stopping -> "● Salvando e parando"
                        Phase.Error -> "● Falha ao iniciar"
                    },
                    color = C.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp),
                )
                Text("${s.gameMode} • ${s.difficulty}", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Tempo ativo", fontSize = 13.sp, color = C.muted)
                Text(s.uptime, color = C.ink, fontSize = 17.sp, modifier = Modifier.padding(vertical = 4.dp))
                Rule()
                Text("Último salvamento", fontSize = 13.sp, color = C.muted, modifier = Modifier.padding(top = 8.dp))
                Text(s.lastSave, color = C.ink, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Primary(if (s.phase == Phase.Running) "Parar servidor" else if (busy) "Aguarde…" else "Ligar servidor", !busy && (world != null || s.phase == Phase.Running)) {
            if (s.phase == Phase.Running) a.stop() else a.start()
        }
    }
    s.error?.let { Note(it) }

    Text("Conexão", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = C.ink, modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.soft).padding(18.dp)) {
        Text("Endereço para jogar pela internet", fontSize = 13.sp, color = C.muted)
        Text(
            s.internetAddress,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = C.ink,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(kindLabel(s.internetKind), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = a::copyAddress) {
                Icon(Icons.Outlined.ContentCopy, null, tint = C.green, modifier = Modifier.size(18.dp))
                Text(" Copiar", color = C.green)
            }
            TextButton(onClick = a::shareAddress) {
                Icon(Icons.Outlined.Share, null, tint = C.green, modifier = Modifier.size(18.dp))
                Text(" Compartilhar", color = C.green)
            }
        }
    }
    Note("Internet: ${s.internetStatus}")
    Gap()
    Group {
        Entry(Icons.Outlined.Wifi, "Rede local", "${s.lanAddress}:${s.port}")
        Rule()
        Entry(Icons.Outlined.SettingsEthernet, "Porta", s.port.toString(), a::editPort)
    }
    Gap()
    Group {
        Entry(Icons.Outlined.People, "Jogadores online", "${s.players.size}/${s.maxPlayers}", players)
        Rule()
        Entry(Icons.Outlined.Public, "Acesso pela internet", onClick = a::internetDetails)
        Rule()
        Entry(Icons.Outlined.HelpOutline, "Como seus amigos entram", onClick = a::help)
    }
    Gap()
    Primary("Verificar acesso", enabled = s.phase == Phase.Running && s.internetKind != "verificado", onClick = a::verifyAccess)
}

private fun kindLabel(kind: String): String = when (kind) {
    "verificado" -> "Endereço confirmado por um teste externo"
    "observado" -> "IP público observado (STUN) — acesso não verificado"
    "wan" -> "Endereço WAN do roteador (UPnP) — não é prova de acesso"
    "local" -> "Rede local — só funciona no mesmo Wi-Fi"
    else -> "Nenhum endereço externo confirmado"
}

@Composable private fun WorldImage(image: ImageBitmap?, modifier: Modifier) {
    if (image != null) Image(image, "Imagem do mundo", modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(C.soft), contentAlignment = Alignment.Center) { Text("Sem imagem do mundo", fontSize = 12.sp, color = C.muted) }
}

@Composable private fun Worlds(s: PanelUi, a: PanelActions) {
    Heading("Mundos", "Escolha o mundo do servidor.")
    val editable = s.phase == Phase.Stopped || s.phase == Phase.Error
    Primary("Importar mundo", editable, a::importWorld); Gap()
    if (s.worlds.isEmpty()) Note("Exporte um .mcworld pelo Minecraft e importe aqui. Seu arquivo original será preservado.")
    s.worlds.forEach { w ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.card)) {
            WorldImage(w.thumbnail, Modifier.fillMaxWidth().height(152.dp))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = C.ink)
                    Text(w.size, color = C.muted, fontSize = 14.sp)
                }
                TextButton(onClick = { a.selectWorld(w.id) }, enabled = editable && w.id != s.selectedWorldId) {
                    Text(if (w.id == s.selectedWorldId) "Selecionado" else "Selecionar", color = C.green)
                }
                IconButton(onClick = { a.worldOptions(w.id) }, enabled = editable) {
                    Icon(Icons.Outlined.MoreHoriz, "Opções do mundo", tint = C.ink)
                }
            }
        }; Gap()
    }
    Note("Arquivos .mcworld • o original é preservado.")
}

// ------------------------------------------------------------------- console

@Composable private fun Console(s: PanelUi, a: PanelActions) {
    Heading("Console", "Registros do servidor e chat em separado.")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Segmented(listOf("Registros", "Chat"), tab) { tab = it }
    Gap()
    if (tab == 0) Logs(s, a) else Chat(s, a)
}

@Composable private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.card).padding(4.dp)) {
        options.forEachIndexed { index, label ->
            Text(
                label,
                color = if (index == selected) C.green else C.muted,
                fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (index == selected) C.soft else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 12.dp)
                    .heightIn(min = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable private fun Logs(s: PanelUi, a: PanelActions) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Todos", "Avisos", "Erros")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(label, index == filter) { filter = index }
        }
    }
    Gap()
    val visible = remember(s.logLines, filter) {
        when (filter) {
            1 -> s.logLines.filter { it.level == "warn" || it.level == "error" }
            2 -> s.logLines.filter { it.level == "error" }
            else -> s.logLines
        }
    }
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    // Só acompanha o fim quando o usuário já está nele.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && atBottom) listState.animateScrollToItem(visible.lastIndex)
    }
    Box {
        Group {
            if (visible.isEmpty()) {
                Text("Sem registros nesta sessão.", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                // Altura limitada: uma LazyColumn dentro de um contêiner vertical
                // rolável não pode receber altura infinita.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                ) {
                    items(visible, key = { it.id }) { line ->
                        LogRow(line)
                    }
                }
            }
        }
        if (visible.isNotEmpty() && !atBottom) {
            Text(
                "Novos registros ↓",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.green)
                    .clickable { scope.launch { listState.animateScrollToItem(visible.lastIndex) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    Gap()
    Group {
        // Espaço dividido igualmente: cada ação ocupa 1/4 da linha, inclusive
        // com fonte ampliada (o texto quebra em até duas linhas).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tool(Icons.Outlined.ContentCopy, "Copiar", a::copyLogs, Modifier.weight(1f))
            Tool(Icons.Outlined.Share, "Exportar", a::exportLogs, Modifier.weight(1f))
            Tool(Icons.Outlined.MonitorHeart, "Diagnóstico", a::diagnostics, Modifier.weight(1f))
            Tool(Icons.Outlined.DeleteOutline, "Limpar", a::clearVisibleLogs, Modifier.weight(1f))
        }
    }
    Note("Limpar apaga só esta visualização; o servidor e o log em disco continuam.")
}

@Composable private fun LogRow(line: LogUi) {
    val color = when (line.level) {
        "error" -> Color(0xFFB3261E)
        "warn" -> Color(0xFF8A5A00)
        else -> C.ink
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hourOf(line.time), color = C.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(line.level.uppercase(Locale.getDefault()), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(line.message, color = color, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) C.green else C.muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) C.soft else C.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable private fun Chat(s: PanelUi, a: PanelActions) {
    var message by rememberSaveable { mutableStateOf("") }
    val lastSent = s.chatMessages.lastOrNull { it.fromPanel }
    // O texto só é apagado quando o servidor confirma o envio; em falha ele
    // continua no campo para nova tentativa.
    LaunchedEffect(lastSent?.id, lastSent?.status) {
        if (lastSent != null && lastSent.status == "sent" && lastSent.text == message.trim()) message = ""
    }
    Group {
        if (s.chatMessages.isEmpty()) {
            Text("Nenhuma mensagem nesta sessão.", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                s.chatMessages.takeLast(80).forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hourOf(item.time), color = C.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                if (item.fromPanel) "Painel" else item.from,
                                color = if (item.fromPanel) C.green else C.ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            when (item.status) {
                                "pending" -> Text("enviando…", color = C.muted, fontSize = 11.sp)
                                "failed" -> Text("não enviada", color = Color(0xFFB3261E), fontSize = 11.sp)
                            }
                        }
                        Text(item.text, color = C.ink, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
    Gap()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = message,
            onValueChange = { message = it.take(240) },
            placeholder = { Text("Mensagem no chat", color = C.muted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { a.sendChat(message) },
            enabled = s.phase == Phase.Running && message.isNotBlank(),
        ) { Icon(Icons.Outlined.Send, "Enviar mensagem", tint = C.green) }
    }
    if (s.phase != Phase.Running) Note("O chat só envia com o servidor online.")
}

@Composable
private fun Tool(
    icon: ImageVector,
    label: String,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = action)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = C.ink, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = C.ink,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable private fun Settings(s: PanelUi, a: PanelActions) {
    Heading("Ajustes", "Valem no próximo início do servidor.")
    Label("SERVIDOR"); Group { Entry(Icons.Outlined.Dns, "Nome do servidor", s.serverName, a::editName); Rule(); Entry(Icons.Outlined.SettingsEthernet, "Porta", s.port.toString(), a::editPort) }
    Label("DESEMPENHO"); Group {
        var max by remember(s.maxPlayers) { mutableFloatStateOf(s.maxPlayers.toFloat().coerceIn(1f, 5f)) }
        Entry(Icons.Outlined.People, "Máximo de jogadores", max.toInt().toString())
        Slider(
            value = max,
            onValueChange = { max = it },
            onValueChangeFinished = { a.setMaxPlayers(max.toInt()) },
            valueRange = 1f..5f,
            steps = 3,
            colors = moboSliderColors(),
        )
        Rule(); var view by remember(s.viewDistance) { mutableFloatStateOf(s.viewDistance.toFloat().coerceIn(4f, 16f)) }
        Entry(Icons.Outlined.Visibility, "Distância de visão", "${view.toInt()} chunks")
        Slider(
            value = view,
            onValueChange = { view = it },
            onValueChangeFinished = { a.setViewDistance(view.toInt()) },
            valueRange = 4f..16f,
            steps = 11,
            colors = moboSliderColors(),
        )
    }
    Note("Valores menores aliviam o celular."); Gap()
    Group { Entry(Icons.Outlined.SportsEsports, "Jogo", onClick = a::gameSettings); Rule(); Entry(Icons.Outlined.DarkMode, "Aparência", onClick = a::changeAppearance); Rule(); Entry(Icons.Outlined.Tune, "Avançado", onClick = a::advancedSettings) }
}

@Composable private fun Players(s: PanelUi, a: PanelActions, back: () -> Unit) {
    TextButton(onClick = back) { Text("‹ Voltar", color = C.green) }
    Heading("Jogadores", "${s.players.size} de ${s.maxPlayers} conectados")
    if (s.players.isEmpty()) Note("Nenhum jogador conectado.")
    Group {
        s.players.forEachIndexed { index, p ->
            if (index > 0) Rule()
            Row(
                Modifier.fillMaxWidth()
                    .clickable { a.playerDetails(p.id) }
                    .heightIn(min = 56.dp).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (p.head != null) {
                    Image(p.head, "Cabeça da skin de ${p.name}", Modifier.size(40.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                } else {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(C.soft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, "Sem imagem da skin", tint = C.muted, modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = C.ink, fontWeight = FontWeight.SemiBold)
                    val hint = when (p.avatarState) {
                        AvatarStateUi.Loading -> "carregando a skin…"
                        AvatarStateUi.Ready -> ""
                        AvatarStateUi.Unsupported -> p.avatarReason.ifBlank { "formato de skin não suportado" }
                        AvatarStateUi.Failed -> p.avatarReason.ifBlank { "falha ao ler a skin" }
                    }
                    if (hint.isNotEmpty()) {
                        Text(hint, color = C.muted, fontSize = 12.sp, maxLines = 2)
                    }
                }
                val rtt = p.latencyMs
                val bars = when {
                    rtt == null -> 0
                    rtt < 80 -> 3
                    rtt < 160 -> 2
                    else -> 1
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..3).forEach { i ->
                        Box(Modifier.width(4.dp).height((5 + i * 4).dp).background(if (i <= bars) C.green else C.line, RoundedCornerShape(1.dp)))
                    }
                }
                Text(rtt?.let { "$it ms" } ?: "—", color = C.muted, fontSize = 13.sp)
            }
        }
    }
    Note("Latência entre cada jogador e o servidor. Sem medição, mostramos —. Toque num jogador para ver o motivo quando a skin não puder ser exibida.")
}

@Composable private fun BottomBar(page: Page, onPage: (Page) -> Unit) {
    val tabs = listOf(
        Triple(Page.Home, "Início", Icons.Outlined.Home),
        Triple(Page.Worlds, "Mundos", Icons.Outlined.Inventory2),
        Triple(Page.Console, "Console", Icons.Outlined.Terminal),
        Triple(Page.Settings, "Ajustes", Icons.Outlined.Settings),
    )
    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(27.dp)).background(C.card).padding(4.dp)) {
        tabs.forEach { (p, label, icon) ->
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (page == p) C.soft else Color.Transparent)
                    .clickable { onPage(p) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = if (page == p) C.green else C.ink, modifier = Modifier.size(24.dp))
                Text(label, fontSize = 12.sp, color = if (page == p) C.green else C.muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun hourOf(millis: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(millis))
