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
