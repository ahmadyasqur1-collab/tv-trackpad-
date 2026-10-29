package com.lgtrackpad

import android.content.SharedPreferences
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLSocket

/** Protobuf mini (hanya yang dibutuhkan protokol Android TV Remote v2). */
object Pb {
    class Fields(private val m: Map<Int, List<Any>>) {
        fun has(f: Int) = m.containsKey(f)
        fun bytes(f: Int): ByteArray? = m[f]?.firstOrNull() as? ByteArray
        fun long(f: Int): Long? = m[f]?.firstOrNull() as? Long
    }

    fun varint(v: Long): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v
        while ((x and 0x7FL.inv()) != 0L) { o.write((((x and 0x7F) or 0x80)).toInt()); x = x ushr 7 }
        o.write(x.toInt())
        return o.toByteArray()
    }
    fun fInt(f: Int, v: Long): ByteArray = varint((f shl 3).toLong()) + varint(v)
    fun fInt(f: Int, v: Int): ByteArray = fInt(f, v.toLong())
    fun fBytes(f: Int, b: ByteArray): ByteArray = varint(((f shl 3) or 2).toLong()) + varint(b.size.toLong()) + b
    fun fStr(f: Int, s: String): ByteArray = fBytes(f, s.toByteArray())

    fun parse(b: ByteArray): Fields {
        val out = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun rv(): Long { var r = 0L; var s = 0; while (i < b.size) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if ((c and 0x80) == 0) break; s += 7 }; return r }
        while (i < b.size) {
            val tag = rv()
            val f = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out.getOrPut(f) { ArrayList() }.add(rv())
                2 -> { val n = rv().toInt(); if (n < 0 || i + n > b.size) break; out.getOrPut(f) { ArrayList() }.add(b.copyOfRange(i, i + n)); i += n }
                1 -> i += 8
                5 -> i += 4
                else -> break
            }
        }
        return Fields(out)
    }
}

/** Android TV / Google TV (TCL, Sony, Xiaomi, Philips, dll) — Remote Protocol v2. */
class AndroidTvBackend(ip: String, prefs: SharedPreferences, ui: Ui) : DpadBackend(ip, prefs, ui) {
    private var pairSock: SSLSocket? = null
    private var remoteSock: SSLSocket? = null
    @Volatile private var remoteOut: OutputStream? = null
    private val pinQ = LinkedBlockingQueue<String>()

    private lateinit var priv: PrivateKey
    private lateinit var cert: X509Certificate
    private lateinit var kmf: KeyManagerFactory

    private val pairedKey get() = "atv_paired_$ip"

    override fun forgetCredentials() { prefs.edit().remove(pairedKey).apply() }

    override fun closeAll() {
        remoteOut = null
        try { pairSock?.close() } catch (_: Exception) {}
        try { remoteSock?.close() } catch (_: Exception) {}
        pairSock = null; remoteSock = null
    }

    override fun submitPin(pin: String) { pinQ.offer(pin.trim()) }

