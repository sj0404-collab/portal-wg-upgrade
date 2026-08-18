package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

object HandshakeWatchdog {
    private const val TAG = "AetherWG/Watch"
    private var lastBytes = -1L
    private var staleTicks = 0

    suspend fun loop() {
        while (true) {
            delay(25_000)
            try {
                tick()
            } catch (e: Throwable) {
                Log.w(TAG, "tick", e)
            }
        }
    }

    private suspend fun tick() {
        if (UserKnobs.userPaused.first()) return
        val mgr = Application.getTunnelManager()
        val up = mgr.getTunnels().firstOrNull { it.state == Tunnel.State.UP } ?: return
        val st = try {
            mgr.getTunnelStatistics(up)
        } catch (_: Throwable) {
            return
        }
        val bytes = st.totalRx() + st.totalTx()
        if (lastBytes >= 0 && bytes > lastBytes + 2048) {
            lastBytes = bytes
            staleTicks = 0
            return
        }
        lastBytes = bytes
        val keys = st.peers()
        val hs = if (keys.isNotEmpty()) st.peer(keys[0])?.latestHandshakeEpochMillis ?: 0L else 0L
        val age = if (hs > 0L) System.currentTimeMillis() - hs else Long.MAX_VALUE
        if (age < 120_000L || bytes > 64_000) {
            staleTicks = 0
            return
        }
        staleTicks++
        if (staleTicks < 3) return
        staleTicks = 0
        Log.i(TAG, "no traffic ${up.name} age=$age bytes=$bytes — same tunnel bounce")
        try {
            mgr.setTunnelState(up, Tunnel.State.DOWN)
            delay(800)
            if (!UserKnobs.userPaused.first()) mgr.setTunnelState(up, Tunnel.State.UP)
        } catch (e: Throwable) {
            Log.e(TAG, "bounce", e)
        }
    }
}
