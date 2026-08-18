package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

object FastestExisting {
    private const val TAG = "AetherWG/Fast"
    private val junk = setOf("full", "split", "dns", "AetherWG")
    private const val PROBE = "https://speed.cloudflare.com/__down?bytes=524288"

    data class Pick(val tunnel: ObservableTunnel, val ms: Long, val kbps: Int = 0)

    suspend fun pickAndUp(): Pick? {
        val mgr = Application.getTunnelManager()
        val tunnels = mgr.getTunnels().filter { it.name !in junk }
        if (tunnels.isEmpty()) return null
        val byPing = tunnels.mapNotNull { t ->
            val cfg = try {
                t.getConfigAsync()
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            val ep = cfg.peers.firstOrNull()?.endpoint?.orElse(null) ?: return@mapNotNull null
            if (ep.host.isBlank() || ep.host == "127.0.0.1") return@mapNotNull null
            val ping = tcpMs(ep.host, ep.port)
            if (ping < 0) null else t to ping
        }.sortedBy { it.second }.take(4)
        if (byPing.isEmpty()) return null

        var best: Pick? = null
        var bestBps = -1.0
        val previous = mgr.getTunnels().firstOrNull { it.state == Tunnel.State.UP }
        UserKnobs.setUserPaused(false)
        for ((t, ping) in byPing) {
            try {
                mgr.setTunnelState(t, Tunnel.State.UP)
                delay(1200)
                val bps = downloadBps()
                Log.i(TAG, "${t.name} ping=${ping}ms download=${bps.toInt()} B/s")
                if (bps > bestBps) {
                    bestBps = bps
                    best = Pick(t, ping, (bps / 1024.0).toInt())
                }
            } catch (e: Throwable) {
                Log.w(TAG, "probe ${t.name}", e)
            }
        }
        val win = best ?: Pick(byPing.first().first, byPing.first().second)
        if (mgr.getTunnels().firstOrNull { it.state == Tunnel.State.UP }?.name != win.tunnel.name) {
            mgr.setTunnelState(win.tunnel, Tunnel.State.UP)
        }
        if (bestBps < 0 && previous != null && previous.name != win.tunnel.name) {
            try {
                mgr.setTunnelState(previous, Tunnel.State.UP)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "winner ${win.tunnel.name} ~${bestBps.toInt()} B/s")
        return win
    }

    private fun tcpMs(host: String, port: Int): Long {
        var best = -1L
        for (p in listOf(port, 443).distinct()) {
            val t0 = System.currentTimeMillis()
            try {
                Socket().use { s ->
                    s.soTimeout = 1500
                    s.connect(InetSocketAddress(host, p), 1500)
                }
                val dt = System.currentTimeMillis() - t0
                if (best < 0 || dt < best) best = dt
            } catch (_: Throwable) {
            }
        }
        return best
    }

    private fun downloadBps(): Double {
        val t0 = System.nanoTime()
        var n = 0L
        return try {
            val c = URL(PROBE).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "AetherWG-speed")
            c.inputStream.use { inp ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r <= 0) break
                    n += r
                    if (n > 600_000) break
                }
            }
            c.disconnect()
            val sec = (System.nanoTime() - t0) / 1_000_000_000.0
            if (sec <= 0 || n < 8_000) -1.0 else n / sec
        } catch (e: Throwable) {
            Log.w(TAG, "download", e)
            -1.0
        }
    }
}
