package com.lgtrackpad

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

class MainActivity : Activity(), Backend.Ui {
    private lateinit var web: WebView
    private lateinit var tv: TvClient
    private var scanning = false
    private var lastStatus: JSONObject? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tv = TvClient(this, this)

        web = object : WebView(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val ic = super.onCreateInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                return ic
            }
        }
        setContentView(web)
        web.setBackgroundColor(0xFF0E1117.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.isHapticFeedbackEnabled = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onStart() {
        super.onStart()
        tv.resume()
    }

    override fun onStop() {
        super.onStop()
        tv.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        }
    }

    // ---------- Backend.Ui ----------
    override fun status(ok: Boolean, msg: String) {
        val o = JSONObject().put("t", "status").put("ok", ok).put("msg", msg)
        lastStatus = o
        push(o)
    }

    override fun askPin() { push(JSONObject().put("t", "askpin")) }

    private fun push(o: JSONObject) {
        runOnUiThread { web.evaluateJavascript("window.onNative&&window.onNative(" + o.toString() + ")", null) }
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        push(JSONObject().put("t", "scanStart"))
        Scanner.scan(this,
            { ip, brand, name -> push(JSONObject().put("t", "found").put("ip", ip).put("brand", brand).put("name", name)) },
            { scanning = false; push(JSONObject().put("t", "scanDone")) })
    }

    inner class Bridge {
        @JavascriptInterface fun send(s: String) { tv.handlePhone(s) }
        @JavascriptInterface fun scan() { startScan() }
        @JavascriptInterface fun connect(brand: String, ip: String) { tv.connect(brand, ip.trim()) }
        @JavascriptInterface fun pin(code: String) { tv.submitPin(code) }
        @JavascriptInterface fun forget() { tv.forget() }
        @JavascriptInterface fun state(): String = JSONObject().put("ip", tv.ip).put("brand", tv.brand).toString()
        @JavascriptInterface fun ready() {
            push(JSONObject().put("t", "brand").put("brand", tv.brand))
            lastStatus?.let { push(it) }
            if (tv.ip.isEmpty()) push(JSONObject().put("t", "needsetup"))
        }
    }
}
