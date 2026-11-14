package com.moboserver.app.core

/**
 * Classifica os endereços que o app pode divulgar, sem inventar um "IP público"
 * a partir do que o roteador ou a operadora reportam.
 *
 * Regras (RFC 6598 para CGNAT):
 *  - 100.64.0.0/10 nunca é oferecido a amigos, nem como IP público nem como WAN
 *    (verificado tanto no endereço da LAN quanto no WAN informado pelo roteador);
 *  - "verificado" exige teste externo real, não HTTP localhost nem aceite UPnP;
 *  - IP observado por STUN é apresentado, mas com "não verificado";
 *  - endereço WAN do roteador é rotulado como tal;
 *  - sem nada externo, o texto é o da rede local.
 *
 * Kotlin puro (sem Android) para rodar nos testes de JVM.
 */
data class AddressChoice(
    val share: String,
    /** "verificado", "observado", "wan", "local" ou "nenhum". */
    val kind: String,
    val status: String,
    val cgnat: Boolean,
)

object AddressClassifier {

    /**
     * Valida IPv4 de verdade: quatro octetos numéricos entre 0 e 255.
     * Devolve null quando não é um endereço válido.
     */
    fun parseIpv4(ip: String?): List<Int>? {
        val value = ip?.trim() ?: return null
        if (value.isEmpty()) return null
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets
    }

    /** 100.64.0.0/10 — faixa compartilhada de operadoras (RFC 6598). */
    fun isCgnat(ip: String?): Boolean {
        val octets = parseIpv4(ip) ?: return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    fun classify(
        lanIp: String?,
        wanIp: String?,
        publicIp: String?,
        port: Int,
        verified: Boolean?,
        // O CGNAT pode aparecer no IP da LAN ou no endereço WAN do roteador: os
        // dois são considerados, não só a LAN.
        cgnat: Boolean = isCgnat(lanIp) || isCgnat(wanIp),
    ): AddressChoice {
        val publicUsable = publicIp?.trim()?.takeIf { it.isNotEmpty() && !isCgnat(it) }
        val wanUsable = wanIp?.trim()?.takeIf { it.isNotEmpty() && !isCgnat(it) }
        val lan = lanIp?.trim()?.takeIf { it.isNotEmpty() }
        val confirmed = verified == true && publicUsable != null

        val share = when {
            confirmed -> "$publicUsable:$port"
            publicUsable != null -> "$publicUsable:$port"
            wanUsable != null -> "$wanUsable:$port"
            lan != null -> "$lan:$port"
            else -> "—"
        }
        val kind = when {
            confirmed -> "verificado"
            publicUsable != null -> "observado"
            wanUsable != null -> "wan"
            lan != null -> "local"
            else -> "nenhum"
        }
        val base = when {
            confirmed -> "Teste externo confirmou o acesso"
            // IP visto por STUN ou endereço do roteador: nada disso confirma acesso externo.
            publicUsable != null || wanUsable != null -> "Acesso não verificado"
            cgnat -> "Rede da operadora (CGNAT): o endereço 100.64/10 não serve para amigos"
            else -> "Não verificado"
        }
        // Com IP observado atrás de CGNAT as duas informações importam: o
        // endereço é mostrado, mas o aviso da operadora continua visível.
        val status = if (cgnat && !confirmed && publicUsable != null) {
            "$base · rede da operadora (CGNAT)"
        } else {
            base
        }
        return AddressChoice(share, kind, status, cgnat)
    }
}
