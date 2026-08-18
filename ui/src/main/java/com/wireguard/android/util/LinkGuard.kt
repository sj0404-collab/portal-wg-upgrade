package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.wireguard.android.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Network loss = pause. Do not bring the tunnel DOWN or create a new one.
 * When the radio returns, resume the same saved tunnel if the process dropped it.
 */
class LinkGuard(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var bumpJob: Job? = null

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available — resume same tunnel if needed")
                scheduleResume()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) scheduleResume()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network lost — pause, keep VpnService and same peer")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
            else cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
        }
    }

    private fun scheduleResume() {
        bumpJob?.cancel()
        bumpJob = Application.getCoroutineScope().launch {
            delay(2500)
            try {
                val running = Application.getBackend().runningTunnelNames
                if (running.isNotEmpty()) {
                    Log.i(TAG, "already up $running — do not recreate")
                    return@launch
                }
                Application.getTunnelManager().restoreState(true)
            } catch (e: Exception) {
                Log.e(TAG, "resume failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/LinkGuard"
    }
}
