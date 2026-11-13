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
    var showCreate by remember { mutableStateOf(false) }
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
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    repository.importWorld(uri, "Mundo importado") as Any
                } catch (e: Exception) {
                    e
                }
            }
            importing = false
            if (outcome is WorldInfo) {
                Store.update { it.copy(activeWorldId = outcome.id) }
                reload()
                message = "Mundo \"${outcome.name}\" importado e selecionado."
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
            "Escolha o mundo que o servidor vai abrir. Você pode importar o seu mundo do Minecraft (.mcworld) ou gerar um novo.",
            color = MoboColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { showCreate = true },
                enabled = !busy && !importing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Novo mundo", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !busy && !importing,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Importar", fontSize = 13.sp)
            }
        }

        if (busy) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Desligue o servidor para trocar, criar ou excluir mundos.",
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
                    "Nenhum mundo ainda. Crie um mundo novo para testar ou importe o seu .mcworld.",
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
                    enabled = !busy,
                    onSelect = {
                        Store.update { it.copy(activeWorldId = world.id) }
                        message = "O servidor vai abrir \"${world.name}\" no próximo início."
                    },
                    onDelete = { pendingDelete = world },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showCreate) {
        CreateWorldDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, generator ->
                showCreate = false
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        try {
                            repository.create(name) as Any
                        } catch (e: Exception) {
                            e
                        }
                    }
                    if (outcome is WorldInfo) {
                        Store.update { it.copy(activeWorldId = outcome.id, generator = generator) }
                        reload()
                        message = "Mundo \"${outcome.name}\" criado."
                    } else {
                        message = (outcome as? Exception)?.message ?: "Não foi possível criar o mundo."
                    }
                }
            },
        )
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
                TextButton(onClick = {
                    val id = world.id
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.delete(id) }
                        if (config.activeWorldId == id) {
                            Store.update { it.copy(activeWorldId = "") }
                        }
                        reload()
                        message = "Mundo excluído."
                    }
                }) { Text("Excluir", color = MoboColors.Danger) }
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
    enabled: Boolean,
    onSelect: () -> Unit,
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
            }
            if (active) {
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
                Text(if (active) "Selecionado" else "Usar este mundo", fontSize = 12.sp)
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

@Composable
private fun CreateWorldDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, generator: String) -> Unit,
) {
    var name by remember { mutableStateOf("Meu mundo novo") }
    var generator by remember { mutableStateOf("terrain") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo mundo") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Nome do mundo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text("Tipo de mundo", color = MoboColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectablePill(
                        text = "Terreno natural",
                        selected = generator == "terrain",
                        onClick = { generator = "terrain" },
                    )
                    SelectablePill(
                        text = "Superflat",
                        selected = generator == "flat",
                        onClick = { generator = "flat" },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "O mundo é gerado no primeiro início do servidor. Terreno natural tem colinas, praias, árvores e minérios.",
                    color = MoboColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "Novo mundo" }, generator) }) {
                Text("Criar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
        containerColor = MoboColors.SurfaceHigh,
    )
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
