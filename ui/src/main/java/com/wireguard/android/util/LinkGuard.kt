/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps UP tunnels UP across radio loss / Wi-Fi↔mobile switches.
 * Does not tear the VpnService down on transient disconnects.
 */
class LinkGuard(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "network available, restore tunnels")
                bump()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) bump()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "network lost — leave VPN process running, wait for next network")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(cb)
            else cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
        }
    }

    private fun bump() {
        Application.getCoroutineScope().launch {
            delay(800)
            try {
                Application.getTunnelManager().restoreState(true)
            } catch (e: Exception) {
                Log.e(TAG, "restore failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/LinkGuard"
    }
}
