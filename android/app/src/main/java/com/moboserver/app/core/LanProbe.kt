package com.moboserver.app.core

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Read-only HTTP page served while the server runs.
 *
 * It exists to answer one question that used to be impossible to settle from
 * the phone: "is this device reachable from another device on the same Wi-Fi?".
 * If a PC opens `http://<ip-do-celular>:8080` and sees this page, the network
 * path works and any remaining problem is on the Minecraft side; if the page
 * does not load, the router is isolating the devices (AP isolation / different
 * network) — no amount of port forwarding fixes that.
 */
object LanProbe {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var port: Int = 0
    @Volatile private var status: () -> String = { "" }

    /** Tries [firstPort] and the next four ports; returns the bound port or 0. */
    @Synchronized
    fun start(firstPort: Int, status: () -> String): Int {
        stop()
        this.status = status
        for (candidate in firstPort until firstPort + 5) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("0.0.0.0", candidate))
                socket = server
                port = candidate
                worker = thread(isDaemon = true, name = "MoboLanProbe") { loop(server) }
                return candidate
            } catch (e: Exception) {
                // port busy: try the next one
            }
        }
        return 0
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // already closed
        }
        socket = null
        worker = null
        port = 0
    }

    fun boundPort(): Int = port

    private fun loop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            try {
                client.soTimeout = 3000
                val reader = client.getInputStream().bufferedReader()
                reader.readLine() // request line, unused
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val bytes = page().toByteArray(Charsets.UTF_8)
                val out = client.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
                )
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                // client went away: ignore and keep serving
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun page(): String {
        val details = escape(runCatching { status() }.getOrDefault(""))
        return """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MoboServer</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0a0e09;color:#e9efe4;
display:flex;justify-content:center;padding:32px}
.card{max-width:520px;width:100%;background:#131a11;border:1px solid #2e3a2a;
border-radius:20px;padding:28px}
h1{margin:0 0 4px;font-size:22px;color:#8be04a}
p{color:#9fac99;line-height:1.5}
.ok{display:inline-block;background:#2b451b;color:#8be04a;padding:6px 12px;
border-radius:999px;font-size:13px;margin-bottom:16px}
pre{background:#0a0e09;padding:12px;border-radius:12px;color:#e9efe4;
font-size:12px;white-space:pre-wrap;word-break:break-word}
</style></head><body><div class="card">
<h1>MoboServer</h1>
<div class="ok">Rede local funcionando</div>
<p>Se voce esta vendo esta pagina de outro aparelho, o celular esta acessivel
na rede. No Minecraft, use <b>Jogar &rarr; Servidores &rarr; Adicionar servidor</b>
com o endereco abaixo.</p>
<pre>$details</pre>
<p>Se esta pagina nao abria, o roteador estaria isolando os aparelhos.</p>
</div></body></html>"""
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
