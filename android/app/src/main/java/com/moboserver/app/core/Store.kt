package com.moboserver.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Server settings chosen in the app. Persisted as JSON in SharedPreferences so
 * the server always starts with exactly what the user configured.
 */
data class ServerConfig(
    val serverName: String = "MoboServer",
    val port: Int = 19132,
    val maxPlayers: Int = 4,
    val authEnabled: Boolean = true,
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val viewDistance: Int = 8,
    val generator: String = "terrain",
    val autoForward: Boolean = true,
    val activeWorldId: String = "",
    /** Relay "host:porta" do túnel UDP. Vazio = túnel desligado. */
    val relayAddr: String = "",
    /** Token aceito pelo relay. */
    val relayToken: String = "",
    /** Usa TLS na conexão de controle do túnel. */
    val relayTls: Boolean = true,
    /** Aceita subir sem verificação de conta quando o login Xbox falha. */
    val allowOpenMode: Boolean = false,
)

/** Tiny key/value store shared by the UI, the service and the controller. */
object Store {
    private const val PREFS = "moboserver"
    private const val KEY_CONFIG = "config"

    private var prefs: android.content.SharedPreferences? = null
    private val _config = MutableStateFlow(ServerConfig())
    val config: StateFlow<ServerConfig> = _config

    fun init(context: Context) {
        if (prefs != null) {
            return
        }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _config.value = decode(p.getString(KEY_CONFIG, null))
    }

    fun save(config: ServerConfig) {
        _config.value = config
        prefs?.edit()?.putString(KEY_CONFIG, encode(config))?.apply()
    }

    fun update(block: (ServerConfig) -> ServerConfig) {
        save(block(_config.value))
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
    }.toString()

    private fun decode(raw: String?): ServerConfig {
        val defaults = ServerConfig()
        if (raw.isNullOrBlank()) {
            return defaults
        }
        return try {
            val o = JSONObject(raw)
            ServerConfig(
                serverName = o.optString("serverName", defaults.serverName).ifBlank { defaults.serverName },
                port = o.optInt("port", defaults.port).coerceIn(1024, 65535),
                maxPlayers = o.optInt("maxPlayers", defaults.maxPlayers).coerceIn(1, 30),
                authEnabled = o.optBoolean("authEnabled", defaults.authEnabled),
                gameMode = o.optString("gameMode", defaults.gameMode),
                difficulty = o.optString("difficulty", defaults.difficulty),
                viewDistance = o.optInt("viewDistance", defaults.viewDistance).coerceIn(4, 24),
                generator = o.optString("generator", defaults.generator),
                autoForward = o.optBoolean("autoForward", defaults.autoForward),
                activeWorldId = o.optString("activeWorldId", defaults.activeWorldId),
                relayAddr = o.optString("relayAddr", defaults.relayAddr).trim(),
                relayToken = o.optString("relayToken", defaults.relayToken).trim(),
                relayTls = o.optBoolean("relayTls", defaults.relayTls),
                allowOpenMode = o.optBoolean("allowOpenMode", defaults.allowOpenMode),
            )
        } catch (e: Exception) {
            defaults
        }
    }
}
