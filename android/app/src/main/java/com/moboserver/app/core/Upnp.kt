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
    private const val SSDP_PORT = 1900

    fun forward(context: Context, port: Int, localIp: String): UpnpResult {
        val lock = acquireMulticastLock(context)
        try {
            val services = discover()
            if (services.isEmpty()) {
                return UpnpResult(
                    false,
                    "Não encontrei um roteador com UPnP. Libere a porta $port/UDP manualmente nas configurações do roteador.",
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

    private fun discover(): List<Service> {
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n")
                append("\r\n")
            }.toByteArray()

            val group = InetAddress.getByName(SSDP_ADDRESS)
            repeat(2) {
                try {
                    socket.send(DatagramPacket(request, request.size, group, SSDP_PORT))
                } catch (e: Exception) {
                    // keep going: some networks drop the first packet
                }
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
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
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
