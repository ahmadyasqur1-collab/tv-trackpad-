package com.lgtrackpad

import android.content.SharedPreferences
import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder

/** Samsung Tizen (Smart TV 2016+) lewat WebSocket remote control. */
class SamsungBackend(ip: String, prefs: SharedPreferences, ui: Ui) : DpadBackend(ip, prefs, ui) {
    private val client = Net.client()
    private var ws: WebSocket? = null

    override fun forgetCredentials() { prefs.edit().remove("samsung_token").apply() }
    override fun closeAll() { try { ws?.cancel() } catch (_: Exception) {}; ws = null }

    override fun open(g: Int) {
        status(false, "Menghubungkan ke Samsung TV $ip ...")
        tryUrl(g, listOf("wss://$ip:8002", "ws://$ip:8001"), 0)
    }

    private fun tryUrl(g: Int, bases: List<String>, i: Int) {
        if (g != gen) return
        if (i >= bases.size) {
            status(false, "TV tidak terjangkau. Cek TV nyala & IP benar. Mencoba lagi...")
            retry(5000); return
        }
        val name = URLEncoder.encode(Base64.encodeToString("TV Trackpad".toByteArray(), Base64.NO_WRAP), "UTF-8")
        val token = prefs.getString("samsung_token", null)
        val url = bases[i] + "/api/v2/channels/samsung.remote.control?name=" + name + (if (token != null) "&token=$token" else "")
        var opened = false
        var connected = false
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (g != gen) { webSocket.cancel(); return }
                opened = true; ws = webSocket
                if (token == null) status(false, "Lihat layar TV, pilih Izinkan")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (g != gen) return
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (m.optString("event")) {
                    "ms.channel.connect" -> {
                        m.optJSONObject("data")?.optString("token", "")?.takeIf { it.isNotEmpty() }
                            ?.let { prefs.edit().putString("samsung_token", it).apply() }
                        connected = true
                        status(true, "Terhubung ke Samsung TV")
                    }
                    "ms.channel.unauthorized" -> {
                        prefs.edit().remove("samsung_token").apply()
                        status(false, "Izin ditolak di TV. Mencoba lagi...")
                    }
                    "ms.channel.timeOut" -> status(false, "Waktu izin habis. Mencoba lagi...")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (opened) lost(g) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (g != gen) return
                if (!opened) tryUrl(g, bases, i + 1) else lost(g)
            }
        })
    }

    private fun sendKey(k: String) {
        ws?.send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", k).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString())
    }

    override fun press(k: String) {
        sendKey(when (k) {
            "UP" -> "KEY_UP"; "DOWN" -> "KEY_DOWN"; "LEFT" -> "KEY_LEFT"; "RIGHT" -> "KEY_RIGHT"
            "OK" -> "KEY_ENTER"; "BACK" -> "KEY_RETURN"; "HOME" -> "KEY_HOME"
            "VOLUMEUP" -> "KEY_VOLUP"; "VOLUMEDOWN" -> "KEY_VOLDOWN"; "MUTE" -> "KEY_MUTE"; else -> return
        })
    }
    override fun typeText(s: String) {
        ws?.send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)).put("DataOfCmd", "base64")
            .put("Option", "false").put("TypeOfRemote", "SendInputString")).toString())
    }
    override fun del(n: Int) { repeat(n) { sendKey("KEY_BACKSPACE") } }
    override fun enter() { sendKey("KEY_ENTER") }
}
