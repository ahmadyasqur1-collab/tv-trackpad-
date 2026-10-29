package com.lgtrackpad

import android.content.SharedPreferences
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Roku TV (termasuk TCL Roku, Hisense Roku, dll) lewat ECP port 8060. */
class RokuBackend(ip: String, prefs: SharedPreferences, ui: Ui) : DpadBackend(ip, prefs, ui) {
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()

    override fun closeAll() {}

    override fun open(g: Int) {
        status(false, "Menghubungkan ke Roku TV $ip ...")
        http.newCall(Request.Builder().url("http://$ip:8060/query/device-info").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (g != gen) return
                status(false, "Roku tidak terjangkau. Cek IP & aktifkan Kontrol oleh aplikasi seluler. Mencoba lagi...")
                retry(5000)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (g != gen) return
                    if (it.isSuccessful) status(true, "Terhubung ke Roku TV")
                    else { status(false, "Roku menolak (kode ${it.code}). Cek pengaturan kontrol jaringan."); retry(6000) }
                }
            }
        })
    }

    private fun post(path: String) {
        http.newCall(Request.Builder().url("http://$ip:8060/keypress/$path").post("".toRequestBody(null)).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
    }

    override fun press(k: String) {
        val p = when (k) {
            "UP" -> "Up"; "DOWN" -> "Down"; "LEFT" -> "Left"; "RIGHT" -> "Right"; "OK" -> "Select"
            "BACK" -> "Back"; "HOME" -> "Home"; "VOLUMEUP" -> "VolumeUp"; "VOLUMEDOWN" -> "VolumeDown"
            "MUTE" -> "VolumeMute"; else -> return
        }
        post(p)
    }
    override fun typeText(s: String) { for (c in s) post("Lit_" + URLEncoder.encode(c.toString(), "UTF-8")) }
    override fun del(n: Int) { repeat(n) { post("Backspace") } }
    override fun enter() { post("Enter") }
}
