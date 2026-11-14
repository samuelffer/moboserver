package com.moboserver.app.ui

/**
 * Marca quando a primeira leitura real de dados terminou. A tela de abertura
 * (SplashScreen compat) fica visível só até aqui — nunca por um atraso
 * artificial.
 */
object UiReadiness {
    @Volatile
    var ready: Boolean = false
}
