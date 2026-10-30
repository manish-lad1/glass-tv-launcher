package com.manishlad.glasslauncher.companion

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

private const val TAG = "GlassLauncher"

/** Fixed name for the phone page, so bookmarks and pairing survive the router changing the TV's IP. */
const val HOST_NAME = "glass-tv"
const val HOST_DOMAIN = "$HOST_NAME.local"

private const val MDNS_PORT = 5353
private val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
private const val TYPE_A = 1
private const val TYPE_ANY = 255

/**
 * A minimal mDNS responder for one name: answers A queries for [HOST_DOMAIN] with the TV's current
 * IPv4 address. Phones on the same Wi-Fi resolve `.local` names this way, without the router.
 * Call [refresh] periodically: if the TV's address changed, it re-binds and re-announces.
 */
class HostNameResponder(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    // Wi-Fi drivers drop multicast to save power unless an app holds this lock.
    private val multicastLock = wifi?.createMulticastLock("glass-mdns")?.apply { setReferenceCounted(false) }
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    private var announced: Inet4Address? = null
    private var lastAnswerLog = 0L

    @Synchronized
    fun refresh() {
        val ip = localIpv4().firstOrNull()?.let { InetAddress.getByName(it) as? Inet4Address }
        if (ip == announced && socket != null) return
        close()
        if (ip == null) return
        try {
            multicastLock?.acquire()
            val nif = NetworkInterface.getByInetAddress(ip)
            val s = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                networkInterface = nif
                timeToLive = 255
                joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), nif)
            }
            socket = s
            announced = ip
            thread = Thread({ serve(s, ip) }, "glass-mdns").apply { isDaemon = true; start() }
            // Unsolicited announcement, as mDNS recommends after starting or changing address.
            repeat(2) { send(s, answer(ip, id = 0, question = null), MDNS_GROUP, MDNS_PORT) }
            Log.i(TAG, "mDNS: $HOST_DOMAIN -> ${ip.hostAddress} on ${nif?.name}")
        } catch (e: Exception) {
            Log.w(TAG, "mDNS start failed: ${e.javaClass.simpleName}: ${e.message}")
            close()
        }
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        thread = null
        announced = null
    }

    private fun serve(s: MulticastSocket, ip: Inet4Address) {
        val buf = ByteArray(1500)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: Exception) {
                return // closed
            }
            val q = parseQuery(p.data, p.length) ?: continue
            if (!q.name.equals(HOST_DOMAIN, ignoreCase = true) || (q.type != TYPE_A && q.type != TYPE_ANY)) continue
            val now = System.currentTimeMillis()
            if (now - lastAnswerLog > 60_000) {
                lastAnswerLog = now
                Log.i(TAG, "mDNS: answered $HOST_DOMAIN query")
            }
            if (p.port != MDNS_PORT) {
                // Legacy one-shot query: reply directly, echoing the id and question.
                send(s, answer(ip, q.id, q.raw), p.address, p.port)
            } else {
                send(s, answer(ip, id = 0, question = null), MDNS_GROUP, MDNS_PORT)
                if (q.unicastResponse) send(s, answer(ip, id = 0, question = null), p.address, p.port)
            }
        }
    }

    private fun send(s: MulticastSocket, data: ByteArray, to: InetAddress, port: Int) {
        runCatching { s.send(DatagramPacket(data, data.size, to, port)) }
    }

    private class Query(val id: Int, val name: String, val type: Int, val unicastResponse: Boolean, val raw: ByteArray)

    /** First question of a DNS query (responses are ignored). */
    private fun parseQuery(d: ByteArray, len: Int): Query? {
        if (len < 12) return null
        val flags = u16(d, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if (u16(d, 4) < 1) return null
        var i = 12
        val labels = ArrayList<String>()
        while (i < len) {
            val l = d[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0) return null // compression isn't used in the first question name
            if (i + 1 + l > len) return null
            labels += String(d, i + 1, l, Charsets.UTF_8)
            i += 1 + l
        }
        if (i + 4 > len) return null
        val qtype = u16(d, i)
        val qclass = u16(d, i + 2)
        return Query(u16(d, 0), labels.joinToString("."), qtype, qclass and 0x8000 != 0, d.copyOfRange(12, i + 4))
    }

    /** Authoritative response with one A record (cache-flush, TTL 120 s), optionally echoing a question. */
    private fun answer(ip: Inet4Address, id: Int, question: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        w16(id); w16(0x8400); w16(if (question != null) 1 else 0); w16(1); w16(0); w16(0)
        question?.let { out.write(it) }
        for (label in HOST_DOMAIN.split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        w16(TYPE_A)
        w16(if (question != null) 0x0001 else 0x8001) // class IN (+ cache flush on multicast)
        w16(0); w16(120) // TTL
        w16(4); out.write(ip.address)
        return out.toByteArray()
    }

    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
}
