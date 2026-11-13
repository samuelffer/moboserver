package com.moboserver.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Serialização pura (testada na JVM): ver ConfigCodec. */
    private fun encode(c: ServerConfig): String = ConfigCodec.encode(c)

    /** Leitura tolerante: um campo estragado não apaga os outros. */
    private fun decode(raw: String?): ServerConfig = ConfigCodec.decode(raw)
}
