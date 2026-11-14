package com.moboserver.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moboserver.app.core.BanEntry
import com.moboserver.app.core.ServerConfig
import com.moboserver.design.moboPalette

/*
 * Subpáginas e diálogos do app. Usam a mesma paleta do kit
 * (com.moboserver.design) para não inventar um segundo visual: fundo cinza
 * claro, cartões brancos arredondados, verde profundo nos destaques e alvos de
 * toque de pelo menos 48 dp.
 */

@Composable
fun MoboPage(
    dark: Boolean,
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = moboPalette(dark)
    Column(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding().imePadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, null, tint = p.green, modifier = Modifier.size(20.dp))
                    Text("Voltar", color = p.green, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 20.dp)) {
                Text(
                    title,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.ink,
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = p.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MoboGroup(dark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val p = moboPalette(dark)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        content = content,
    )
}

@Composable
fun MoboDivider(dark: Boolean) {
    HorizontalDivider(color = moboPalette(dark).line)
}

@Composable
fun MoboLabel(dark: Boolean, text: String) {
    val p = moboPalette(dark)
    Text(
        text,
        color = p.green,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 10.dp),
    )
}

@Composable
fun MoboNote(dark: Boolean, text: String) {
    val p = moboPalette(dark)
    Text(text, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
}

@Composable
fun MoboRow(
    dark: Boolean,
    title: String,
    value: String = "",
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val p = moboPalette(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            color = if (enabled) p.ink else p.muted,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) Text(value, color = p.muted, fontSize = 14.sp)
    }
}

@Composable
fun MoboSwitchRow(
    dark: Boolean,
    title: String,
    description: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val p = moboPalette(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) p.ink else p.muted, fontSize = 15.sp)
            if (description.isNotEmpty()) {
                Text(description, color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.green,
                checkedBorderColor = p.green,
                uncheckedThumbColor = p.muted,
                uncheckedTrackColor = p.line,
                uncheckedBorderColor = p.line,
            ),
        )
    }
}

