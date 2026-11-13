package com.moboserver.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.ServerStatus
import com.moboserver.app.core.Store
import com.moboserver.app.core.WorldInfo
import com.moboserver.app.core.WorldRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorldsScreen() {
    val context = LocalContext.current
    val repository = remember { WorldRepository(context) }
    val config by Store.config.collectAsState()
    val runtime by ServerController.state.collectAsState()
    val scope = rememberCoroutineScope()

    var worlds by remember { mutableStateOf<List<WorldInfo>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WorldInfo?>(null) }

    val busy = runtime.status == ServerStatus.STARTING || runtime.status == ServerStatus.RUNNING ||
        runtime.status == ServerStatus.STOPPING

    fun reload() {
        worlds = repository.list()
    }

    LaunchedEffect(config.activeWorldId) {
        reload()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        message = null
        ServerController.beginImport()
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    try {
                        repository.importWorld(uri, "Mundo importado") as Any
                    } catch (e: Exception) {
                        e
                    }
                }
            } finally {
                // Sempre libera o início, inclusive se a corrotina for cancelada.
                importing = false
                ServerController.endImport()
            }
            if (outcome is WorldInfo) {
                val selection = ServerController.selectWorld(outcome.id)
                reload()
                message = buildString {
                    append("Mundo \"${outcome.name}\" importado e selecionado.")
                    if (outcome.originalBytes > 0) {
                        append(" O .mcworld original ficou guardado no aparelho.")
                    }
                    if (outcome.fixedTags.isNotEmpty()) {
                        append(
                            " Metadados preservados: ${outcome.fixedTags.joinToString(", ")} " +
                                "(voltam ao arquivo quando o mundo é salvo).",
                        )
                    }
                    if (!selection.ok) {
                        append(" Mas não foi possível selecioná-lo: ${selection.failure?.message ?: "erro desconhecido"}")
                    }
                }
            } else {
                message = (outcome as? Exception)?.message ?: "Não foi possível importar o mundo."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Mundos", color = MoboColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "O servidor abre um mundo exportado pelo Minecraft Bedrock. Importe o .mcworld do seu " +
                "aparelho: o arquivo original é guardado aqui e o mundo só é liberado depois de validado.",
            color = MoboColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy && !importing,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Importar mundo (.mcworld)", fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "A criação de mundo acontece no Minecraft, onde o jogo gera o terreno e os animais. " +
                "Aqui não existe gerador próprio.",
            color = MoboColors.TextSecondary,
            fontSize = 11.sp,
        )

        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Desligue o servidor para trocar ou excluir mundos.",
                color = MoboColors.Warning,
                fontSize = 12.sp,
            )
        }
        if (importing) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MoboColors.Lime,
                )
                Spacer(Modifier.width(10.dp))
                Text("Importando mundo...", color = MoboColors.TextSecondary, fontSize = 12.sp)
            }
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MoboColors.SurfaceHigh)
                    .padding(12.dp),
            ) {
                Text(it, color = MoboColors.TextPrimary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        if (worlds.isEmpty()) {
            MoboCard {
                Text(
                    "Nenhum mundo ainda. Exporte um mundo pelo Minecraft (\"Exportar mundo\" gera um " +
                        ".mcworld) e toque em Importar mundo.",
                    color = MoboColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(worlds, key = { it.id }) { world ->
                WorldCard(
                    world = world,
                    active = world.id == config.activeWorldId,
                    running = world.id == ServerController.runningWorldId,
                    enabled = !busy && !importing,
                    onSelect = {
                        val outcome = ServerController.selectWorld(world.id)
                        if (outcome.ok) {
                            message = "O servidor vai abrir \"${world.name}\" no próximo início."
                        } else {
                            message = outcome.failure?.let { "${it.title}: ${it.message}" }
                                ?: "Não foi possível selecionar este mundo."
                        }
                        reload()
                    },
                    onValidate = {
                        scope.launch {
                            val validation = withContext(Dispatchers.IO) { repository.validate(world.id) }
                            message = if (validation.ok) {
                                buildString {
                                    append("Mundo \"${validation.levelName.ifBlank { world.name }}\" validado: ")
                                    append("level.dat legível, ${validation.dbFiles} arquivo(s) em db/")
                                    if (validation.fixedTags.isNotEmpty()) {
                                        append(", metadados preservados (${validation.fixedTags.joinToString(", ")})")
                                    }
                                    append(".")
                                }
                            } else {
                                validation.message(world.name)
                            }
                        }
                    },
                    onDelete = { pendingDelete = world },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { world ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Excluir \"${world.name}\"?") },
            text = {
                Text(
                    "O mundo será apagado do celular. Essa ação não pode ser desfeita.",
                    color = MoboColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = world.id != ServerController.runningWorldId,
                    onClick = {
                        val id = world.id
                        pendingDelete = null
                        scope.launch {
                            // O mundo carregado pela sessão nunca é apagado.
                            if (id == ServerController.runningWorldId) {
                                message = "Pare o servidor antes de excluir o mundo que está em execução."
                                return@launch
                            }
                            withContext(Dispatchers.IO) { repository.delete(id) }
                            // Limpa a seleção apenas se ela ainda apontar para o
                            // mundo removido: se o usuário escolheu outro nesse
                            // meio-tempo, a escolha nova é preservada.
                            Store.clearWorldIf(id)
                            reload()
                            message = "Mundo excluído."
                        }
                    },
                ) { Text("Excluir", color = MoboColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
            containerColor = MoboColors.SurfaceHigh,
        )
    }
}

@Composable
private fun WorldCard(
    world: WorldInfo,
    active: Boolean,
    running: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onValidate: () -> Unit,
    onDelete: () -> Unit,
) {
    MoboCard(borderColor = if (active) MoboColors.LimeDark else MoboColors.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = world.name,
                    color = MoboColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${formatSize(world.sizeBytes)} • ${formatDate(world.updatedAt)}",
                    color = MoboColors.TextSecondary,
                    fontSize = 11.sp,
                )
                if (world.originalBytes > 0) {
                    Text(
                        text = "Cópia do .mcworld guardada (${formatSize(world.originalBytes)})",
                        color = MoboColors.TextSecondary.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                    )
                }
            }
            if (running) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MoboColors.LimeSoft)
                        .border(1.dp, MoboColors.Lime, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Em execução", color = MoboColors.Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            } else if (active) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MoboColors.LimeSoft)
                        .border(1.dp, MoboColors.LimeDark, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("Em uso", color = MoboColors.Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSelect,
                enabled = enabled && !active,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (active) "Selecionado para o próximo início" else "Usar este mundo", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onValidate,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Validar", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Excluir mundo",
                    tint = MoboColors.Danger,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "novo"
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return "sem data"
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
