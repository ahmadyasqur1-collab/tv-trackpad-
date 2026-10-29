package com.lgtrackpad

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Utilitas jaringan bersama: TV memakai sertifikat self-signed, jadi semua diterima. */
object Net {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun sslContext(km: Array<javax.net.ssl.KeyManager>? = null): SSLContext =
        SSLContext.getInstance("TLS").apply { init(km, arrayOf<TrustManager>(trustAll), SecureRandom()) }

    fun client(): OkHttpClient {
        val ssl = sslContext()
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** Dasar semua jenis TV. */
abstract class Backend(protected val ip: String, protected val prefs: SharedPreferences, protected val ui: Ui) {
    interface Ui {
        fun status(ok: Boolean, msg: String)
        fun askPin()
    }

    protected val main = Handler(Looper.getMainLooper())
    @Volatile protected var gen = 0

    protected abstract fun open(g: Int)
    protected abstract fun closeAll()
    abstract fun onPhone(m: JSONObject)
    open fun submitPin(pin: String) {}
    open fun forgetCredentials() {}

    fun start() {
        gen++
        main.removeCallbacksAndMessages(null)
        closeAll()
        open(gen)
    }

    fun stop() {
        gen++
        main.removeCallbacksAndMessages(null)
        closeAll()
    }

    protected fun status(ok: Boolean, msg: String) = ui.status(ok, msg)

    protected fun lost(g: Int, msg: String = "Terputus dari TV. Menyambung ulang...") {
        if (g != gen) return
        gen++
        closeAll()
        status(false, msg)
        retry(3000)
    }

    protected fun retry(ms: Long) {
        val g = gen
        main.postDelayed({ if (g == gen) start() }, ms)
    }
}

/**
 * Untuk TV tanpa kursor (Android TV, Roku, Samsung): geser = tombol panah,
 * ketuk = OK, ketuk 2 jari = Kembali, 2 jari geser = atas/bawah.
 */
abstract class DpadBackend(ip: String, prefs: SharedPreferences, ui: Ui) : Backend(ip, prefs, ui) {
    /** nama kanonik: UP DOWN LEFT RIGHT OK BACK HOME VOLUMEUP VOLUMEDOWN MUTE */
    protected abstract fun press(k: String)
    protected abstract fun typeText(s: String)
    protected abstract fun del(n: Int)
    protected abstract fun enter()

    private var ax = 0
    private var ay = 0
    private var sy = 0

    override fun onPhone(m: JSONObject) {
        when (m.optString("t")) {
            "move" -> {
                ax += m.optInt("dx"); ay += m.optInt("dy")
                if (Math.abs(ax) >= 30 || Math.abs(ay) >= 30) {
                    if (Math.abs(ax) >= Math.abs(ay)) press(if (ax > 0) "RIGHT" else "LEFT")
                    else press(if (ay > 0) "DOWN" else "UP")
                    ax = 0; ay = 0
                }
            }
            "scroll" -> {
                sy += m.optInt("dy")
                if (Math.abs(sy) >= 25) { press(if (sy > 0) "DOWN" else "UP"); sy = 0 }
            }
            "tap" -> press("OK")
            "key" -> press(m.optString("name"))
            "text" -> typeText(m.optString("text"))
            "del" -> del(maxOf(1, m.optInt("count")))
            "enter" -> enter()
        }
    }
}
