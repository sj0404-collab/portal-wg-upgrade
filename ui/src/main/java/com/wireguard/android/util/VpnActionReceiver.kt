package com.wireguard.android.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.launch

class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        applicationScope.launch {
            try {
                when (a) {
                    ACTION_PAUSE -> {
                        UserKnobs.setUserPaused(true)
                        Application.getTunnelManager().getTunnels()
                            .filter { it.state == Tunnel.State.UP }
                            .forEach { it.setStateAsync(Tunnel.State.DOWN) }
                    }
                    ACTION_FAST -> FastestExisting.pickAndUp()
                    ACTION_RESUME -> {
                        UserKnobs.setUserPaused(false)
                        Application.getTunnelManager().restoreState(true)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.aetherwg.client.action.PAUSE"
        const val ACTION_FAST = "app.aetherwg.client.action.FAST"
        const val ACTION_RESUME = "app.aetherwg.client.action.RESUME"
    }
}
