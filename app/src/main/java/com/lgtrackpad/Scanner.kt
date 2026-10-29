package com.lgtrackpad

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cari TV di jaringan WiFi: SSDP + mDNS + scan port. */
object Scanner {
    private const val ST_LG = "urn:lge-com:service:webos-second-screen:1"
    private const val ST_ROKU = "roku:ecp"
    private const val ST_SAMSUNG = "urn:samsung.com:device:RemoteControlReceiver:1"

    fun scan(ctx: Context, onFound: (ip: String, brand: String, name: String) -> Unit, onDone: () -> Unit) {
        Thread {
            val seen = HashSet<String>()
            val rep: (String, String, String) -> Unit = { ip, brand, name ->
                val isNew = synchronized(seen) { seen.add(ip) }
                if (isNew) onFound(ip, brand, name)
            }
            var lock: WifiManager.MulticastLock? = null
            try {
                try {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    lock = wm.createMulticastLock("tvscan").apply { setReferenceCounted(false); acquire() }
                } catch (_: Exception) {}
                // SSDP & mDNS berjalan paralel
                val t1 = Thread { try { ssdp(rep) } catch (_: Exception) {} }
                val t2 = Thread { try { mdns(ctx, rep) } catch (_: Exception) {} }
                t1.start(); t2.start(); t1.join(); t2.join()
                portScan(rep)
            } catch (_: Exception) {
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                onDone()
            }
        }.start()
    }

    private fun ssdp(rep: (String, String, String) -> Unit) {
        var s: DatagramSocket? = null
        try {
            s = DatagramSocket()
            s.soTimeout = 600
            val addr = InetAddress.getByName("239.255.255.250")
            for (st in listOf(ST_LG, ST_ROKU, ST_SAMSUNG)) {
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                repeat(2) { s.send(DatagramPacket(msg, msg.size, addr, 1900)) }
            }
            val end = System.currentTimeMillis() + 3500
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    val ip = p.address.hostAddress ?: continue
                    val up = text.uppercase()
                    when {
                        up.contains("LGE-COM") || up.contains("WEBOS") -> rep(ip, "lg", "LG webOS TV")
                        up.contains("ROKU") -> rep(ip, "roku", "Roku TV (TCL Roku, dll)")
                        up.contains("SAMSUNG") -> rep(ip, "samsung", "Samsung Smart TV")
                    }
                } catch (_: SocketTimeoutException) {}
            }
        } finally { try { s?.close() } catch (_: Exception) {} }
    }

    private fun mdns(ctx: Context, rep: (String, String, String) -> Unit) {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = Collections.synchronizedList(ArrayList<NsdServiceInfo>())
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.add(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
        Thread.sleep(3500)
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        for (si in found.toList()) {
            val latch = CountDownLatch(1)
            try {
                nsd.resolveService(si, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) { latch.countDown() }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val h = serviceInfo.host?.hostAddress
                        if (h != null && !h.contains(':')) rep(h, "androidtv", serviceInfo.serviceName ?: "Android TV")
                        latch.countDown()
                    }
                })
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
    }

    private fun prefixes(): List<String> {
        val out = LinkedHashSet<String>()
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (ni in Collections.list(ifs)) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    val b = a.address
                    out.add("${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}")
                }
            }
        }
        return out.toList()
    }

    private fun open(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 450); true }
    } catch (_: Exception) { false }

    private fun portScan(rep: (String, String, String) -> Unit) {
        val pool = Executors.newFixedThreadPool(48)
        for (pre in prefixes()) for (i in 1..254) {
            val host = "$pre.$i"
            pool.execute {
                when {
                    open(host, 6466) -> rep(host, "androidtv", "Android / Google TV (TCL, Sony, Xiaomi, dll)")
                    open(host, 8060) -> rep(host, "roku", "Roku TV (TCL Roku, dll)")
                    open(host, 8002) || open(host, 8001) -> rep(host, "samsung", "Samsung Smart TV")
                    open(host, 3001) || open(host, 3000) -> rep(host, "lg", "Kemungkinan LG webOS TV")
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)
    }
}
