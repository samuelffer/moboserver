package com.moboserver.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enumerates the network interfaces with the public Android APIs and hands them
 * to the embedded Go process.
 *
 * Why this exists: Android denies NETLINK_ROUTE sockets to normal apps, so the
 * Go standard library fails to list interfaces with
 * `route ip+net: netlinkrib: permission denied`. Pion's ICE agent enumerates the
 * interfaces when it is created, which made every NetherNet connection fail with
 *
 *     create peer connection: nethernet: obtain local ICE parameters:
 *     failed to create network: route ip+net: netlinkrib: permission denied
 *
 * `java.net.NetworkInterface` does not use netlink (it goes through the platform
 * API), so the list is written to a JSON file that the Go process reads and
 * injects into Pion (see server/icenet.go). The file is rewritten whenever the
 * network changes, because the Wi-Fi address can change while the server runs.
 */
object IfaceReporter {
    private const val TAG = "MoboServer"
    private const val WATCH_INTERVAL_MS = 15_000L

    /** Snapshot summary shown in the app's console. */
    data class Snapshot(val interfaces: Int, val addresses: List<String>) {
        val summary: String
            get() = if (addresses.isEmpty()) {
                "$interfaces interface(s), nenhum endereço utilizável"
            } else {
                addresses.joinToString(", ")
            }
    }

    fun path(context: Context): File = File(File(context.filesDir, "net"), "ifaces.json")

    /**
     * Writes the interface list and returns a summary. Returns null when the
     * list could not be produced at all (then the Go process will report the
     * ICE failure instead of hiding it).
     */
    fun write(context: Context): Snapshot? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível enumerar interfaces: ${e.message}")
            return null
        }

        val array = JSONArray()
        val addresses = ArrayList<String>()
        var usable = 0
        for (ni in interfaces) {
            try {
                val entry = JSONObject()
                entry.put("name", ni.name ?: "")
                entry.put("index", try { ni.index } catch (e: Exception) { 0 })
                entry.put("mtu", try { ni.mtu } catch (e: Exception) { 1500 })
                entry.put("up", try { ni.isUp } catch (e: Exception) { true })
                entry.put("loopback", try { ni.isLoopback } catch (e: Exception) { false })

                val addrs = JSONArray()
                for (address in ni.interfaceAddresses) {
                    val inet = address.address ?: continue
                    val host = inet.hostAddress ?: continue
                    // Prefixo do endereço; InterfaceAddress sempre traz um valor
                    // válido, mas 32/128 servem de reserva.
                    var prefix = address.networkPrefixLength.toInt()
                    if (prefix <= 0 || prefix > 128) {
                        prefix = if (inet.address.size == 4) 32 else 128
                    }
                    addrs.put(JSONObject().put("ip", host).put("prefix", prefix))
                    val loopback = try { ni.isLoopback } catch (e: Exception) { false }
                    val up = try { ni.isUp } catch (e: Exception) { true }
                    if (up && !loopback && !host.startsWith("fe80")) {
                        addresses.add("${ni.name}/$host")
                    }
                }
                entry.put("addrs", addrs)
                if (addrs.length() > 0) usable++
                array.put(entry)
            } catch (e: Exception) {
                // Uma interface problemática não pode derrubar a lista inteira.
                Log.w(TAG, "interface ignorada: ${e.message}")
            }
        }

        if (array.length() == 0) return null

        val document = JSONObject()
            .put("generated", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            .put("source", "android")
            .put("interfaces", array)

        val target = path(context)
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(document.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(document.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível gravar a lista de interfaces: ${e.message}")
            return null
        }
        return Snapshot(usable, addresses)
    }

    /**
     * Rewrites the file while [isActive] returns true, so a Wi-Fi address change
     * (or switching to mobile data) is picked up by the next ICE gathering.
     */
    fun startWatching(context: Context, isActive: () -> Boolean) {
        val appContext = context.applicationContext
        Thread({
            var last = ""
            while (isActive()) {
                val snapshot = write(appContext)
                val current = snapshot?.summary ?: ""
                if (current != last && current.isNotEmpty()) {
                    last = current
                    Log.i(TAG, "lista de interfaces atualizada: $current")
                }
                try {
                    Thread.sleep(WATCH_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "moboserver-ifaces").apply { isDaemon = true }.start()
    }
}