@Composable
fun MoboPrimary(dark: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF126A34),
            contentColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MoboField(
    dark: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    numeric: Boolean = false,
    placeholder: String = "",
) {
    val p = moboPalette(dark)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = p.muted) },
        placeholder = if (placeholder.isEmpty()) null else {
            { Text(placeholder, color = p.muted, fontSize = 13.sp) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.ink,
            unfocusedTextColor = p.ink,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = p.green,
            unfocusedBorderColor = p.line,
            cursorColor = p.green,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/** Moldura dos diálogos: mesmo cartão arredondado do resto da interface. */
@Composable
private fun MoboDialogFrame(dark: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = moboPalette(dark)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(p.card)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun DialogTitle(dark: Boolean, text: String, subtitle: String = "") {
    val p = moboPalette(dark)
    Text(text, color = p.ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    if (subtitle.isNotEmpty()) {
        Text(subtitle, color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DialogButtons(dark: Boolean, cancel: String = "Cancelar", onCancel: () -> Unit, onConfirm: () -> Unit, confirm: String, confirmEnabled: Boolean = true, destructive: Boolean = false) {
    val p = moboPalette(dark)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(cancel, color = p.muted)
        }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) Color(0xFFB3261E) else Color(0xFF126A34),
                contentColor = Color.White,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(confirm, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --------------------------------------------------------------------- diálogos

@Composable
fun EditNameDialog(dark: Boolean, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, "Nome do servidor", "É o nome que aparece na lista de servidores do Minecraft.")
        MoboField(dark, text, { text = text.take(40) }, "Nome")
        DialogButtons(dark, onCancel = onDismiss, onConfirm = { onSave(text) }, confirm = "Salvar", confirmEnabled = text.isNotBlank())
    }
}

@Composable
fun EditPortDialog(dark: Boolean, current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(
            dark,
            "Porta",
            "Entre 1024 e 65535. A mudança vale no próximo início do servidor.",
        )
        MoboField(dark, text, { text = it.filter { ch -> ch.isDigit() }.take(5) }, "Porta", numeric = true)
        if (parsed != null && parsed !in 1024..65535) {
            MoboNote(dark, "Use um valor entre 1024 e 65535.")
        }
        DialogButtons(
            dark,
            onCancel = onDismiss,
            onConfirm = { if (parsed != null) onSave(parsed) },
            confirm = "Salvar",
            confirmEnabled = parsed != null && parsed in 1024..65535,
        )
    }
}

@Composable
fun GameDialog(
    dark: Boolean,
    gameMode: String,
    difficulty: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(gameMode) }
    var diff by remember { mutableStateOf(difficulty) }
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, "Jogo", "Vale quando um mundo novo é aberto pelo servidor.")
        MoboLabel(dark, "MODO DE JOGO")
        MoboGroup(dark) {
            listOf("survival" to "Sobrevivência", "creative" to "Criativo", "adventure" to "Aventura").forEachIndexed { index, item ->
                if (index > 0) MoboDivider(dark)
                ChoiceRow(dark, item.second, mode == item.first) { mode = item.first }
            }
        }
        MoboLabel(dark, "DIFICULDADE")
        MoboGroup(dark) {
            listOf("peaceful" to "Pacífico", "easy" to "Fácil", "normal" to "Normal", "hard" to "Difícil").forEachIndexed { index, item ->
                if (index > 0) MoboDivider(dark)
                ChoiceRow(dark, item.second, diff == item.first) { diff = item.first }
            }
        }
        DialogButtons(dark, onCancel = onDismiss, onConfirm = { onSave(mode, diff) }, confirm = "Salvar")
    }
}

@Composable
private fun ChoiceRow(dark: Boolean, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = moboPalette(dark)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, "Selecionado", tint = p.green, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun AppearanceDialog(dark: Boolean, theme: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, "Aparência", "O tema não altera a configuração do servidor.")
        MoboGroup(dark) {
            listOf(
                UiPrefs.SYSTEM to "Seguir o sistema",
                UiPrefs.LIGHT to "Claro",
                UiPrefs.DARK to "Escuro",
            ).forEachIndexed { index, item ->
                if (index > 0) MoboDivider(dark)
                ChoiceRow(dark, item.second, theme == item.first) { onPick(item.first) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = moboPalette(dark).green)
        }
    }
}

@Composable
fun InternetDialog(dark: Boolean, info: InternetInfo, onRefresh: () -> Unit, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val p = moboPalette(dark)
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, "Acesso pela internet", "O que é medido, e o que ainda não foi.")
        MoboGroup(dark) {
            MoboRow(dark, "Endereço na rede local", info.lanAddress)
            MoboDivider(dark)
            MoboRow(dark, "Endereço do roteador (UPnP)", info.wanAddress)
            MoboDivider(dark)
            MoboRow(dark, "Endereço visto de fora", info.publicAddress)
            if (info.tunnelAddress.isNotBlank()) {
                MoboDivider(dark)
                MoboRow(dark, "Túnel (relay)", info.tunnelAddress)
            }
        }
        if (info.routerNote.isNotBlank()) MoboNote(dark, "Roteador: ${info.routerNote}")
        if (info.tunnelState.isNotBlank()) MoboNote(dark, "Túnel: ${info.tunnelState}")
        Spacer(Modifier.height(12.dp))
        MoboLabel(dark, "TESTE EXTERNO")
        MoboGroup(dark) {
            MoboRow(dark, "Resultado", info.externalStatus)
            if (info.externalDetail.isNotBlank()) {
                MoboDivider(dark)
                Text(
                    info.externalDetail,
                    color = p.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        if (info.cgnat) {
            MoboNote(
                dark,
                "A rede local está em 100.64.0.0/10 (CGNAT da operadora). Esse endereço não é " +
                    "alcançável por amigos: uma regra UPnP no roteador não atravessa o NAT da operadora.",
            )
        }
        MoboNote(
            dark,
            if (info.lastCheckAt.isBlank()) {
                "“Não verificado” não significa bloqueado: o teste só é concluído quando outra " +
                    "rede realmente alcança o servidor."
            } else {
                "Último teste registrado em ${info.lastCheckAt}. Trocar de rede invalida o resultado."
            },
        )
        Spacer(Modifier.height(8.dp))
        MoboPrimary(dark, if (info.checking) "Verificando…" else "Testar acesso externo", enabled = !info.checking, onClick = onCheck)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Atualizar endereços", color = p.green)
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = p.muted)
        }
    }
}

@Composable
fun HelpDialog(dark: Boolean, address: String, port: Int, onCopyAddress: () -> Unit, onDismiss: () -> Unit) {
    val p = moboPalette(dark)
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, "Como seus amigos entram", "Passo a passo, na mesma rede Wi-Fi.")
        MoboGroup(dark) {
            Text(
                "1. Ligue o servidor aqui e espere aparecer ● Online.\n" +
                    "2. No outro celular, abra o Minecraft Bedrock.\n" +
                    "3. Toque em Jogar, depois em Servidores e em Adicionar servidor.\n" +
                    "4. Em Endereço do servidor, digite $address e a porta $port.\n" +
                    "5. Salve e entre. Os dois aparelhos precisam estar na mesma rede.",
                color = p.ink,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        MoboNote(
            dark,
            "Se o botão “Adicionar servidor” do Minecraft não aceitar o endereço, use a aba " +
                "“Amigos” com o convite do jogo. Nada aqui muda o mundo original importado.",
        )
        Spacer(Modifier.height(8.dp))
        MoboPrimary(dark, "Copiar endereço", onClick = onCopyAddress)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = p.muted)
        }
    }
}

