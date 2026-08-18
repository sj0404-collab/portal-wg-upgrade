package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import java.net.InetSocketAddress
import java.net.Socket

/** Picks the lowest-latency tunnel among configs already imported. Does not invent servers. */
object FastestExisting {
    private const val TAG = "AetherWG/Fast"
    private val junk = setOf("full", "split", "dns", "AetherWG")

    data class Pick(val tunnel: ObservableTunnel, val ms: Long)

    suspend fun pickAndUp(): Pick? {
        val mgr = Application.getTunnelManager()
        val tunnels = mgr.getTunnels().filter { it.name !in junk }
        if (tunnels.isEmpty()) return null
        val ranked = tunnels.mapNotNull { t ->
            val cfg = try {
                t.getConfigAsync()
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            val ep = cfg.peers.firstOrNull()?.endpoint?.orElse(null) ?: return@mapNotNull null
            val host = ep.host
            if (host.isBlank() || host == "127.0.0.1") return@mapNotNull null
            val ms = probe(host, ep.port)
            if (ms < 0) null else Pick(t, ms)
        }.sortedBy { it.ms }
        val best = ranked.firstOrNull() ?: return null
        UserKnobs.setUserPaused(false)
        mgr.setTunnelState(best.tunnel, Tunnel.State.UP)
        Log.i(TAG, "up ${best.tunnel.name} ${best.ms}ms")
        return best
    }

    private fun probe(host: String, port: Int): Long {
        val ports = listOf(port, 443).distinct()
        var best = -1L
        for (p in ports) {
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
}
