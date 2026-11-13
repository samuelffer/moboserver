package com.moboserver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.ServerConfig
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.Store
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val saved by Store.config.collectAsState()
    val runtime by ServerController.state.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved) }
    var savedFlash by remember { mutableStateOf(false) }

    val busy = runtime.status != com.moboserver.app.core.ServerStatus.STOPPED &&
        runtime.status != com.moboserver.app.core.ServerStatus.ERROR

    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(2000)
            savedFlash = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Ajustes do servidor", color = MoboColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Estas opções valem para o próximo início do servidor.",
            color = MoboColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        MoboCard {
            SectionTitle("Identificação")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.serverName,
                onValueChange = { draft = draft.copy(serverName = it.take(40)) },
                label = { Text("Nome do servidor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.port.toString(),
                onValueChange = { text ->
                    val digits = text.filter { it.isDigit() }.take(5)
                    val value = digits.toIntOrNull()
                    if (value == null) {
                        draft = draft.copy(port = 19132)
                    } else {
                        draft = draft.copy(port = value)
                    }
                },
                label = { Text("Porta (UDP)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                supportingText = { Text("Padrão do Minecraft: 19132", fontSize = 11.sp) },
            )
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Jogadores")
            Spacer(Modifier.height(6.dp))
            SliderRow(
                label = "Máximo de jogadores",
                value = "${draft.maxPlayers}",
                sliderValue = draft.maxPlayers.toFloat(),
                range = 1f..10f,
                steps = 8,
                onChange = { draft = draft.copy(maxPlayers = it.toInt()) },
            )
            Text(
                "Para celulares mais fracos, 2 a 4 jogadores é o recomendado.",
                color = MoboColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            SliderRow(
                label = "Distância de visão (chunks)",
                value = "${draft.viewDistance}",
                sliderValue = draft.viewDistance.toFloat(),
                range = 4f..16f,
                steps = 11,
                onChange = { draft = draft.copy(viewDistance = it.toInt()) },
            )
            Text(
                "Valores menores economizam memória e processamento do celular.",
                color = MoboColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Jogo")
            Spacer(Modifier.height(10.dp))
            Text("Modo de jogo padrão", color = MoboColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectablePill(
                    text = "Sobrevivência",
                    selected = draft.gameMode == "survival",
                    onClick = { draft = draft.copy(gameMode = "survival") },
                )
                SelectablePill(
                    text = "Criativo",
                    selected = draft.gameMode == "creative",
                    onClick = { draft = draft.copy(gameMode = "creative") },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectablePill(
                    text = "Aventura",
                    selected = draft.gameMode == "adventure",
                    onClick = { draft = draft.copy(gameMode = "adventure") },
                )
                SelectablePill(
                    text = "Espectador",
                    selected = draft.gameMode == "spectator",
                    onClick = { draft = draft.copy(gameMode = "spectator") },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("Dificuldade", color = MoboColors.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectablePill(
                    text = "Pacífico",
                    selected = draft.difficulty == "peaceful",
                    onClick = { draft = draft.copy(difficulty = "peaceful") },
                )
                SelectablePill(
                    text = "Fácil",
                    selected = draft.difficulty == "easy",
                    onClick = { draft = draft.copy(difficulty = "easy") },
                )
                SelectablePill(
                    text = "Normal",
                    selected = draft.difficulty == "normal",
                    onClick = { draft = draft.copy(difficulty = "normal") },
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectablePill(
                    text = "Difícil",
                    selected = draft.difficulty == "hard",
                    onClick = { draft = draft.copy(difficulty = "hard") },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Segurança e rede")
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "Exigir login Xbox",
                description = "Recomendado. Só entra quem tem conta Xbox Live válida.",
                checked = draft.authEnabled,
                onChange = { draft = draft.copy(authEnabled = it) },
            )
            SwitchRow(
                title = "Liberar porta automaticamente",
                description = "Tenta abrir a porta no roteador via UPnP ao ligar o servidor.",
                checked = draft.autoForward,
                onChange = { draft = draft.copy(autoForward = it) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val port = draft.port.coerceIn(1024, 65535)
                Store.save(draft.copy(port = port))
                savedFlash = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (savedFlash) "Configurações salvas!" else "Salvar configurações", fontWeight = FontWeight.SemiBold)
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Text(
                "O servidor está ligado. Desligue e religue para aplicar as mudanças.",
                color = MoboColors.Warning,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Desempenho do celular")
            Spacer(Modifier.height(8.dp))
            Text(
                "Para o servidor continuar rodando com a tela apagada, libere o MoboServer da otimização de bateria.",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (ignored: Exception) {
                                // nothing else we can do
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Liberar bateria", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:${context.packageName}")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // ignore
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Config. do app", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Sobre")
            Spacer(Modifier.height(8.dp))
            InfoLine("Versão", appVersion())
            InfoLine("Minecraft", runtime.info?.mcVersion ?: "Bedrock 1.26.60")
            InfoLine("Servidor", "Dragonfly 0.11.6 (MIT)")
            InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Spacer(Modifier.height(6.dp))
            Text(
                "O MoboServer roda um servidor de Minecraft Bedrock dentro do celular, sem custo e sem serviços de terceiros. " +
                    "O consumo depende do celular: quanto mais forte o aparelho, mais jogadores ele aguenta.",
                color = MoboColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MoboColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = MoboColors.Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = sliderValue.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MoboColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = MoboColors.TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MoboColors.LimeDark),
        )
    }
}

private fun appVersion(): String {
    return try {
        com.moboserver.app.BuildConfig.VERSION_NAME
    } catch (e: Exception) {
        "1.0.0"
    }
}