@Composable
fun WorldMenuDialog(
    dark: Boolean,
    name: String,
    running: Boolean,
    onExport: () -> Unit,
    onValidate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, name, "Opções deste mundo.")
        MoboGroup(dark) {
            MoboRow(dark, "Validar mundo", onClick = onValidate)
            MoboDivider(dark)
            MoboRow(dark, "Exportar (.mcworld)", onClick = onExport)
            MoboDivider(dark)
            MoboRow(dark, "Excluir mundo", enabled = !running, onClick = if (running) null else onDelete)
        }
        MoboNote(
            dark,
            if (running) {
                "Pare o servidor antes de excluir este mundo."
            } else {
                "O mundo original importado continua guardado junto do mundo até a exclusão."
            },
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = moboPalette(dark).muted)
        }
    }
}

@Composable
fun PlayerDetailsDialog(
    dark: Boolean,
    name: String,
    latencyMs: Long?,
    state: String,
    reason: String,
    infoSummary: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = moboPalette(dark)
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, name, "Skin e latência desta sessão.")
        MoboGroup(dark) {
            MoboRow(dark, "Latência", latencyMs?.let { "$it ms" } ?: "sem medição")
            MoboDivider(dark)
            MoboRow(dark, "Imagem da skin", state)
            if (infoSummary.isNotBlank()) {
                MoboDivider(dark)
                Text(infoSummary, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
        if (reason.isNotBlank()) {
            MoboNote(dark, reason)
        }
        MoboNote(
            dark,
            "A imagem é montada pelo próprio servidor a partir da skin que chegou na sessão. " +
                "Nenhuma skin é inventada nem buscada por fora.",
        )
        Spacer(Modifier.height(8.dp))
        MoboPrimary(dark, "Pedir a skin de novo", onClick = onRetry)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = p.muted)
        }
    }
}

@Composable
fun TextDialog(dark: Boolean, title: String, body: String, onDismiss: () -> Unit) {
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(dark, title)
        MoboGroup(dark) {
            Text(
                body,
                color = moboPalette(dark).ink,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Fechar", color = moboPalette(dark).green)
        }
    }
}

@Composable
fun ConfirmDeleteDialog(dark: Boolean, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    MoboDialogFrame(dark, onDismiss) {
        DialogTitle(
            dark,
            "Excluir “$name”?",
            "A pasta do mundo e a cópia do .mcworld serão apagadas do aparelho. O arquivo que você " +
                "importou do Minecraft não é alterado.",
        )
        DialogButtons(
            dark,
            cancel = "Manter",
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirm = "Excluir",
            destructive = true,
        )
    }
}

// ------------------------------------------------------- subpágina de avançado

