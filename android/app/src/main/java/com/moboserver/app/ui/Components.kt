package com.moboserver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun PlayerAvatar(name: String, size: Int = 38) {
    val initial = name.trim().take(1).uppercase().ifBlank { "?" }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MoboColors.LimeSoft)
            .border(1.dp, MoboColors.LimeDark, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MoboColors.Lime,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}
