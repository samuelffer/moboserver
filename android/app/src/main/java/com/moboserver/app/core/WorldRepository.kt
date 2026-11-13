package com.moboserver.app.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.zip.ZipFile

/** A world stored on the phone. */
data class WorldInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

/**
 * Manages the worlds on disk. A world is simply a folder in Bedrock format
 * (level.dat + db/), exactly what the embedded server expects. Worlds exported
 * by Minecraft ("Exportar mundo" produces a .mcworld file, which is a ZIP) can
 * be imported directly.
 */
class WorldRepository(context: Context) {

    private val app = context.applicationContext
    private val root = File(app.filesDir, "worlds")
    private val indexFile = File(root, "index.json")

    init {
        root.mkdirs()
    }

    fun directory(id: String): File = File(root, id)

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
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun nameOf(id: String): String =
        readIndex()[id] ?: readLevelName(directory(id)) ?: "Mundo $id"

    /** Creates an empty world folder. The server generates the terrain on first start. */
    fun create(name: String): WorldInfo {
        val id = "w" + System.currentTimeMillis()
        val dir = directory(id)
        if (!dir.mkdirs()) {
            throw IOException("Não foi possível criar a pasta do mundo")
        }
        val clean = name.trim().ifBlank { "Novo mundo" }
        writeIndex(readIndex().apply { put(id, clean) })
        return WorldInfo(id, clean, 0L, System.currentTimeMillis())
    }

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

            val display = levelName?.ifBlank { null } ?: fallbackName.ifBlank { "Mundo importado" }
            writeIndex(readIndex().apply { put(id, display) })
            return WorldInfo(id, display, dirSize(target), System.currentTimeMillis())
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
        rel == "level.dat" || rel == "levelname.txt" || rel == "world_icon.jpeg" || rel.startsWith("db/")

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
