package com.moboserver.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self check used by the "Diagnóstico" card: it answers the only question that
 * matters when the app "does nothing" — can this phone actually execute the
 * embedded server binary?
 */
object Diagnostics {

    fun summary(context: Context): String {
        val binary = try {
            ServerProcess.resolveBinary(context)
        } catch (e: Exception) {
            return "Binário do servidor: NÃO ENCONTRADO (${e.message})"
        }
        val size = binary.length() / (1024 * 1024)
        return buildString {
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Binário: ${binary.absolutePath} (${size} MB)")
        }.trim()
    }

    /**
     * Everything needed to debug a phone that nobody can reach: device, network
     * interfaces, addresses, exposure state and the tail of the server log. It is
     * shared as plain text, so the user can send it without installing anything.
     */
    fun report(context: Context): String = buildString {
        val state = ServerController.state.value
        appendLine("== MoboServer — diagnostico ==")
        appendLine(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        appendLine()
        appendLine("-- Aparelho --")
        appendLine(summary(context))
        appendLine()
        appendLine("-- Interfaces --")
        try {
            for (iface in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp) continue
                val addresses = java.util.Collections.list(iface.inetAddresses)
                    .joinToString(", ") { it.hostAddress ?: "?" }
                appendLine("${iface.name} (up=${iface.isUp}, mtu=${iface.mtu}): $addresses")
            }
        } catch (e: Exception) {
            appendLine("falha ao listar interfaces: ${e.message}")
        }
        appendLine()
        appendLine("-- Seleção de mundo --")
        for (line in ServerController.selectionDiagnostics()) {
            appendLine(line)
        }
        appendLine()
        appendLine("-- Avatares (skin da sessao) --")
        for (line in ServerController.avatarDiagnostics()) {
            appendLine(line)
        }
        appendLine()
        appendLine("-- Chat e salvamento --")
        appendLine("Mensagens no chat: ${state.chat.size}")
        appendLine(
            "Ultimo salvamento confirmado: " +
                if (state.lastSaveAt > 0) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(state.lastSaveAt))
                } else {
                    "nenhum evento de salvamento nesta sessao"
                },
        )
        val lan = Net.lanIp()
        appendLine()
        appendLine("-- Rede --")
        appendLine("IP local escolhido: ${lan ?: "nenhum"}")
        appendLine("Gateway: ${Net.gatewayIp(context) ?: "desconhecido"}")
        appendLine("IP publico (STUN): ${Net.publicIp() ?: "nao descoberto"}")
        appendLine("CGNAT: ${if (lan != null && Net.isCarrierGradeNat(lan)) "sim" else "nao"}")
        appendLine()
        appendLine("-- Servidor --")
        appendLine("Status: ${state.status}")
        appendLine("Info: ${state.info}")
        appendLine("Exposure: ${state.exposure}")
        appendLine("Erro: ${state.error ?: "nenhum"}")
        // O detalhe técnico da falha de início só existe aqui: o cartão da
        // interface mostra apenas título e mensagem.
        state.failure?.let { failure ->
            appendLine("Falha: ${failure.problem} — ${failure.title}")
            appendLine("Mensagem: ${failure.message}")
            if (failure.technical.isNotBlank()) appendLine("Detalhe técnico: ${failure.technical}")
        }
        appendLine()
        appendLine("-- Log (ultimas 120 linhas) --")
        val file = File(context.filesDir, "server.log")
        if (file.isFile) {
            val lines = file.readLines()
            lines.takeLast(120).forEach { appendLine(it) }
        } else {
            appendLine("sem arquivo de log")
        }
    }.toString()

    /** Runs `mobosrv -version` and returns whatever it printed. */
    fun testBinary(context: Context): String {
        val binary = try {
            ServerProcess.resolveBinary(context)
        } catch (e: Exception) {
            return "Falha: binário não encontrado (${e.message})"
        }
        return try {
            val process = ProcessBuilder(binary.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                "Falha: o binário não respondeu em 15s"
            } else {
                "OK (código ${process.exitValue()})\n${output.trim()}"
            }
        } catch (e: Exception) {
            "Falha ao executar: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun freeSpace(context: Context): String {
        return try {
            val usable = File(context.filesDir.absolutePath).usableSpace / (1024L * 1024L)
            "$usable MB livres para os mundos"
        } catch (e: Exception) {
            "espaço livre desconhecido"
        }
    }
}
