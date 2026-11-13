package com.moboserver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.LogLine
import com.moboserver.app.core.ServerController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConsoleScreen() {
    val state by ServerController.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var lastSize by remember { mutableStateOf(0) }

    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty() && state.logs.size != lastSize) {
            lastSize = state.logs.size
            listState.scrollToItem(state.logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Console", color = MoboColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Tudo o que acontece no servidor aparece aqui.",
                    color = MoboColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(ServerController.logText())) }) {
                Text("Copiar", color = MoboColors.TextSecondary, fontSize = 12.sp)
            }
            TextButton(onClick = { ServerController.clearLogs() }) {
                Text("Limpar", color = MoboColors.TextSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF080B07))
                .border(1.dp, MoboColors.Outline, RoundedCornerShape(20.dp)),
        ) {
            if (state.logs.isEmpty()) {
                Text(
                    "Sem mensagens por enquanto.",
                    color = MoboColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.logs) { line -> LogRow(line) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(200) },
                placeholder = { Text("Mensagem para todos no chat", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    ServerController.say(input)
                    input = ""
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MoboColors.LimeSoft),
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Enviar mensagem",
                    tint = MoboColors.Lime,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.level.lowercase()) {
        "error" -> MoboColors.Danger
        "warn" -> MoboColors.Warning
        "debug" -> MoboColors.TextSecondary
        else -> MoboColors.TextPrimary
    }
    val time = remember(line.time) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(line.time))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = time,
            color = MoboColors.TextSecondary.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = line.message,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
