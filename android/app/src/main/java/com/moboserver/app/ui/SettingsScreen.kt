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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.Bans
import com.moboserver.app.core.Diagnostics
import com.moboserver.app.core.ServerConfig
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                title = "Modo aberto se o login falhar",
                description = "Padrão: o servidor NÃO sobe quando a verificação de conta da Microsoft " +
                    "está inacessível. Ligue para permitir entrar sem conta nesse caso.",
                checked = draft.allowOpenMode,
                onChange = { draft = draft.copy(allowOpenMode = it) },
            )
            SwitchRow(
                title = "Liberar porta automaticamente",
                description = "Tenta abrir a porta no roteador via UPnP ao ligar o servidor.",
                checked = draft.autoForward,
                onChange = { draft = draft.copy(autoForward = it) },
            )
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Configurações avançadas")
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "Compatibilidade RakNet",
                description = "Desativada por padrão. Liga o transporte antigo (UDP) usado pela aba LAN do " +
                    "Minecraft. Sem ela, o servidor NÃO aparece na aba LAN: quem entra usa endereço e porta " +
                    "pelo NetherNet. Ligue só se precisar da descoberta automática em rede local.",
                checked = draft.rakNet,
                onChange = { draft = draft.copy(rakNet = it) },
            )
            SwitchRow(
                title = "Diagnóstico avançado",
                description = "Mostra as linhas DEBUG no console. Desligado, o console exibe INFO, avisos e erros.",
                checked = draft.advancedDiagnostics,
                onChange = { draft = draft.copy(advancedDiagnostics = it) },
            )
            Spacer(Modifier.height(8.dp))
            FormRow(label = "Endereço externo (domínio opcional)") {
                OutlinedTextField(
                    value = draft.externalHost,
                    onValueChange = { draft = draft.copy(externalHost = it.trim().take(120)) },
                    placeholder = { Text("casa.exemplo.com", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Text(
                "Só exibição: o app não cria túnel NetherNet nem publica entrada externa sozinho. " +
                    "O relay atual encaminha UDP/RakNet, então um domínio apontando para ele não atende " +
                    "quem entra por NetherNet.",
                color = MoboColors.TextSecondary,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        MoboCard {
            SectionTitle("Acesso sem mexer no roteador (túnel)")
            Spacer(Modifier.height(6.dp))
            Text(
                "Opcional. Com um relay do MoboServer rodando em um servidor público, o celular " +
                    "abre uma conexão de saída e recebe jogadores de qualquer rede, inclusive atrás " +
                    "de CGNAT. O endereço público aparece na tela inicial quando o túnel conecta.",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.relayAddr,
                onValueChange = { draft = draft.copy(relayAddr = it.trim().take(80)) },
                label = { Text("Relay (host:porta)") },
                placeholder = { Text("relay.exemplo.com:9000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                supportingText = { Text("Vazio = túnel desligado (só LAN e port forwarding)", fontSize = 11.sp) },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.relayToken,
                onValueChange = { draft = draft.copy(relayToken = it.trim().take(80)) },
                label = { Text("Token do relay") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "Usar TLS no túnel",
                description = "Recomendado com domínio e certificado válidos no relay.",
                checked = draft.relayTls,
                onChange = { draft = draft.copy(relayTls = it) },
            )
        }

        Spacer(Modifier.height(12.dp))
        BannedPlayersCard(runtimeStatus = runtime.status)

        Spacer(Modifier.height(12.dp))
        DiagnosticsCard()

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

@Composable
private fun BannedPlayersCard(runtimeStatus: com.moboserver.app.core.ServerStatus) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bans by remember { mutableStateOf(Bans.list(context)) }

    LaunchedEffect(runtimeStatus) {
        bans = Bans.list(context)
    }

    MoboCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Jogadores banidos")
            Spacer(Modifier.weight(1f))
            Text("${bans.size}", color = MoboColors.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (bans.isEmpty()) {
            Text(
                "Ninguém banido. Você pode banir um jogador online na tela inicial.",
                color = MoboColors.TextSecondary,
                fontSize = 12.sp,
            )
        } else {
            bans.forEach { ban ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ban.name.ifBlank { "Jogador sem nome" },
                            color = MoboColors.TextPrimary,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = ban.xuid,
                            color = MoboColors.TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = {
                        ServerController.unban(ban.xuid)
                        scope.launch {
                            delay(900)
                            bans = Bans.list(context)
                        }
                    }) {
                        Text("Desbanir", color = MoboColors.Lime, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val summary = remember {
        Diagnostics.summary(context) + "\n" + Diagnostics.freeSpace(context)
    }

    MoboCard {
        SectionTitle("Diagnóstico")
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary,
            color = MoboColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                running = true
                scope.launch {
                    result = withContext(Dispatchers.IO) { Diagnostics.testBinary(context) }
                    running = false
                }
            },
            enabled = !running,
        ) {
            Text(if (running) "Testando..." else "Testar servidor embutido", fontSize = 13.sp)
        }
        result?.let { output ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = output,
                color = MoboColors.Lime,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
