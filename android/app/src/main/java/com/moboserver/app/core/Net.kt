package com.moboserver.app.core

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.Random

/** Network helpers: local IP, public IP (via STUN) and a reachability check. */
object Net {

    /** Best guess for the Wi-Fi IPv4 address of this device. */
    fun lanIp(): String? {
        var fallback: String? = null
        try {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains(':')) continue // IPv6: not used by Bedrock
                    if (addr.isSiteLocalAddress) {
                        val name = iface.name ?: ""
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                            return host
                        }
                        if (fallback == null) fallback = host
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return fallback
    }

    private val STUN_SERVERS = listOf(
        "stun.l.google.com:19302",
        "stun1.l.google.com:19302",
        "stun.cloudflare.com:3478",
    )

    /** Public IP of the connection, discovered with a STUN binding request. */
    fun publicIp(): String? = stunLookup()?.first

    /** Returns (ip, port) as seen from the internet, or null when STUN failed. */
    fun stunLookup(): Pair<String, Int>? {
        for (server in STUN_SERVERS) {
            try {
                val parts = server.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 3478
                val address = InetAddress.getAllByName(host).firstOrNull() ?: continue

                DatagramSocket().use { socket ->
                    socket.soTimeout = 2500
                    val txId = ByteArray(12).also { Random().nextBytes(it) }
                    val request = ByteArray(20)
                    request[0] = 0x00
                    request[1] = 0x01
                    request[4] = 0x21
                    request[5] = 0x12
                    request[6] = 0xA4.toByte()
                    request[7] = 0x42
                    System.arraycopy(txId, 0, request, 8, 12)
                    socket.send(DatagramPacket(request, request.size, address, port))

                    val buffer = ByteArray(512)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    val length = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                    var offset = 20
                    val end = minOf(20 + length, response.length)
                    while (offset + 4 <= end) {
                        val type = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
                        val attrLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
                        val valueOffset = offset + 4
                        if (type == 0x0020 && attrLength >= 8 && valueOffset + 8 <= end) {
                            val family = buffer[valueOffset + 1].toInt() and 0xFF
                            if (family == 0x01) {
                                var mappedPort = ((buffer[valueOffset + 2].toInt() and 0xFF) shl 8) or (buffer[valueOffset + 3].toInt() and 0xFF)
                                mappedPort = mappedPort xor 0x2112
                                val ip = byteArrayOf(
                                    (buffer[valueOffset + 4].toInt() xor 0x21).toByte(),
                                    (buffer[valueOffset + 5].toInt() xor 0x12).toByte(),
                                    (buffer[valueOffset + 6].toInt() xor 0xA4.toInt()).toByte(),
                                    (buffer[valueOffset + 7].toInt() xor 0x42).toByte(),
                                )
                                return InetAddress.getByAddress(ip).hostAddress to mappedPort
                            }
                        }
                        if (type == 0x0001 && attrLength >= 8 && valueOffset + 8 <= end) {
                            val mappedPort = ((buffer[valueOffset + 2].toInt() and 0xFF) shl 8) or (buffer[valueOffset + 3].toInt() and 0xFF)
                            val ip = byteArrayOf(
                                buffer[valueOffset + 4],
                                buffer[valueOffset + 5],
                                buffer[valueOffset + 6],
                                buffer[valueOffset + 7],
                            )
                            return InetAddress.getByAddress(ip).hostAddress to mappedPort
                        }
                        offset = valueOffset + ((attrLength + 3) / 4) * 4
                    }
                }
            } catch (e: Exception) {
                // try the next server
            }
        }
        return null
    }

    /**
     * Asks a public Minecraft status API whether the server can be reached from
     * the internet. Best effort: a failure simply means "could not check".
     */
    fun checkBedrockReachable(ip: String, port: Int, timeoutMs: Int = 7000): String {
        val address = "$ip:$port"
        val urls = listOf(
            "https://api.mcsrvstat.us/bedrock/3/$address",
            "https://api.mcsrvstat.us/bedrock/$address",
        )
        for (url in urls) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", "MoboServer/1.0 (Android)")
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    ""
                }
                connection.disconnect()
                if (body.isBlank()) continue
                val json = JSONObject(body)
                return if (json.optBoolean("online")) {
                    "Acessível pela internet! Seus amigos já podem entrar."
                } else {
                    "Ainda não acessível. Libere a porta no roteador ou verifique o roteamento."
                }
            } catch (e: Exception) {
                // try the next endpoint
            }
        }
        return "Não foi possível verificar agora. Confira sua conexão com a internet."
    }
}
