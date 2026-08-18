package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** If the same tunnel is UP but handshake is dead, bounce THAT config only. */
object HandshakeWatchdog {
    private const val TAG = "AetherWG/Watch"
    private var fails = 0

    suspend fun loop() {
        while (true) {
            delay(20_000)
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
        val keys = st.peers()
        val hs = if (keys.isNotEmpty()) st.peer(keys[0])?.latestHandshakeEpochMillis ?: 0L else 0L
        val age = if (hs > 0L) System.currentTimeMillis() - hs else Long.MAX_VALUE
        if (age < 75_000L) {
            fails = 0
            return
        }
        if (fails >= 4) {
            delay(180_000)
            fails = 2
            return
        }
        fails++
        Log.i(TAG, "stale handshake ${up.name} age=$age bounce $fails")
        try {
            mgr.setTunnelState(up, Tunnel.State.DOWN)
            delay(600)
            if (!UserKnobs.userPaused.first()) mgr.setTunnelState(up, Tunnel.State.UP)
        } catch (e: Throwable) {
            Log.e(TAG, "bounce", e)
        }
    }
}