    // ---------- Sertifikat klien (dibuat sekali, disimpan) ----------
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val n = body.size
        when {
            n < 128 -> o.write(n)
            n < 256 -> { o.write(0x81); o.write(n) }
            else -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
        }
        o.write(body)
        return o.toByteArray()
    }

    private fun makeCert(kpPub: java.security.PublicKey, kpPriv: PrivateKey): ByteArray {
        val sigAlg = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + der(0x05, ByteArray(0)))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0C, "TVTrackpad".toByteArray()))))
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val validity = der(0x30,
            der(0x17, fmt.format(Date(now - 86400000L)).toByteArray()) +
            der(0x17, fmt.format(Date(now + 15L * 365 * 86400000L)).toByteArray()))
        val serial = der(0x02, BigInteger.valueOf(now).toByteArray())
        val version = der(0xA0, der(0x02, byteArrayOf(2)))
        val tbs = der(0x30, version + serial + sigAlg + name + validity + name + kpPub.encoded)
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(kpPriv); update(tbs) }.sign()
        return der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + sig))
    }

    private fun loadIdentity() {
        val kf = KeyFactory.getInstance("RSA")
        val cf = CertificateFactory.getInstance("X.509")
        val pk = prefs.getString("atv_priv", null)
        val ct = prefs.getString("atv_cert", null)
        if (pk != null && ct != null) {
            priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(pk, Base64.NO_WRAP)))
            cert = cf.generateCertificate(ByteArrayInputStream(Base64.decode(ct, Base64.NO_WRAP))) as X509Certificate
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val certBytes = makeCert(kp.public, kp.private)
            cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            priv = kp.private
            prefs.edit()
                .putString("atv_priv", Base64.encodeToString(priv.encoded, Base64.NO_WRAP))
                .putString("atv_cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                .apply()
        }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("tvt", priv, CharArray(0), arrayOf(cert))
        kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
    }

    private fun connectTls(port: Int): SSLSocket {
        val s = Net.sslContext(kmf.keyManagers).socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(ip, port), 5000)
        s.startHandshake()
        return s
    }

    // ---------- Framing ----------
    private fun readMsg(inp: InputStream): ByteArray? {
        var len = 0; var shift = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            len = len or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        if (len < 0 || len > 1_000_000) return null
        val buf = ByteArray(len)
        var off = 0
        while (off < len) { val n = inp.read(buf, off, len - off); if (n < 0) return null; off += n }
        return buf
    }

    private fun writeMsg(out: OutputStream, msg: ByteArray) {
        synchronized(out) { out.write(Pb.varint(msg.size.toLong()) + msg); out.flush() }
    }

    // ---------- Alur utama ----------
    override fun open(g: Int) {
        status(false, "Menghubungkan ke Android TV $ip ...")
        Thread {
            try {
                if (!::kmf.isInitialized) loadIdentity()
                if (g != gen) return@Thread
                if (prefs.getBoolean(pairedKey, false)) runRemote(g) else runPairing(g)
            } catch (e: Exception) {
                if (g == gen) lost(g, "Gagal: " + (e.message ?: e.javaClass.simpleName) + ". Mencoba lagi...")
            }
        }.start()
    }

    // ---------- Pairing (port 6467) ----------
    private fun outer(field: Int, payload: ByteArray): ByteArray =
        Pb.fInt(1, 2) + Pb.fInt(2, 200) + Pb.fBytes(field, payload)

    private fun expect(inp: InputStream, field: Int): Pb.Fields {
        val m = readMsg(inp) ?: throw RuntimeException("TV menutup koneksi")
        val f = Pb.parse(m)
        val st = f.long(2)
        if (st != null && st != 200L) throw RuntimeException("TV menolak pairing (status $st)")
        if (!f.has(field)) throw RuntimeException("Respons pairing tidak sesuai")
        return f
    }

    private fun stripZero(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        return b.copyOfRange(i, b.size)
    }

    private fun runPairing(g: Int) {
        pinQ.clear()
        val s = connectTls(6467)
        pairSock = s
        val inp = s.inputStream; val out = s.outputStream
        writeMsg(out, outer(10, Pb.fStr(1, "atvremote") + Pb.fStr(2, "TV Trackpad")))
        expect(inp, 11)
        val enc = Pb.fInt(1, 3) + Pb.fInt(2, 6)
        writeMsg(out, outer(20, Pb.fBytes(1, enc) + Pb.fInt(3, 1)))
        expect(inp, 20)
        writeMsg(out, outer(30, Pb.fBytes(1, enc) + Pb.fInt(2, 1)))
        expect(inp, 31)
        if (g != gen) return
        status(false, "Masukkan kode yang tampil di layar TV")
        ui.askPin()
        val pin = pinQ.poll(180, TimeUnit.SECONDS)
        if (g != gen) return
        if (pin == null || !Regex("^[0-9A-Fa-f]{6}$").matches(pin)) {
            lost(g, if (pin == null) "Waktu memasukkan kode habis. Mencoba lagi..." else "Kode harus 6 karakter (0-9, A-F). Mencoba lagi...")
            return
        }
        val pinBytes = ByteArray(3) { pin.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val srvCert = s.session.peerCertificates[0] as X509Certificate
        val cKey = cert.publicKey as RSAPublicKey
        val sKey = srvCert.publicKey as RSAPublicKey
        val md = MessageDigest.getInstance("SHA-256")
        md.update(stripZero(cKey.modulus.toByteArray())); md.update(stripZero(cKey.publicExponent.toByteArray()))
        md.update(stripZero(sKey.modulus.toByteArray())); md.update(stripZero(sKey.publicExponent.toByteArray()))
        md.update(pinBytes.copyOfRange(1, 3))
        val hash = md.digest()
        if (hash[0] != pinBytes[0]) {
            lost(g, "Kode salah. Mencoba lagi...")
            return
        }
        writeMsg(out, outer(40, Pb.fBytes(1, hash)))
        expect(inp, 41)
        try { s.close() } catch (_: Exception) {}
        pairSock = null
        prefs.edit().putBoolean(pairedKey, true).apply()
        if (g == gen) runRemote(g)
    }

    // ---------- Remote (port 6466) ----------
    private fun runRemote(g: Int) {
        val s = connectTls(6466)
        remoteSock = s
        val inp = s.inputStream; val out = s.outputStream
        var active = false
        while (g == gen) {
            val m = readMsg(inp) ?: break
            val f = Pb.parse(m)
            if (f.has(1)) {
                val dev = Pb.fStr(1, "TV Trackpad") + Pb.fStr(2, "Android") + Pb.fInt(3, 1) + Pb.fStr(4, "1") +
                    Pb.fStr(5, "com.lgtrackpad") + Pb.fStr(6, "1.0")
                writeMsg(out, Pb.fBytes(1, Pb.fInt(1, 622) + Pb.fBytes(2, dev)))
            }
            if (f.has(2)) {
                writeMsg(out, Pb.fBytes(2, Pb.fInt(1, 622)))
                active = true
                remoteOut = out
                status(true, "Terhubung ke Android TV")
            }
            if (f.has(8)) {
                val v = f.bytes(8)?.let { Pb.parse(it).long(1) } ?: 1L
                writeMsg(out, Pb.fBytes(9, Pb.fInt(1, v)))
            }
        }
        if (g != gen) return
        if (!active) {
            prefs.edit().remove(pairedKey).apply()
            lost(g, "TV belum memasangkan perangkat ini. Mengulang pairing...")
        } else lost(g)
    }

    private fun key(code: Int) {
        val o = remoteOut ?: return
        try { writeMsg(o, Pb.fBytes(10, Pb.fInt(1, code) + Pb.fInt(2, 3))) } catch (_: Exception) {}
    }

    override fun press(k: String) {
        key(when (k) {
            "UP" -> 19; "DOWN" -> 20; "LEFT" -> 21; "RIGHT" -> 22; "OK" -> 23
            "BACK" -> 4; "HOME" -> 3; "VOLUMEUP" -> 24; "VOLUMEDOWN" -> 25; "MUTE" -> 164; else -> return
        })
    }

    override fun typeText(s: String) {
        for (c in s.lowercase()) {
            val code = when (c) {
                in 'a'..'z' -> 29 + (c - 'a')
                in '0'..'9' -> 7 + (c - '0')
                ' ' -> 62; '.' -> 56; ',' -> 55; '-' -> 69; '@' -> 77; '/' -> 76
                else -> -1
            }
            if (code > 0) key(code)
        }
    }
    override fun del(n: Int) { repeat(n) { key(67) } }
    override fun enter() { key(66) }
}