@Composable
fun AdvancedPage(
    dark: Boolean,
    extras: PanelExtras,
    bans: List<BanEntry>,
    online: List<Pair<String, String>>,
    onBack: () -> Unit,
    onUpdate: ((ServerConfig) -> ServerConfig) -> Unit,
    onDiagnostics: () -> Unit,
    onUnban: (String) -> Unit,
    onKick: (String) -> Unit,
    onBan: (String, String) -> Unit,
    onRefreshBans: () -> Unit,
    onSaveWorld: () -> Unit,
    onTestBinary: () -> Unit,
    serverRunning: Boolean,
) {
    val p = moboPalette(dark)
    var relayAddr by remember(extras.relayAddr) { mutableStateOf(extras.relayAddr) }
    var relayToken by remember(extras.relayToken) { mutableStateOf(extras.relayToken) }
    var externalHost by remember(extras.externalHost) { mutableStateOf(extras.externalHost) }

    MoboPage(
        dark = dark,
        title = "Avançado",
        subtitle = "Autenticação, rede e diagnóstico. Vale no próximo início, exceto onde indicado.",
        onBack = onBack,
    ) {
        MoboLabel(dark, "SERVIDOR")
        MoboGroup(dark) {
            MoboRow(
                dark,
                "Salvar mundo agora",
                value = if (serverRunning) "" else "servidor desligado",
                enabled = serverRunning,
                onClick = if (serverRunning) onSaveWorld else null,
            )
        }
        MoboNote(dark, "Parar o servidor também salva o mundo.")

        MoboLabel(dark, "AUTENTICAÇÃO")
        MoboGroup(dark) {
            MoboSwitchRow(
                dark,
                "Exigir conta Xbox",
                "Desligado aceita entrar sem login Xbox. Só use em rede confiável.",
                extras.authEnabled,
            ) { value -> onUpdate { it.copy(authEnabled = value) } }
            MoboDivider(dark)
            MoboSwitchRow(
                dark,
                "Permitir modo aberto se o login falhar",
                "O servidor sobe mesmo quando a verificação de conta falha.",
                extras.allowOpenMode,
            ) { value -> onUpdate { it.copy(allowOpenMode = value) } }
        }

        MoboLabel(dark, "REDE")
        MoboGroup(dark) {
            MoboSwitchRow(
                dark,
                "Abrir porta no roteador (UPnP)",
                "O roteador aceitar a regra não prova que o acesso externo funciona.",
                extras.autoForward,
            ) { value -> onUpdate { it.copy(autoForward = value) } }
            MoboDivider(dark)
            MoboSwitchRow(
                dark,
                "Transporte legado RakNet",
                "Liga a descoberta na aba LAN. A entrada por NetherNet continua sendo o padrão.",
                extras.rakNet,
            ) { value -> onUpdate { it.copy(rakNet = value) } }
            MoboDivider(dark)
            Column(Modifier.padding(bottom = 8.dp)) {
                MoboField(dark, externalHost, { externalHost = it.take(80) }, "Domínio próprio", placeholder = "ex.: casa.exemplo.com")
                TextButton(
                    onClick = { onUpdate { it.copy(externalHost = externalHost.trim()) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Salvar domínio", color = p.green) }
            }
        }

        MoboLabel(dark, "TÚNEL (RELAY)")
        MoboGroup(dark) {
            Column(Modifier.padding(vertical = 6.dp)) {
                MoboField(dark, relayAddr, { relayAddr = it.take(120) }, "Endereço do relay", placeholder = "host:porta (vazio = desligado)")
                MoboField(dark, relayToken, { relayToken = it.take(200) }, "Token do relay")
            }
            MoboSwitchRow(dark, "Usar TLS no túnel", checked = extras.relayTls) { value ->
                onUpdate { it.copy(relayTls = value) }
            }
            TextButton(
                onClick = { onUpdate { it.copy(relayAddr = relayAddr.trim(), relayToken = relayToken.trim()) } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Salvar túnel", color = p.green) }
        }

        MoboLabel(dark, "DIAGNÓSTICO")
        MoboGroup(dark) {
            MoboSwitchRow(
                dark,
                "Diagnóstico avançado",
                "Mostra as linhas DEBUG no console.",
                extras.advancedDiagnostics,
            ) { value -> onUpdate { it.copy(advancedDiagnostics = value) } }
            MoboDivider(dark)
            MoboRow(dark, "Compartilhar diagnóstico", onClick = { onDiagnostics() })
            MoboDivider(dark)
            MoboRow(dark, "Testar o binário do servidor", onClick = { onTestBinary() })
        }
        MoboNote(dark, "O relatório inclui versões e caminhos. Tokens e certificados não entram.")

        MoboLabel(dark, "JOGADORES ONLINE")
        if (online.isEmpty()) {
            MoboNote(dark, "Nenhum jogador conectado.")
        } else {
            MoboGroup(dark) {
                online.forEachIndexed { index, (name, xuid) ->
                    if (index > 0) MoboDivider(dark)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onKick(name) }) { Text("Expulsar", color = p.green, fontSize = 13.sp) }
                        TextButton(onClick = { onBan(name, xuid) }) { Text("Banir", color = Color(0xFFB3261E), fontSize = 13.sp) }
                    }
                }
            }
        }

        MoboLabel(dark, "BANIDOS (${bans.size})")
        if (bans.isEmpty()) {
            MoboNote(dark, "Ninguém banido neste aparelho.")
        } else {
            MoboGroup(dark) {
                bans.forEachIndexed { index, entry ->
                    if (index > 0) MoboDivider(dark)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name.ifBlank { "Jogador" }, color = p.ink, fontSize = 15.sp)
                            Text("XUID ${entry.xuid}", color = p.muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onUnban(entry.xuid) }) { Text("Remover", color = p.green) }
                    }
                }
            }
        }
        TextButton(onClick = onRefreshBans, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Atualizar lista de banidos", color = p.green)
        }
        Spacer(Modifier.height(8.dp))
    }
}
