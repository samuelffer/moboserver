package com.moboserver.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.PingSample
import com.moboserver.app.core.RuntimeState
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.ServerStatus

/**
 * Aba Conexão: latência medida dos jogadores, separada das verificações de rede
 * do próprio aparelho (sondagem da LAN e túnel/relay). Misturar as duas coisas
 * faria o número dos jogadores parecer um teste de internet.
 */
@Composable
fun ConnectionScreen() {
    val state by ServerController.state.collectAsState()
    val running = state.status == ServerStatus.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Conexão", color = MoboColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Latência dos jogadores e, separado, os testes de rede feitos pelo próprio celular.",
            color = MoboColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        PlayerPingCard(state = state, running = running)
        Spacer(Modifier.height(12.dp))
        TransportsCard(state = state)
        Spacer(Modifier.height(12.dp))
        NetworkTestsCard(state = state)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PlayerPingCard(state: RuntimeState, running: Boolean) {
    val samples = state.pingHistory
    val withPing = samples.filter { it.median > 0 }
    val latest = samples.lastOrNull()
    val online = state.players
    val measured = online.filter { it.pingMs > 0 }

    MoboCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                tint = MoboColors.Lime,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            SectionTitle("Ping dos jogadores")
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    !running -> "servidor desligado"
                    online.isEmpty() -> "0 jogadores"
                    measured.isEmpty() -> "sem amostras"
                    else -> "${measured.size} de ${online.size} medindo"
                },
                color = MoboColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))

        when {
            !running -> {
                Text(
                    "Ligue o servidor para ver a latência de quem está jogando.",
                    color = MoboColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
            online.isEmpty() -> {
                Text(
                    "Nenhum jogador online agora. Não há latência para medir " +
                        "(os testes de rede abaixo são do próprio celular e continuam disponíveis).",
                    color = MoboColors.TextSecondary,
                    fontSize = 13.sp,
                )
                if (state.avatars.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Miniaturas em cache: ${state.avatars.size}.",
                        color = MoboColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
            measured.isEmpty() -> {
                Text(
                    "O servidor está com jogadores, mas o transporte ativo não informa o RTT deles. " +
                        "Prefira mostrar \\"Sem medição\\" a inventar um valor.",
                    color = MoboColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
            else -> {
                val median = withPing.lastOrNull()?.median ?: -1
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (median > 0) "$median" else "—",
                        color = MoboColors.Lime,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("ms (mediana)", color = MoboColors.TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                val window = withPing
                if (window.isNotEmpty()) {
                    val min = window.minOf { it.min }
                    val max = window.maxOf { it.max }
                    Text(
                        "Faixa recente: $min–$max ms • ${window.size} amostra(s) nos últimos 60 s",
                        color = MoboColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PingSparkline(samples = samples)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = sourceLabel(online.map { it.transport }.filter { it.isNotBlank() }.distinct()),
                    color = MoboColors.TextSecondary,
                    fontSize = 10.sp,
                )
            }
        }

        if (online.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            online.forEach { player ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerName(player.name, fontSize = 12)
                    Spacer(Modifier.weight(1f))
                    PingBars(pingMs = player.pingMs)
                }
            }
        }
    }
}

/** Gráfico simples dos últimos 60 s: uma linha por valor mediano válido. */
@Composable
private fun PingSparkline(samples: List<PingSample>) {
    val points = remember(samples) { samples.filter { it.median > 0 }.takeLast(20) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoboColors.Background)
            .padding(6.dp),
    ) {
        if (points.size < 2) {
            Text(
                "Amostras insuficientes para o gráfico.",
                color = MoboColors.TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxValue = points.maxOf { it.max }.coerceAtLeast(1)
                val stepX = size.width / (points.size - 1).coerceAtLeast(1)
                for (index in 1 until points.size) {
                    val previous = points[index - 1]
                    val current = points[index]
                    val x1 = stepX * (index - 1)
                    val x2 = stepX * index
                    val y1 = size.height - (previous.median.toFloat() / maxValue) * size.height
                    val y2 = size.height - (current.median.toFloat() / maxValue) * size.height
                    drawLine(
                        color = MoboColors.Lime,
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportsCard(state: RuntimeState) {
    val info = state.info
    MoboCard {
        SectionTitle("Transportes")
        Spacer(Modifier.height(8.dp))
        if (info == null) {
            Text(
                "Nenhuma sessão ativa. O padrão é NetherNet (entrada por endereço); " +
                    "RakNet é opcional em Ajustes → Configurações avançadas e é o que alimenta a aba LAN.",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
            return@MoboCard
        }
        val transports = info.transports.ifEmpty {
            buildList {
                if (info.raknetOk) add("raknet")
                if (info.signalingOk) add("nethernet")
            }
        }
        InfoLine("NetherNet", if (transports.contains("nethernet")) "ligado" else "desligado")
        InfoLine("RakNet", if (transports.contains("raknet")) "ligado (aba LAN)" else "desligado")
        if (info.signalingLatencyMs >= 0) {
            InfoLine("GET /v1/join", "${info.signalingLatencyMs} ms")
        }
        Text(
            "O protocolo não informa por qual transporte cada jogador entrou: a origem mostrada " +
                "na lista é uma inferência pelo endereço (selo LAN) ou ausente.",
            color = MoboColors.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun NetworkTestsCard(state: RuntimeState) {
    MoboCard {
        SectionTitle("Rede local e relay")
        Spacer(Modifier.height(8.dp))
        val targets = state.info?.lanTargets.orEmpty()
        if (targets.isEmpty()) {
            InfoLine("Sondagem LAN", "ainda não executada", MoboColors.TextSecondary)
        } else {
            targets.forEach { target ->
                InfoLine(
                    label = target.iface.ifBlank { "interface" },
                    value = "${target.ip} — ${if (target.ok) "respondeu" else "sem resposta"}",
                    valueColor = if (target.ok) MoboColors.Lime else MoboColors.TextSecondary,
                    mono = true,
                )
            }
        }
        val tunnel = state.exposure.tunnelState
        InfoLine(
            label = "Túnel/relay",
            value = when (tunnel) {
                null -> "não configurado"
                "connected" -> state.exposure.tunnelAddress?.let { "conectado: $it" } ?: "conectado"
                else -> tunnel
            },
        )
        Text(
            "O relay atual encaminha UDP/RakNet. Entrada NetherNet por outra rede exigiria sinalização " +
                "HTTP + ICE/WebRTC no relay, o que não está implementado nem validado.",
            color = MoboColors.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

private fun sourceLabel(transports: List<String>): String = when {
    transports.isEmpty() -> "O servidor não informou qual transporte mediu o RTT."
    else -> "Medição fornecida pelo transporte: ${transports.joinToString(", ")}."
}
