package com.lgtrackpad

import android.content.SharedPreferences
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

/** LG webOS: kursor penuh lewat socket pointer. */
class LgBackend(ip: String, prefs: SharedPreferences, ui: Ui) : Backend(ip, prefs, ui) {
    private val client = Net.client()
    private var tv: WebSocket? = null
    private var ptr: WebSocket? = null
    private var reqN = 0

    private val perms = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
        "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY", "CONTROL_FAVORITE_GROUP", "CONTROL_USER_INFO", "CONTROL_BLUETOOTH",
        "CONTROL_TIMER_INFO", "CONTROL_RECORDING", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
        "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
        "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO", "READ_SETTINGS",
        "CONTROL_TV_POWER", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS",
        "WRITE_NOTIFICATION_ALERT", "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP",
        "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"
    )

    override fun forgetCredentials() { prefs.edit().remove("lg_key").apply() }

    override fun closeAll() {
        try { ptr?.cancel() } catch (_: Exception) {}
        try { tv?.cancel() } catch (_: Exception) {}
        ptr = null; tv = null
    }

    override fun open(g: Int) {
        status(false, "Menghubungkan ke TV LG $ip ...")
        tryUrl(g, listOf("wss://$ip:3001", "ws://$ip:3000"), 0)
    }

    private fun tryUrl(g: Int, urls: List<String>, i: Int) {
        if (g != gen) return
        if (i >= urls.size) {
            status(false, "TV tidak terjangkau. Cek TV nyala & IP benar. Mencoba lagi...")
            retry(5000); return
        }
        var opened = false
        client.newWebSocket(Request.Builder().url(urls[i]).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (g != gen) { webSocket.cancel(); return }
                opened = true; tv = webSocket; register(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (g == gen) onTvMessage(g, text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (opened) lost(g) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (g != gen) return
                if (!opened) tryUrl(g, urls, i + 1) else lost(g)
            }
        })
    }

    private fun register(ws: WebSocket) {
        val permsArr = JSONArray(perms)
        val manifest = JSONObject()
            .put("manifestVersion", 1).put("appVersion", "1.1")
            .put("signed", JSONObject()
                .put("created", "20140509").put("appId", "com.lge.test").put("vendorId", "com.lge")
                .put("localizedAppNames", JSONObject().put("", "HP Trackpad"))
                .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                .put("permissions", permsArr)
                .put("serial", "2f930e2d2cfe083771f68e4fe7bb07"))
            .put("permissions", permsArr)
        val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
        val key = prefs.getString("lg_key", null)
        if (key != null) payload.put("client-key", key)
        ws.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
        if (key == null) status(false, "Lihat layar TV, pilih YA / OK untuk mengizinkan")
    }

    private fun onTvMessage(g: Int, text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val type = m.optString("type")
        if (type == "registered") {
            m.optJSONObject("payload")?.optString("client-key")?.let { prefs.edit().putString("lg_key", it).apply() }
            tv?.send(JSONObject().put("type", "request").put("id", "ptr")
                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString())
        }
        val sp = m.optJSONObject("payload")?.optString("socketPath", "") ?: ""
        if (sp.isNotEmpty()) openPointer(g, sp)
        if (type == "error" && m.optString("id") == "register_0") {
            prefs.edit().remove("lg_key").apply()
            status(false, "Izin ditolak / kunci lama tidak valid. Mencoba lagi...")
            retry(3000)
        }
    }

    private fun openPointer(g: Int, url: String) {
        var opened = false
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (g != gen) { webSocket.cancel(); return }
                opened = true; ptr = webSocket; status(true, "Terhubung ke TV LG")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (opened) lost(g) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (g != gen) return
                if (opened) lost(g) else { status(false, "Gagal buka kontrol kursor: ${t.message}"); retry(4000) }
            }
        })
    }

    private fun ptrSend(vararg lines: String) { ptr?.send(lines.joinToString("\n") + "\n\n") }
    private fun tvRequest(uri: String, payload: JSONObject = JSONObject()) {
        tv?.send(JSONObject().put("type", "request").put("id", "r" + (++reqN)).put("uri", uri).put("payload", payload).toString())
    }

    override fun onPhone(m: JSONObject) {
        when (m.optString("t")) {
            "move" -> ptrSend("type:move", "dx:" + m.optInt("dx"), "dy:" + m.optInt("dy"), "down:" + (if (m.optBoolean("down") || m.optInt("down") == 1) 1 else 0))
            "tap" -> ptrSend("type:click")
            "scroll" -> ptrSend("type:scroll", "dx:" + m.optInt("dx"), "dy:" + m.optInt("dy"))
            "key" -> ptrSend("type:button", "name:" + m.optString("name").replace(Regex("[^A-Z0-9_]"), ""))
            "text" -> tvRequest("ssap://com.webos.service.ime/insertText", JSONObject().put("text", m.optString("text")).put("replace", 0))
            "del" -> tvRequest("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", maxOf(1, m.optInt("count"))))
            "enter" -> tvRequest("ssap://com.webos.service.ime/sendEnterKey")
        }
    }
}
