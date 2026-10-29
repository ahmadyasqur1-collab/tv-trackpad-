package com.lgtrackpad

import android.content.Context
import org.json.JSONObject

/** Pengelola: memilih backend sesuai merek TV. */
class TvClient(ctx: Context, private val ui: Backend.Ui) {
    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private var backend: Backend? = null

    var brand: String = prefs.getString("brand", "lg") ?: "lg"
        private set
    var ip: String = prefs.getString("ip", "") ?: ""
        private set

    fun connect(newBrand: String, newIp: String) {
        brand = newBrand; ip = newIp
        prefs.edit().putString("brand", brand).putString("ip", ip).apply()
        launch()
    }

    fun resume() { if (ip.isNotEmpty() && backend == null) launch() }

    private fun create(): Backend = when (brand) {
        "roku" -> RokuBackend(ip, prefs, ui)
        "samsung" -> SamsungBackend(ip, prefs, ui)
        "androidtv" -> AndroidTvBackend(ip, prefs, ui)
        else -> LgBackend(ip, prefs, ui)
    }

    private fun launch() {
        backend?.stop()
        if (ip.isEmpty()) { ui.status(false, "IP TV belum diisi"); return }
        backend = create().also { it.start() }
    }

    fun pause() { backend?.stop(); backend = null }

    fun forget() {
        if (ip.isEmpty()) return
        (backend ?: create()).forgetCredentials()
        launch()
    }

    fun submitPin(pin: String) { backend?.submitPin(pin) }

    fun handlePhone(t: String) {
        val m = try { JSONObject(t) } catch (_: Exception) { return }
        backend?.onPhone(m)
    }
}
