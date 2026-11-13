package com.moboserver.app.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class UpnpResult(
    val ok: Boolean,
    val message: String,
    val externalIp: String? = null,
)

/**
 * Minimal UPnP-IGD client. It asks the home router to forward the Bedrock UDP
 * port to this phone, which is what makes the server reachable from the
 * internet without any account or third party service. Routers without UPnP
 * simply report a failure and the app shows manual instructions.
 */
object Upnp {
    private const val TAG = "MoboServer-Upnp"
    private const val SSDP_ADDRESS = "239.255.255.250"

    /** Reason the last discovery found nothing, shown in the UI. */
    @Volatile
    private var lastError: String? = null
    private const val SSDP_PORT = 1900

    fun forward(context: Context, port: Int, localIp: String): UpnpResult {
        val lock = acquireMulticastLock(context)
        try {
            val services = discover(localIp, context)
            if (services.isEmpty()) {
                val detail = lastError?.let { " ($it)" } ?: ""
                return UpnpResult(
                    false,
                    "Não encontrei um roteador com UPnP$detail. " +
                        "Libere a porta $port/UDP manualmente nas configurações do roteador.",
                )
            }
            for (service in services) {
                val externalIp = externalIp(service)
                if (addPortMapping(service, port, localIp)) {
                    val extra = externalIp?.let { " IP público: $it." } ?: ""
                    return UpnpResult(true, "Porta $port/UDP liberada no roteador automaticamente.$extra", externalIp)
                }
            }
            return UpnpResult(
                false,
                "O roteador recusou a liberação automática. Libere a porta $port/UDP manualmente.",
            )
        } catch (e: Exception) {
            Log.w(TAG, "upnp failed: ${e.message}")
            return UpnpResult(false, "Falha ao falar com o roteador via UPnP: ${e.message}")
        } finally {
            try {
                lock?.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private data class Service(val controlUrl: String, val serviceType: String)

    /**
     * SSDP search for the router. The socket is bound to the Wi-Fi address when
     * one is known: on phones with mobile data + Wi-Fi at the same time, the
     * default route may be the cellular link, and a wildcard socket would then
     * send the search where no router is listening (this is why "no router with
     * UPnP" used to show up on phones that do have it).
     */
    private fun discover(localIp: String?, context: Context): List<Service> {
        lastError = null
        val locations = LinkedHashSet<String>()
        val socket = DatagramSocket(null)
        socket.use {
            try {
                socket.reuseAddress = true
                if (localIp != null) {
                    socket.bind(InetSocketAddress(localIp, 0))
                } else {
                    socket.bind(InetSocketAddress(0))
                }
            } catch (e: Exception) {
                Log.w(TAG, "bind ssdp: ${e.message}")
                try {
                    socket.bind(InetSocketAddress(0))
                } catch (e2: Exception) {
                    return emptyList()
                }
            }
            socket.soTimeout = 1200
            val requests = listOf(
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "upnp:rootdevice",
            ).map { type ->
                buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: ").append(type).append("\r\n")
                    append("\r\n")
                }.toByteArray()
            }

            val group = InetAddress.getByName(SSDP_ADDRESS)
            // Three rounds: routers frequently drop the first multicast packet
            // while the Wi-Fi link is still waking up.
            repeat(3) { round ->
                for (request in requests) {
                    try {
                        socket.send(DatagramPacket(request, request.size, group, SSDP_PORT))
                    } catch (e: Exception) {
                        // keep going
                    }
                }
                if (round < 2) Thread.sleep(350)
            }

            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    for (line in text.split("\r\n")) {
                        if (line.startsWith("LOCATION:", ignoreCase = true)) {
                            val value = line.substringAfter(":").trim()
                            if (value.startsWith("http", ignoreCase = true)) {
                                locations.add(value)
                            }
                        }
                    }
                    if (locations.size >= 4) break
                } catch (e: Exception) {
                    break // socket timeout: no more answers
                }
            }
        }

        if (locations.isEmpty()) {
            lastError = "nenhuma resposta SSDP do roteador"
        }

        // Fallback: some routers ignore SSDP but serve the IGD description.
        Net.gatewayIp(context)?.let { gateway ->
            locations.add("http://$gateway:5000/rootDesc.xml")
            locations.add("http://$gateway:49152/description.xml")
            locations.add("http://$gateway/rootDesc.xml")
        }

        val services = ArrayList<Service>()
        for (location in locations) {
            try {
                val xml = httpGet(location) ?: continue
                services.addAll(parseServices(xml, location))
            } catch (e: Exception) {
                Log.w(TAG, "parse $location: ${e.message}")
            }
        }
        return services
    }

    private fun parseServices(xml: String, location: String): List<Service> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // feature not supported by this platform, keep the defaults
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nodes = document.getElementsByTagName("service")
        val base = URL(location)
        val result = ArrayList<Service>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val type = childText(element, "serviceType") ?: continue
            val control = childText(element, "controlURL") ?: continue
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue
            val resolved = try {
                URL(base, control).toString()
            } catch (e: Exception) {
                continue
            }
            result.add(Service(resolved, type))
        }
        return result
    }

    private fun childText(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.ifBlank { null }
    }

    private fun externalIp(service: Service): String? {
        val response = soap(service, "GetExternalIPAddress", "") ?: return null
        val match = Regex("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>").find(response)
        return match?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    private fun addPortMapping(service: Service, port: Int, localIp: String): Boolean {
        val body = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>").append(port).append("</NewExternalPort>")
            append("<NewProtocol>UDP</NewProtocol>")
            append("<NewInternalPort>").append(port).append("</NewInternalPort>")
            append("<NewInternalClient>").append(localIp).append("</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>MoboServer</NewPortMappingDescription>")
            append("<NewLeaseDuration>0</NewLeaseDuration>")
        }
        return soap(service, "AddPortMapping", body) != null
    }

    private fun soap(service: Service, action: String, body: String): String? {
        val envelope = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${service.serviceType}\">$body</u:$action></s:Body></s:Envelope>"

        val connection = URL(service.controlUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"${service.serviceType}#$action\"")
            connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) text else null
        } catch (e: Exception) {
            Log.w(TAG, "$action: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.setRequestProperty("User-Agent", "Android/UPnP MoboServer")
            val code = connection.responseCode
            if (code !in 200..299) {
                lastError = "HTTP $code em $url"
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Cleartext blocked, router refused the connection, wrong URL...
            lastError = "${e.javaClass.simpleName}: ${e.message} ($url)"
            Log.w(TAG, "httpGet $url: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            manager.createMulticastLock("moboserver-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            null
        }
    }
}
