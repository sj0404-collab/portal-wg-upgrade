/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.util.Log
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.BR
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.Keyed
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.applicationScope
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates the volatile and nonvolatile state of a WireGuard tunnel.
 */
class ObservableTunnel internal constructor(
    private val manager: TunnelManager,
    private var name: String,
    config: Config?,
    state: Tunnel.State
) : BaseObservable(), Keyed<String>, Tunnel {
    override val key
        get() = name

    @Bindable
    override fun getName() = name

    suspend fun setNameAsync(name: String): String = withContext(Dispatchers.Main.immediate) {
        if (name != this@ObservableTunnel.name)
            manager.setTunnelName(this@ObservableTunnel, name)
        else
            this@ObservableTunnel.name
    }

    fun onNameChanged(name: String): String {
        this.name = name
        notifyPropertyChanged(BR.name)
        return name
    }


    @get:Bindable
    var state = state
        private set

    private var upSince: Long = if (state == Tunnel.State.UP) System.currentTimeMillis() else 0L

    @get:Bindable
    val localStatus: String
        get() {
            if (state != Tunnel.State.UP) return ""
            val cfg = config
            val peer = cfg?.peers?.firstOrNull()
            val pkRaw = peer?.publicKey?.toBase64().orEmpty()
            val pk = if (pkRaw.length > 12) pkRaw.take(5) + "..." + pkRaw.takeLast(5) else pkRaw.ifEmpty { "—" }
            val ep = peer?.endpoint?.map { it.toString() }?.orElse("—") ?: "—"
            val st = statistics
            val rx = QuantityFormatter.formatBytes(st?.totalRx() ?: 0L)
            val tx = QuantityFormatter.formatBytes(st?.totalTx() ?: 0L)
            var ago = "—"
            val keys = st?.peers()
            if (keys != null && keys.isNotEmpty()) {
                val hs = st.peer(keys[0])?.latestHandshakeEpochMillis ?: 0L
                if (hs > 0L) ago = ((System.currentTimeMillis() - hs) / 1000).toString() + " с"
            }
            val work = if (upSince > 0L) formatUptime(System.currentTimeMillis() - upSince) else "—"
            return "Время работы: $work\nпир: $pk\n↓ $rx   ↑ $tx\nпоследнее подключение: $ago\nконечная точка: $ep"
        }

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged(newState)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.UP) {
            onStatisticsChanged(null)
            upSince = 0L
        } else if (this.state != Tunnel.State.UP) {
            upSince = System.currentTimeMillis()
        }
        this.state = state
        notifyPropertyChanged(BR.state)
        notifyPropertyChanged(BR.localStatus)
        return state
    }

    suspend fun setStateAsync(state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        if (state != this@ObservableTunnel.state)
            manager.setTunnelState(this@ObservableTunnel, state)
        else
            this@ObservableTunnel.state
    }


    @get:Bindable
    var config = config
        get() {
            if (field == null)
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelConfig(this@ObservableTunnel)
                    } catch (e: Throwable) {
                        Log.e(TAG, Log.getStackTraceString(e))
                    }
                }
            return field
        }
        private set

    suspend fun getConfigAsync(): Config = withContext(Dispatchers.Main.immediate) {
        config ?: manager.getTunnelConfig(this@ObservableTunnel)
    }

    suspend fun setConfigAsync(config: Config): Config = withContext(Dispatchers.Main.immediate) {
        this@ObservableTunnel.config.let {
            if (config != it)
                manager.setTunnelConfig(this@ObservableTunnel, config)
            else
                it
        }
    }

    fun onConfigChanged(config: Config?): Config? {
        this.config = config
        notifyPropertyChanged(BR.config)
        return config
    }


    @get:Bindable
    var statistics: Statistics? = null
        get() {
            if (field == null || field?.isStale != false)
            // Opportunistically fetch this if we don't have a cached one, and rely on data bindings to update it eventually
                applicationScope.launch {
                    try {
                        manager.getTunnelStatistics(this@ObservableTunnel)
                    } catch (e: Throwable) {
                        Log.e(TAG, Log.getStackTraceString(e))
                    }
                }
            return field
        }
        private set

    suspend fun getStatisticsAsync(): Statistics = withContext(Dispatchers.Main.immediate) {
        statistics.let {
            if (it == null || it.isStale)
                manager.getTunnelStatistics(this@ObservableTunnel)
            else
                it
        }
    }

    fun onStatisticsChanged(statistics: Statistics?): Statistics? {
        this.statistics = statistics
        notifyPropertyChanged(BR.statistics)
        notifyPropertyChanged(BR.localStatus)
        return statistics
    }

    fun tickStatus() {
        if (state == Tunnel.State.UP) notifyPropertyChanged(BR.localStatus)
    }


    private fun formatUptime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d ч %02d мин %02d с", h, m, sec)
        else if (m > 0) String.format("%d мин %02d с", m, sec)
        else String.format("%d с", sec)
    }

    suspend fun deleteAsync() = manager.delete(this)


    companion object {
        private const val TAG = "WireGuard/ObservableTunnel"
    }
}
