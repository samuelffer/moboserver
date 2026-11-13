package com.moboserver.app.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** A world stored on the phone. */
data class WorldInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    /** Cópia do .mcworld que originou o mundo (importações), quando existir. */
    val originalBytes: Long = 0L,
    /** Metadados preservados do level.dat (tags que o motor não lê). */
    val fixedTags: List<String> = emptyList(),
)

/**
 * Resultado da validação de um mundo, produzido pelo próprio binário
 * ("-validate-world"). Os três códigos de falha são mostrados de formas
 * diferentes para o usuário:
 *
 *  - incompatible_metadata: o mundo é válido, mas o level.dat tem metadados que
 *    esta versão do motor não lê;
 *  - incomplete_import: faltam arquivos (normalmente o banco de dados db/);
 *  - invalid_file: o arquivo não é um mundo Bedrock.
 */
data class WorldValidation(
    val ok: Boolean,
    val code: String = "",
    val detail: String = "",
    val levelName: String = "",
    val fixedTags: List<String> = emptyList(),
    val storageVersion: Int = 0,
    val dbFiles: Int = 0,
    val worldBytes: Long = 0,
) {
    /** Mensagem curta e específica para o usuário. */
    fun message(fallbackName: String = ""): String {
        if (ok) return "Mundo \"${levelName.ifBlank { fallbackName }}\" pronto para abrir."
        val suffix = detail.ifBlank { "" }
        return when (code) {
            "incompatible_metadata" ->
                "Este mundo foi exportado com metadados que o motor não lê" +
                    (if (suffix.isBlank()) "." else ": $suffix")
            "incomplete_import" ->
                "A importação ficou incompleta: faltam arquivos do mundo (level.dat ou a pasta db/). " +
                    "Exporte o mundo de novo pelo Minecraft e importe o .mcworld inteiro."
            "invalid_file" ->
                "O arquivo não é um mundo Bedrock válido" + (if (suffix.isBlank()) "." else ": $suffix")
            else -> suffix.ifBlank { "Não foi possível validar o mundo." }
        }
    }
}

/**
 * Manages the worlds on disk. A world is simply a folder in Bedrock format
 * (level.dat + db/), exactly what the embedded server expects. Worlds exported
 * by Minecraft ("Exportar mundo" produces a .mcworld file, which is a ZIP) can
 * be imported directly.
 */
class WorldRepository(context: Context) {

    companion object {
        /** Nome do arquivo com a cópia do .mcworld recebido. */
        const val ORIGINAL_NAME = "original.mcworld"
    }

    private val app = context.applicationContext
    private val root = File(app.filesDir, "worlds")
    private val indexFile = File(root, "index.json")

    init {
        root.mkdirs()
    }

    fun directory(id: String): File = File(root, id)

    /** Cópia do .mcworld recebido, guardada junto do mundo. */
    fun originalFile(id: String): File = File(directory(id), ORIGINAL_NAME)

    /**
     * Valida level.dat e db/ com o mesmo código que abre o mundo no servidor.
     * O binário imprime um JSON e devolve um código de saída próprio, então a
     * mensagem mostrada ao usuário vem daqui, não de uma suposição do app.
     */
    fun validate(id: String): WorldValidation {
        val dir = directory(id)
        if (!dir.isDirectory) {
            return WorldValidation(ok = false, code = "invalid_file", detail = "a pasta do mundo não existe")
        }
        val binary = try {
            ServerProcess.resolveBinary(app)
        } catch (e: Exception) {
            return WorldValidation(
                ok = false,
                code = "invalid_file",
                detail = "o validador embutido não foi encontrado (${e.message})",
            )
        }
        val process = try {
            ProcessBuilder(binary.absolutePath, "-validate-world", dir.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return WorldValidation(ok = false, code = "invalid_file", detail = "falha ao executar o validador: ${e.message}")
        }
        val output = try {
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(90, TimeUnit.SECONDS)
            text
        } catch (e: Exception) {
            process.destroyForcibly()
            return WorldValidation(ok = false, code = "invalid_file", detail = "o validador não respondeu: ${e.message}")
        }
        val json = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }
            ?: return WorldValidation(ok = false, code = "invalid_file", detail = "o validador não devolveu resultado")
        return try {
            val o = JSONObject(json)
            WorldValidation(
                ok = o.optBoolean("ok", false),
                code = o.optString("code"),
                detail = o.optString("detail"),
                levelName = o.optString("levelName"),
                fixedTags = readStringList(o, "fixedTags"),
                storageVersion = o.optInt("storageVersion"),
                dbFiles = o.optInt("dbFiles"),
                worldBytes = o.optLong("worldBytes"),
            )
        } catch (e: Exception) {
            WorldValidation(ok = false, code = "invalid_file", detail = "resultado do validador ilegível: ${e.message}")
        }
    }

    fun exists(id: String): Boolean = id.isNotBlank() && directory(id).isDirectory

