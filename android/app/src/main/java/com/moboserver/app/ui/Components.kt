package com.moboserver.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.PlayerAvatar
import com.moboserver.app.core.PlayerInfo

/** Rounded dark card used across the app. */
@Composable
fun MoboCard(
    modifier: Modifier = Modifier,
    color: Color = MoboColors.Surface,
    borderColor: Color = MoboColors.Outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = MoboColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Label + value pair, used for network information. */
@Composable
fun InfoLine(
    label: String,
    value: String,
    valueColor: Color = MoboColors.TextPrimary,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MoboColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.width(108.dp),
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Selectable pill used instead of experimental chip APIs. */
@Composable
fun SelectablePill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = if (selected) MoboColors.LimeSoft else MoboColors.SurfaceHigh
    val border = if (selected) MoboColors.Lime else MoboColors.Outline
    val textColor = if (selected) MoboColors.Lime else MoboColors.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(text = label, color = MoboColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * Cabeça do jogador. Usa a miniatura enviada pelo servidor a partir da skin da
 * sessão; quando ela não existe (Persona, geometria personalizada, skin
 * inconsistente) mostra o avatar padrão com a inicial do nome.
 *
 * [lan] desenha um selo discreto: o endereço do jogador está na mesma sub-rede
 * do aparelho, o que na prática significa que ele entrou pela rede local.
 */
@Composable
fun PlayerAvatar(
    name: String,
    avatar: PlayerAvatar? = null,
    size: Int = 38,
    lan: Boolean = false,
) {
    val image = avatar?.image
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoboColors.LimeSoft)
            .border(1.dp, MoboColors.LimeDark, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Skin de $name",
                contentScale = ContentScale.Crop,
                // Pixelado de propósito: a miniatura tem 64x64 e precisa manter
                // as bordas nítidas em vez de virar um borrão.
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "?" },
                color = MoboColors.Lime,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        if (lan) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MoboColors.Background.copy(alpha = 0.85f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Text("LAN", color = MoboColors.TextSecondary, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Nome do jogador na fonte pixelada, como no Minecraft. */
@Composable
fun PlayerName(name: String, fontSize: Int = 13) {
    Text(
        text = name,
        color = MoboColors.TextPrimary,
        fontSize = fontSize.sp,
        fontFamily = MoboFonts.Pixel,
        fontWeight = FontWeight.Normal,
    )
}

/**
 * Três barras com o ping em ms. Sem medição (ping <= 0) mostra um ícone neutro
 * e o texto "Sem medição" em vez de sugerir uma latência boa ou ruim.
 */
@Composable
fun PingBars(pingMs: Long, compact: Boolean = false) {
    if (pingMs <= 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                tint = MoboColors.TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (compact) "—" else "Sem medição",
                color = MoboColors.TextSecondary,
                fontSize = if (compact) 10.sp else 11.sp,
            )
        }
        return
    }
    val bars = when {
        pingMs <= 80 -> 3
        pingMs <= 160 -> 2
        else -> 1
    }
    val color = when (bars) {
        3 -> MoboColors.Lime
        2 -> MoboColors.Warning
        else -> MoboColors.Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (index in 0 until 3) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height((4 + index * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (index < bars) color else MoboColors.Outline),
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = "$pingMs ms",
            color = color,
            fontSize = if (compact) 9.sp else 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Uma linha de jogador: cabeça à esquerda, nick no centro em fonte pixelada e
 * ping à direita. [onManage] mantém expulsar/banir acessíveis na Início.
 */
@Composable
fun PlayerRow(
    player: PlayerInfo,
    avatar: PlayerAvatar?,
    lan: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAvatar(name = player.name, avatar = avatar, lan = lan)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            PlayerName(player.name)
            if (player.device.isNotBlank()) {
                Text(
                    text = player.device,
                    color = MoboColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        PingBars(pingMs = player.pingMs)
    }
}
