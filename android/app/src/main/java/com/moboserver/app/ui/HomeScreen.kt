package com.moboserver.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.R
import com.moboserver.app.core.Exposure
import com.moboserver.app.core.Net
import com.moboserver.app.core.PlayerInfo
import com.moboserver.app.core.RuntimeState
import com.moboserver.app.core.ServerConfig
import com.moboserver.app.core.ServerStatus
import com.moboserver.app.core.sameSubnet
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: RuntimeState,
    config: ServerConfig,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onForwardPort: () -> Unit,
    onCheckAccess: () -> Unit,
    onKick: (PlayerInfo) -> Unit,
    onBan: (PlayerInfo) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    val running = state.status == ServerStatus.RUNNING
    val port = state.info?.port ?: config.port
    val maxPlayers = state.info?.maxPlayers ?: config.maxPlayers
    val lanIp = state.exposure.lanIp
    val publicIp = state.exposure.publicIp

    var playerToRemove by remember { mutableStateOf<PlayerInfo?>(null) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val startedAt = state.startedAt
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = System.currentTimeMillis()
            delay(20_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Header(state)
        Spacer(Modifier.height(16.dp))

        StatusCard(
            state = state,
            port = port,
            maxPlayers = maxPlayers,
            lanIp = lanIp,
            publicIp = publicIp,
            upnpOk = state.exposure.upnpOk,
            uptimeMillis = if (startedAt != null) now - startedAt else 0L,
            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
            onStart = onStart,
            onStop = onStop,
        )

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            MoboCard(color = Color(0xFF2A1414), borderColor = Color(0xFF5C2A2A)) {
                SectionTitle("Atenção", modifier = Modifier)
                Spacer(Modifier.height(8.dp))
                Text(state.error, color = MoboColors.Danger, fontSize = 13.sp)
            }
        }

        if (running && state.info?.authEnabled == false) {
            Spacer(Modifier.height(12.dp))
            MoboCard(color = Color(0xFF2A2410), borderColor = Color(0xFF5C4A1E)) {
                SectionTitle("Sem verificação de conta", modifier = Modifier)
                Spacer(Modifier.height(8.dp))
                Text(
                    "O serviço de login do Xbox Live não respondeu, então o servidor abriu em modo aberto: " +
                        "qualquer pessoa com o endereço e a porta entra, inclusive de outras plataformas. " +
                        "Se quiser exigir conta da Microsoft, tente novamente em outra rede.",
                    color = MoboColors.Warning,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        NegotiationCard(state = state, running = running, port = port)

        Spacer(Modifier.height(12.dp))
        PlayersCard(
            state = state,
            maxPlayers = maxPlayers,
            running = running,
            onKick = { playerToRemove = it },
        )

        Spacer(Modifier.height(12.dp))
        ExposureCard(
            state = state,
            running = running,
            port = port,
            onForward = onForwardPort,
            onCheck = onCheckAccess,
            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
        )

        Spacer(Modifier.height(12.dp))
        HowToCard(port = port, lanIp = lanIp, publicIp = publicIp, running = running)

        Spacer(Modifier.height(28.dp))
    }

    val target = playerToRemove
    if (target != null) {
        AlertDialog(
            onDismissRequest = { playerToRemove = null },
            title = { Text("Gerenciar ${target.name}") },
            text = {
                Text(
                    "Expulsar remove o jogador do servidor, mas ele pode voltar. " +
                        "Banir impede que ele entre novamente.",
                    color = MoboColors.TextSecondary,
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        onKick(target)
                        playerToRemove = null
                    }) { Text("Expulsar") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        onBan(target)
                        playerToRemove = null
                    }) {
                        Text("Banir", color = MoboColors.Danger)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { playerToRemove = null }) { Text("Fechar") }
            },
            containerColor = MoboColors.SurfaceHigh,
        )
    }
}