    fun list(): List<WorldInfo> {
        root.mkdirs()
        val names = readIndex()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.map { d ->
            WorldInfo(
                id = d.name,
                name = names[d.name] ?: readLevelName(d) ?: "Mundo ${d.name}",
                sizeBytes = dirSize(d),
                updatedAt = d.lastModified(),
                originalBytes = originalFile(d.name).let { if (it.isFile) it.length() else 0L },
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun nameOf(id: String): String =
        readIndex()[id] ?: readLevelName(directory(id)) ?: "Mundo $id"

    /**
     * Imports a .mcworld / .zip exported by Minecraft. Only the files the
     * server needs are extracted, which keeps the phone storage tidy.
     */
    fun importWorld(uri: Uri, fallbackName: String): WorldInfo {
        val tmp = File(app.cacheDir, "world-import-${System.currentTimeMillis()}.zip")
        val id = "w" + System.currentTimeMillis()
        val target = directory(id)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) } 
            } ?: throw IOException("Não foi possível abrir o arquivo selecionado")

            if (tmp.length() < 100) {
                throw IOException("O arquivo selecionado está vazio ou não é um mundo")
            }

            val entries = try {
                ZipFile(tmp).use { zip -> Collections.list(zip.entries()) }
            } catch (e: Exception) {
                throw IOException("O arquivo não é um ZIP válido. Exporte o mundo pelo Minecraft como .mcworld")
            }

            val prefix = detectPrefix(entries.map { it.name })
                ?: throw IOException("Este arquivo não parece um mundo do Minecraft Bedrock (não encontrei o level.dat)")

            if (!target.mkdirs()) {
                throw IOException("Não foi possível criar a pasta do mundo")
            }

            var levelName: String? = null
            var sawLevel = false
            ZipFile(tmp).use { zip ->
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.startsWith(prefix)) continue
                    val rel = name.removePrefix(prefix)
                    if (!isWanted(rel)) continue
                    if (!isSafe(rel)) continue
                    val out = File(target, rel)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (rel == "level.dat") sawLevel = true
                    if (rel == "levelname.txt") levelName = out.readText().trim()
                }
            }

            if (!sawLevel) {
                throw IOException("O mundo não contém o arquivo level.dat e não pôde ser importado")
            }

            // A cópia do .mcworld fica guardada: o mundo extraído pode ser
            // reaberto e salvo muitas vezes, e o arquivo original do usuário
            // nunca é tocado.
            try {
                tmp.copyTo(File(target, ORIGINAL_NAME), overwrite = true)
            } catch (e: Exception) {
                // sem espaço para a cópia o mundo ainda pode ser usado
            }

            val validation = validate(id)
            if (!validation.ok) {
                throw IOException(validation.message(fallbackName.ifBlank { "mundo importado" }))
            }

            val display = validation.levelName.ifBlank { levelName.orEmpty() }
                .ifBlank { fallbackName.ifBlank { "Mundo importado" } }
            writeIndex(readIndex().apply { put(id, display) })
            return WorldInfo(
                id = id,
                name = display,
                sizeBytes = dirSize(target),
                updatedAt = System.currentTimeMillis(),
                originalBytes = originalFile(id).let { if (it.isFile) it.length() else 0L },
                fixedTags = validation.fixedTags,
            )
        } catch (e: Exception) {
            target.deleteRecursively()
            throw if (e is IOException) e else IOException("Falha ao importar o mundo: ${e.message}")
        } finally {
            tmp.delete()
        }
    }

    fun delete(id: String) {
        directory(id).deleteRecursively()
        writeIndex(readIndex().apply { remove(id) })
    }

    fun markUpdated(id: String) {
        directory(id).setLastModified(System.currentTimeMillis())
    }

    private fun detectPrefix(names: List<String>): String? {
        if (names.contains("level.dat")) return ""
        val nested = names.firstOrNull { it.endsWith("/level.dat") } ?: return null
        return nested.removeSuffix("level.dat")
    }

    private fun isWanted(rel: String): Boolean =
        rel == "level.dat" || rel == "levelname.txt" || rel == "world_icon.jpeg" ||
            rel == ORIGINAL_NAME || rel.startsWith("db/")

    private fun readStringList(o: JSONObject, key: String): List<String> {
        val array = o.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val v = array.optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private fun isSafe(rel: String): Boolean =
        !rel.startsWith("/") && !rel.contains("..") && !rel.contains('\\')

    private fun readLevelName(dir: File): String? {
        val f = File(dir, "levelname.txt")
        return if (f.isFile) f.readText().trim().ifBlank { null } else null
    }

    private fun dirSize(dir: File, budget: IntArray = intArrayOf(20000)): Long {
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            if (budget[0]-- <= 0) break
            total += if (f.isDirectory) dirSize(f, budget) else f.length()
        }
        return total
    }

    private fun readIndex(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (!indexFile.isFile) return map
        return try {
            val json = JSONObject(indexFile.readText())
            for (key in json.keys()) {
                map[key] = json.optString(key)
            }
            map
        } catch (e: Exception) {
            map
        }
    }

    private fun writeIndex(map: Map<String, String>) {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        try {
            root.mkdirs()
            indexFile.writeText(json.toString())
        } catch (e: Exception) {
            // A broken index only costs the display name, never the world data.
        }
    }
}
