package com.moboserver.app.core

import org.json.JSONObject

/**
 * Serialização da configuração em JSON (Kotlin puro, testado na JVM).
 *
 * A leitura é tolerante de propósito: antes, qualquer exceção devolvia a
 * configuração padrão inteira — inclusive `activeWorldId`. Bastava um campo com
 * tipo estranho para o app "esquecer" o mundo selecionado ao reabrir. Agora cada
 * campo é lido isoladamente e a seleção é preservada.
 */
object ConfigCodec {

    fun encode(c: ServerConfig): String = JSONObject().apply {
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

    fun decode(raw: String?): ServerConfig {
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
            // O campo que interessa para "o servidor abriu sem mundo": ele nunca
            // é descartado por causa de outro campo.
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
        // Aceita número salvo como texto por uma versão antiga.
        try {
            getString(key).trim().toInt()
        } catch (e2: Exception) {
            fallback
        }
    }

    private fun JSONObject.readBool(key: String, fallback: Boolean): Boolean = try {
        if (has(key) && !isNull(key)) getBoolean(key) else fallback
    } catch (e: Exception) {
        try {
            getString(key).trim().equals("true", ignoreCase = true)
        } catch (e2: Exception) {
            fallback
        }
    }
}
