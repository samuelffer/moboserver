package com.moboserver.app.ui

import android.content.Context

/**
 * Preferências só de aparência. Ficam separadas da [com.moboserver.app.core.Store]
 * de propósito: mudar o tema nunca pode reescrever a configuração do servidor
 * (e, portanto, nunca pode apagar o mundo selecionado).
 */
object UiPrefs {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "moboserver_ui"
    private const val KEY_THEME = "theme"

    fun theme(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_THEME, SYSTEM)) {
            LIGHT -> LIGHT
            DARK -> DARK
            else -> SYSTEM
        }
    }

    fun setTheme(context: Context, value: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, value).apply()
    }
}