@Composable
private fun Header(state: RuntimeState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MoboColors.LimeSoft)
                .border(1.dp, MoboColors.LimeDark, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "MoboServer",
                color = MoboColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = statusHeadline(state),
                color = statusColor(state.status),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: RuntimeState,
    port: Int,
    maxPlayers: Int,
    lanIp: String?,
    publicIp: String?,
    upnpOk: Boolean,
    uptimeMillis: Long,
    onCopy: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = state.status == ServerStatus.RUNNING
    val starting = state.status == ServerStatus.STARTING
    val stopping = state.status == ServerStatus.STOPPING
    val addressIp = lanIp ?: publicIp
    val version = state.info?.mcVersion
    val protocol = state.info?.protocol

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1E2C17), Color(0xFF12180F)),
                ),
            )
            .border(1.dp, MoboColors.Outline, RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusColor(state.status), 9)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusHeadline(state),
                    color = MoboColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (running && uptimeMillis > 0) {
                    Text(
                        text = formatUptime(uptimeMillis),
                        color = MoboColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Mundo: ${state.worldName ?: "nenhum selecionado"}",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(16.dp))
            Text("Mesma rede (Wi-Fi, LAN)", color = MoboColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = addressIp ?: "sem Wi-Fi",
                    color = MoboColors.Lime,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                if (addressIp != null) {
                    IconButton(onClick = { onCopy(addressIp) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copiar IP",
                            tint = MoboColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Porta", color = MoboColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = port.toString(),
                    color = MoboColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(onClick = { onCopy(port.toString()) }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copiar porta",
                        tint = MoboColors.TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgePill(
                    when {
                        version == null -> "Bedrock"
                        protocol == null -> "Bedrock $version"
                        else -> "Bedrock $version • protocolo $protocol"
                    },
                )
                BadgePill("${state.players.size}/$maxPlayers jogadores")
                BadgePill("UDP $port")
            }

            Spacer(Modifier.height(18.dp))
            if (running || stopping) {
                Button(
                    onClick = onStop,
                    enabled = !stopping,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF33191A),
                        contentColor = MoboColors.Danger,
                    ),
                ) {
                    if (stopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MoboColors.Danger,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Desligando...")
                    } else {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Desligar servidor", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !starting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (starting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MoboColors.Background,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Ligando servidor...")
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Ligar servidor", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = text, color = MoboColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun PlayersCard(
    state: RuntimeState,
    maxPlayers: Int,
    running: Boolean,
    onKick: (PlayerInfo) -> Unit,
) {
    val players = state.players
    // Endereços locais conhecidos: usados só para o selo "LAN" (o protocolo não
    // informa por qual transporte o jogador entrou).
    val lanIps = buildList {
        state.info?.lanTargets?.forEach { if (it.ip.isNotBlank()) add(it.ip) }
        state.exposure.lanIp?.let { add(it) }
    }
    MoboCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MoboColors.Lime,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            SectionTitle("Jogadores online")
            Spacer(Modifier.weight(1f))
            Text(
                text = "${players.size}/$maxPlayers",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (players.isEmpty()) {
            Text(
                text = if (running) {
                    "Ninguém conectado ainda. Compartilhe o endereço acima com seus amigos."
                } else {
                    "Ligue o servidor para receber jogadores."
                },
                color = MoboColors.TextSecondary,
                fontSize = 13.sp,
            )
        } else {
            players.forEach { player ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PlayerRow(
                            player = player,
                            avatar = state.avatars[player.key],
                            lan = lanIps.any { ip -> sameSubnet(ip, player.addr) },
                            onClick = { onKick(player) },
                        )
                    }
                    TextButton(onClick = { onKick(player) }) {
                        Text("Gerenciar", color = MoboColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExposureCard(
    state: RuntimeState,
    running: Boolean,
    port: Int,
    onForward: () -> Unit,
    onCheck: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val exposure = state.exposure
    MoboCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = MoboColors.Lime,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            SectionTitle("Acesso pela internet")
        }
        Spacer(Modifier.height(8.dp))
        if (!running) {
            Text(
                "Ligue o servidor para descobrir o endereço público e testar o acesso de fora.",
                color = MoboColors.TextSecondary,
                fontSize = 13.sp,
            )
            return@MoboCard
        }

        val situation = when (exposure.externalVerified) {
            true -> "confirmado por um verificador externo" to MoboColors.Lime
            false -> "não confirmado no último teste" to MoboColors.Warning
            else -> "ainda não verificado" to MoboColors.TextSecondary
        }
        InfoLine(label = "Acesso externo", value = situation.first, valueColor = situation.second)
        InfoLine(
            label = "IP observado",
            value = exposure.publicIp?.let { "$it:$port" } ?: "ainda não descoberto",
            mono = true,
        )
        if (exposure.wanIp != null && exposure.wanIp != exposure.publicIp) {
            InfoLine(label = "Roteador diz", value = exposure.wanIp, mono = true)
        }
        InfoLine(
            label = "Regra no roteador",
            value = when {
                exposure.upnpOk -> "aceita via UPnP (não garante acesso)"
                exposure.upnpMessage != null -> "não configurada"
                else -> "não verificada"
            },
            valueColor = when {
                exposure.upnpOk -> MoboColors.Lime
                exposure.upnpMessage != null -> MoboColors.Warning
                else -> MoboColors.TextSecondary
            },
        )

        val tunnelState = exposure.tunnelState
        if (tunnelState != null) {
            InfoLine(
                label = "Túnel",
                value = when (tunnelState) {
                    "connected" -> exposure.tunnelAddress ?: "conectado"
                    "connecting" -> "conectando..."
                    "reconnecting" -> "reconectando..."
                    else -> tunnelState
                },
                mono = tunnelState == "connected",
                valueColor = if (tunnelState == "connected") MoboColors.Lime else MoboColors.TextSecondary,
            )
        }

        exposure.upnpMessage?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, color = MoboColors.TextSecondary, fontSize = 12.sp)
        }
        exposure.externalCheck?.let { check ->
            Spacer(Modifier.height(6.dp))
            Text(check, color = MoboColors.Info, fontSize = 12.sp)
        }

        val hypotheses = natHypotheses(exposure)
        if (hypotheses.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            hypotheses.forEach { note ->
                Text("• $note", color = MoboColors.TextSecondary, fontSize = 12.sp)
            }
        }

        if (!exposure.upnpOk && exposure.upnpMessage != null && exposure.lanIp != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Sem liberação automática: cadastre a regra no roteador",
                color = MoboColors.Warning,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "1. Abra o painel do roteador no navegador (em geral o endereço do gateway).\n" +
                    "2. Procure \"Port forwarding\" / \"Redirecionamento de portas\".\n" +
                    "3. Regra nova: protocolo UDP, porta externa e interna $port, " +
                    "IP de destino ${exposure.lanIp}.\n" +
                    "4. Salve e use \"Testar acesso\".",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
            TextButton(onClick = { onCopy("UDP $port -> ${exposure.lanIp}") }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = MoboColors.Lime,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Copiar dados da regra", color = MoboColors.Lime, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onForward,
                enabled = !exposure.working,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Liberar no roteador", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onCheck,
                enabled = !exposure.working,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Testar acesso", fontSize = 12.sp)
            }
        }
        if (exposure.working) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MoboColors.Lime,
                )
                Spacer(Modifier.width(8.dp))
                Text("Trabalhando...", color = MoboColors.TextSecondary, fontSize = 12.sp)
            }
        }
        val tunnel = exposure.tunnelAddress
        if (exposure.tunnelState == "connected" && tunnel != null) {
            TextButton(onClick = { onCopy(tunnel) }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = MoboColors.Lime,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Copiar endereço do túnel", color = MoboColors.Lime, fontSize = 12.sp)
            }
        }
        val public = exposure.publicIp
        if (public != null) {
            TextButton(onClick = { onCopy("$public:$port") }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint = MoboColors.Lime,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Copiar endereço público", color = MoboColors.Lime, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Observations about the network path. They are hypotheses, not verdicts: the
 * app cannot read the router's NAT table nor the carrier's configuration.
 */
private fun natHypotheses(exposure: Exposure): List<String> {
    val notes = ArrayList<String>()
    val public = exposure.publicIp
    if (public != null) {
        if (Net.isPrivateIp(public)) {
            notes.add("O endereço visto de fora é privado, então existe NAT antes do seu roteador.")
        } else if (Net.isCarrierGradeNat(public)) {
            notes.add("O endereço visto de fora está em 100.64/10, indício de CGNAT do provedor.")
        }
    }
    if (exposure.wanIp != null && public != null && exposure.wanIp != public) {
        notes.add("O roteador informa ${exposure.wanIp} e o STUN vê $public: provável NAT duplo.")
    }
    return notes
}

@Composable
private fun HowToCard(port: Int, lanIp: String?, publicIp: String?, running: Boolean) {
    MoboCard {
        SectionTitle("Como seus amigos entram")
        Spacer(Modifier.height(8.dp))
        Step(1, "O cliente precisa ser Minecraft Bedrock (Windows 10/11, celular ou console). O Minecraft Java não conecta em servidor Bedrock.")
        Step(2, "Jogar → Servidores → Adicionar servidor.")
        Step(3, "Endereço: ${lanIp ?: publicIp ?: "IP do celular"}   •   Porta: $port")
        Step(4, "Na mesma rede Wi-Fi use o IP local. Fora dela, o acesso depende do roteador ou de um túnel (área acima).")
        Step(5, "Xbox, PlayStation e Switch não digitam endereço: eles aparecem sozinhos na aba LAN quando estão no mesmo Wi-Fi.")
        if (!running) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Dica: mantenha o app aberto ou na notificação para o servidor continuar rodando.",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MoboColors.LimeSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MoboColors.Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text = text, color = MoboColors.TextSecondary, fontSize = 12.sp)
    }
}

private fun statusHeadline(state: RuntimeState): String = when (state.status) {
    ServerStatus.STOPPED -> "Servidor desligado"
    ServerStatus.STARTING -> "Iniciando servidor..."
    ServerStatus.RUNNING -> "Servidor online"
    ServerStatus.STOPPING -> "Desligando..."
    ServerStatus.ERROR -> "Precisa de atenção"
}

private fun statusColor(status: ServerStatus): Color = when (status) {
    ServerStatus.RUNNING -> MoboColors.Lime
    ServerStatus.STARTING, ServerStatus.STOPPING -> MoboColors.Warning
    ServerStatus.ERROR -> MoboColors.Danger
    ServerStatus.STOPPED -> MoboColors.TextSecondary
}

fun formatUptime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "online há ${hours}h ${minutes}min"
        minutes > 0 -> "online há ${minutes}min"
        else -> "online há ${seconds}s"
    }
}
/**
 * Mostra as etapas da entrada por endereco separadamente. Responder HTTP nao e o
 * mesmo que negociar a conexao, e nenhuma das duas coisas prova que o cliente
 * entrou no mundo: apenas o Bedrock, conectando de verdade, prova isso.
 */
@Composable
private fun NegotiationCard(state: RuntimeState, running: Boolean, port: Int) {
    val info = state.info
    MoboCard {
        SectionTitle("Entrada pelo endereço")
        Spacer(Modifier.height(8.dp))
        if (!running || info == null) {
            Text(
                "Ligue o servidor para ver as etapas de entrada por endereço.",
                color = MoboColors.TextSecondary,
                fontSize = 13.sp,
            )
            return@MoboCard
        }
        StageLine(
            label = "Servidor RakNet (UDP $port)",
            ok = info.raknetOk,
            detail = if (info.raknetOk) "escutando (descoberta e jogo)" else "não abriu a porta",
        )
        Spacer(Modifier.height(6.dp))
        StageLine(
            label = "Sinalização HTTP (GET /v1/join)",
            ok = info.signalingOk,
            detail = if (info.signalingOk) {
                "200 • nome \"${info.signalingName}\""
            } else {
                "sem resposta — quem digitar o endereço demora e cai no RakNet"
            },
        )
        Spacer(Modifier.height(6.dp))
        StageLine(
            label = "Negociação ICE",
            ok = info.iceOk,
            detail = if (info.iceOk) {
                "${info.iceCandidates} candidato(s) local(is) em " +
                    info.iceHosts.joinToString(", ").ifBlank { "?" } +
                    " • ${info.iceInterfaceCount} endereço(s) de interface injetados"
            } else {
                info.iceError.ifBlank { "não foi possível criar o agente ICE" }
            },
        )
        Spacer(Modifier.height(6.dp))
        StageLine(
            label = "Entrada no mundo",
            ok = null,
            detail = "só o cliente Bedrock comprova: adicionar o endereço do celular na porta $port e entrar",
        )
    }
}

@Composable
private fun StageLine(label: String, ok: Boolean?, detail: String) {
    val color = when (ok) {
        true -> MoboColors.Lime
        false -> MoboColors.Danger
        null -> MoboColors.TextSecondary
    }
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(color, 8)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = MoboColors.TextPrimary, fontSize = 13.sp)
            Text(detail, color = MoboColors.TextSecondary, fontSize = 12.sp)
        }
    }
}
