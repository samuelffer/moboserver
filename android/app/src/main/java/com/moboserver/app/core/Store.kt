package com.moboserver.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Armazenamento chave/valor compartilhado pela interface, pelo serviço e pelo
 * controlador do servidor.
 *
 * Toda escrita passa por [lock] e é um read-modify-write completo: duas telas
 * salvando ao mesmo tempo não conseguem se sobrescrever com um snapshot velho.
 * Os campos de outras telas nunca são reescritos por acidente — a seleção de
 * mundo só muda por [selectWorld]/[clearWorldIf], e Ajustes só grava os campos
 * que edita ([saveSettings]).
 */
object Store {
    private const val PREFS = "moboserver"
    private const val KEY_CONFIG = "config"

    private val lock = Any()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _config = MutableStateFlow(ServerConfig())
    val config: StateFlow<ServerConfig> = _config

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _config.value = decode(p.getString(KEY_CONFIG, null))
        }
    }

    /** Substitui a configuração inteira. Evite: use as atualizações por campo. */
    fun save(config: ServerConfig) {
        synchronized(lock) { persistLocked(config) }
    }

    /**
     * Atualização atômica: o bloco recebe o estado mais recente e devolve o novo.
     * Duas chamadas concorrentes nunca partem do mesmo snapshot.
     */
    fun update(block: (ServerConfig) -> ServerConfig) {
        synchronized(lock) { persistLocked(block(_config.value)) }
    }

    /** Grava somente os campos editáveis em Ajustes, preservando a seleção. */
    fun saveSettings(draft: ServerConfig) {
        synchronized(lock) { persistLocked(ConfigMerge.applySettings(_config.value, draft)) }
    }

    /** Única forma de escolher o mundo que o servidor vai abrir. */
    fun selectWorld(id: String) {
        synchronized(lock) { persistLocked(ConfigMerge.selectWorld(_config.value, id)) }
    }

    /** Limpa a seleção apenas se ela ainda for [id]. */
    fun clearWorldIf(id: String) {
        synchronized(lock) { persistLocked(ConfigMerge.clearWorldIf(_config.value, id)) }
    }

    /** Snapshot consistente para uma sessão de servidor. */
    fun snapshot(): ServerConfig = _config.value

    private fun persistLocked(config: ServerConfig) {
        _config.value = config
        prefs?.edit()?.putString(KEY_CONFIG, encode(config))?.apply()
    }

    private fun encode(c: ServerConfig): String = JSONObject().apply {
        put("serverName", c.serverName)
        put("port", c.port)
        put("maxPlayers", c.maxPlayers)
        put("authEnabled", c.authEnabled)
        put("gameMode", c.gameMode)
        put("difficulty", c.difficulty)
        put("viewDistance", c.viewDistance)
        put("generator", c.generator)
        put("autoForward", c.autoForward)
        put("activeWorldId", c.activeWorldId)
        put("relayAddr", c.relayAddr)
        put("relayToken", c.relayToken)
        put("relayTls", c.relayTls)
        put("allowOpenMode", c.allowOpenMode)
        put("rakNet", c.rakNet)
        put("advancedDiagnostics", c.advancedDiagnostics)
        put("externalHost", c.externalHost)
    }.toString()

    /**
     * Leitura tolerante: um campo corrompido cai no padrão dele, sem apagar os
     * outros. (Antes, qualquer exceção devolvia a configuração inteira padrão —
     * inclusive a seleção de mundo.)
     */
    private fun decode(raw: String?): ServerConfig {
        val defaults = ServerConfig()
        if (raw.isNullOrBlank()) return defaults
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return defaults
        }
        return ServerConfig(
            serverName = o.readString("serverName", defaults.serverName).ifBlank { defaults.serverName },
            port = o.readInt("port", defaults.port).coerceIn(1024, 65535),
            maxPlayers = o.readInt("maxPlayers", defaults.maxPlayers).coerceIn(1, 30),
            authEnabled = o.readBool("authEnabled", defaults.authEnabled),
            gameMode = o.readString("gameMode", defaults.gameMode).ifBlank { defaults.gameMode },
            difficulty = o.readString("difficulty", defaults.difficulty).ifBlank { defaults.difficulty },
            viewDistance = o.readInt("viewDistance", defaults.viewDistance).coerceIn(4, 24),
            generator = o.readString("generator", defaults.generator).ifBlank { defaults.generator },
            autoForward = o.readBool("autoForward", defaults.autoForward),
            // A seleção é o campo mais importante aqui: nunca é descartada por
            // causa de outro campo estranho.
            activeWorldId = o.readString("activeWorldId", defaults.activeWorldId).trim(),
            relayAddr = o.readString("relayAddr", defaults.relayAddr).trim(),
            relayToken = o.readString("relayToken", defaults.relayToken).trim(),
            relayTls = o.readBool("relayTls", defaults.relayTls),
            allowOpenMode = o.readBool("allowOpenMode", defaults.allowOpenMode),
            rakNet = o.readBool("rakNet", defaults.rakNet),
            advancedDiagnostics = o.readBool("advancedDiagnostics", defaults.advancedDiagnostics),
            externalHost = o.readString("externalHost", defaults.externalHost).trim(),
        )
    }

    private fun JSONObject.readString(key: String, fallback: String): String = try {
        if (has(key) && !isNull(key)) getString(key) else fallback
    } catch (e: Exception) {
        fallback
    }

    private fun JSONObject.readInt(key: String, fallback: Int): Int = try {
        if (has(key) && !isNull(key)) getInt(key) else fallback
    } catch (e: Exception) {
        fallback
    }

    private fun JSONObject.readBool(key: String, fallback: Boolean): Boolean = try {
        if (has(key) && !isNull(key)) getBoolean(key) else fallback
    } catch (e: Exception) {
        fallback
    }
}
