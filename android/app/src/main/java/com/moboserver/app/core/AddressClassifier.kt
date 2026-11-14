package com.moboserver.app.core

/**
 * Classifica endereços sem confundir sintaxe válida com alcance público.
 * Somente IPv4 globalmente utilizável pode ser divulgado como observado/WAN;
 * STUN, UPnP e teste local nunca confirmam acesso por si só. IPv6 é tratado à
 * parte: o caminho atual de STUN/UPnP do app é IPv4, então IPv6 fica como
 * diagnóstico/LAN formatada com colchetes, não como acesso externo confirmado.
 */
data class AddressChoice(
    val share: String,
    /** "verificado", "observado", "wan", "local" ou "nenhum". */
    val kind: String,
    val status: String,
    val cgnat: Boolean,
    val shareable: Boolean = kind != "nenhum",
    val lanAddress: String = "—",
    val lanLabel: String = "Sem endereço local",
    val wanAddress: String = "—",
    val wanLabel: String = "Sem endereço WAN informado",
    val publicAddress: String = "—",
    val publicLabel: String = "Sem endereço observado",
)

object AddressClassifier {

    /** IPv4 estrito: quatro octetos decimais, sem sinal, zeros à esquerda ou porta. */
    fun parseIpv4(ip: String?): List<Int>? {
        val value = ip?.trim().orEmpty()
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = ArrayList<Int>(4)
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0')) return null
            if (part.any { it !in '0'..'9' }) return null
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            octets.add(n)
        }
        return octets
    }

    fun isCgnat(ip: String?): Boolean {
        val o = parseIpv4(ip) ?: return false
        return o[0] == 100 && o[1] in 64..127
    }

    fun isPrivateIpv4(ip: String?): Boolean {
        val o = parseIpv4(ip) ?: return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    fun isLoopbackIpv4(ip: String?): Boolean = parseIpv4(ip)?.get(0) == 127

    /** Válido não significa público: esta função exclui as faixas especiais. */
    fun isPublicIpv4(ip: String?): Boolean = ipv4Kind(ip) == "público"

    fun ipv4Kind(ip: String?): String {
        val o = parseIpv4(ip) ?: return if (looksLikeIpv6(ip)) "IPv6" else "inválido"
        val a = o[0]; val b = o[1]; val c = o[2]
        return when {
            a == 0 -> "não especificado"
            a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) -> "privado"
            a == 100 && b in 64..127 -> "compartilhado (CGNAT)"
            a == 127 -> "loopback"
            a == 169 && b == 254 -> "link-local"
            a == 192 && b == 0 && (c == 0 || c == 2) -> "reservado/documentação"
            a == 192 && b == 88 && c == 99 -> "reservado (6to4 legado)"
            a == 198 && (b == 18 || b == 19) -> "benchmark"
            a == 198 && b == 51 && c == 100 -> "documentação"
            a == 203 && b == 0 && c == 113 -> "documentação"
            a in 224..239 -> "multicast"
            a >= 240 -> "reservado"
            else -> "público"
        }
    }

    /** Parser literal de IPv6, sem DNS e sem zone. Não transforma IPv4 em IPv6. */
    fun parseIpv6(ip: String?): List<Int>? {
        val value = ip?.trim().orEmpty()
        if (value.isEmpty() || ':' !in value || value.any { it !in "0123456789abcdefABCDEF:." }) return null
        val double = value.indexOf("::")
        if (double >= 0 && value.indexOf("::", double + 1) >= 0) return null
        val groups = ArrayList<Int>()
        fun addPart(part: String): Boolean {
            if (part.isEmpty()) return false
            if ('.' in part) {
                val v4 = parseIpv4(part) ?: return false
                groups.add((v4[0] shl 8) or v4[1])
                groups.add((v4[2] shl 8) or v4[3])
                return true
            }
            if (part.length > 4 || part.any { it !in "0123456789abcdefABCDEF" }) return false
            groups.add(part.toInt(16))
            return true
        }
        if (double >= 0) {
            val left = value.substring(0, double)
            val right = value.substring(double + 2)
            if (left.isNotEmpty()) for (p in left.split(':')) if (!addPart(p)) return null
            val before = groups.size
            if (right.isNotEmpty()) for (p in right.split(':')) if (!addPart(p)) return null
            val explicit = groups.size
            if (explicit >= 8) return null
            val zeros = List(8 - explicit) { 0 }
            return groups.subList(0, before) + zeros + groups.subList(before, groups.size)
        }
        for (p in value.split(':')) if (!addPart(p)) return null
        return if (groups.size == 8) groups else null
    }

    fun looksLikeIpv6(ip: String?): Boolean = parseIpv6(ip) != null

    fun ipv6Kind(ip: String?): String {
        val g = parseIpv6(ip) ?: return "inválido"
        if (g.all { it == 0 }) return "não especificado"
        if (g.take(7).all { it == 0 } && g[7] == 1) return "loopback"
        val first = g[0]
        return when {
            (first and 0xfe00) == 0xfc00 -> "privado (ULA)"
            (first and 0xffc0) == 0xfe80 -> "link-local"
            (first and 0xff00) == 0xff00 -> "multicast"
            first == 0x2001 && g[1] == 0x0db8 -> "documentação"
            (first and 0xe000) == 0x2000 -> "IPv6 global"
            else -> "reservado"
        }
    }

    fun formatHostPort(ip: String, port: Int): String =
        if (':' in ip) "[$ip]:$port" else "$ip:$port"

    private fun usablePublicIpv4(ip: String?): String? =
        ip?.trim()?.takeIf { isPublicIpv4(it) }

    private fun localIpv4(ip: String?): String? = ip?.trim()?.takeIf {
        val kind = ipv4Kind(it)
        kind == "privado" || kind == "compartilhado (CGNAT)" || kind == "link-local" || kind == "público"
    }

    fun classify(
        lanIp: String?,
        wanIp: String?,
        publicIp: String?,
        port: Int,
        verified: Boolean?,
        cgnat: Boolean = isCgnat(lanIp) || isCgnat(wanIp) || isCgnat(publicIp),
        verifiedIp: String? = null,
    ): AddressChoice {
        val publicUsable = usablePublicIpv4(publicIp)
        val wanUsable = usablePublicIpv4(wanIp)
        val lan4 = localIpv4(lanIp)
        val lan6 = lanIp?.trim()?.takeIf { parseIpv6(it) != null && ipv6Kind(it) !in listOf("inválido", "não especificado", "multicast", "reservado") }
        val lan = lan4 ?: lan6
        val confirmedAddress = when {
            verified != true -> null
            verifiedIp != null -> usablePublicIpv4(verifiedIp)
            else -> publicUsable
        }
        val shareAddress = confirmedAddress ?: publicUsable ?: wanUsable ?: lan
        val share = if (shareAddress != null && port in 1..65535) formatHostPort(shareAddress, port) else "—"
        val kind = when {
            confirmedAddress != null -> "verificado"
            publicUsable != null -> "observado"
            wanUsable != null -> "wan"
            lan != null && share != "—" -> "local"
            else -> "nenhum"
        }
        val base = when (kind) {
            "verificado" -> "Teste externo confirmou o acesso ao endereço divulgado"
            "observado", "wan" -> "Acesso não verificado"
            "local" -> if (cgnat) "Rede da operadora (CGNAT): endereço local compartilhado, não público" else "Endereço de rede local; acesso externo não verificado"
            else -> when {
                cgnat -> "Rede da operadora (CGNAT): não há endereço público utilizável para compartilhar"
                !publicIp.isNullOrBlank() && publicUsable == null -> "Endereço observado não é um IPv4 público utilizável"
                !wanIp.isNullOrBlank() && wanUsable == null -> "WAN informado não é um IPv4 público utilizável"
                else -> "Sem endereço utilizável para compartilhar"
            }
        }
        val status = if (cgnat && kind == "observado") "$base · rede da operadora (CGNAT)" else base
        val wanTrim = wanIp?.trim().orEmpty()
        val publicTrim = publicIp?.trim().orEmpty()
        val lanTrim = lanIp?.trim().orEmpty()
        return AddressChoice(
            share = share, kind = kind, status = status, cgnat = cgnat,
            lanAddress = if (lan != null && port in 1..65535) formatHostPort(lan, port) else "—",
            lanLabel = if (lanTrim.isEmpty()) "Sem endereço local" else "LAN (${if (lan6 != null) ipv6Kind(lanTrim) else ipv4Kind(lanTrim)})",
            wanAddress = if (wanTrim.isEmpty()) "—" else wanTrim,
            wanLabel = if (wanTrim.isEmpty()) "Sem endereço WAN informado" else "WAN do roteador (${if (looksLikeIpv6(wanTrim)) ipv6Kind(wanTrim) else ipv4Kind(wanTrim)})",
            publicAddress = if (publicTrim.isEmpty()) "—" else publicTrim,
            publicLabel = if (publicTrim.isEmpty()) "Sem endereço observado" else "Observado por STUN (${if (looksLikeIpv6(publicTrim)) ipv6Kind(publicTrim) else ipv4Kind(publicTrim)})",
        )
    }
}
