package com.moboserver.app.core

/**
 * Classifica os endereços que o app pode divulgar, sem inventar um "IP público"
 * a partir do que o roteador ou a operadora reportam.
 *
 * Regras (RFC 6598 para CGNAT):
 *  - 100.64.0.0/10 nunca é oferecido a amigos, nem como IP público nem como WAN;
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

    /** 100.64.0.0/10 — faixa compartilhada de operadoras (RFC 6598). */
    fun isCgnat(ip: String?): Boolean {
        val value = ip?.trim() ?: return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        if (parts.any { it.toIntOrNull() == null }) return false
        return first == 100 && second in 64..127
    }

    fun classify(
        lanIp: String?,
        wanIp: String?,
        publicIp: String?,
        port: Int,
        verified: Boolean?,
        cgnatLan: Boolean = isCgnat(lanIp),
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
        val status = when {
            confirmed -> "Teste externo confirmou o acesso"
            publicUsable != null -> "Acesso não verificado"
            cgnatLan -> "Rede da operadora (CGNAT): o endereço 100.64/10 não serve para amigos"
            else -> "Não verificado"
        }
        return AddressChoice(share, kind, status, cgnatLan)
    }
}
