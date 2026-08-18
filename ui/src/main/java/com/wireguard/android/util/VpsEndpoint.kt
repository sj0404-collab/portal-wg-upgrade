package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object VpsEndpoint {
    private const val TAG = "AetherWG/Vps"

    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val host = UserKnobs.vpsHost.first()?.trim().orEmpty()
        val ip = when {
            host.isEmpty() -> null
            host.startsWith("http://") || host.startsWith("https://") -> fetchUrl(host)
            else -> resolveHost(host)
        }
        if (ip.isNullOrBlank()) return@withContext null
        applyToPlaceholderTunnels(ip)
        ip
    }

    suspend fun rememberFromConfig(cfg: Config) {
        val ep = cfg.peers.firstOrNull()?.endpoint?.orElse(null) ?: return
        val h = ep.host
        if (h.isBlank() || h == "127.0.0.1" || h == "0.0.0.0" || h.endsWith(".arpa")) return
        val cur = UserKnobs.vpsHost.first()?.trim().orEmpty()
        if (cur.isEmpty()) UserKnobs.setVpsHost(h)
    }

    private fun fetchUrl(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "AetherWG")
        c.inputStream.bufferedReader().use { it.readText() }
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
    } catch (e: Throwable) {
        Log.w(TAG, "url fetch failed", e)
        null
    }

    private fun resolveHost(host: String): String? = try {
        InetAddress.getAllByName(host).firstOrNull { it.hostAddress?.contains(':') != true }?.hostAddress
            ?: InetAddress.getByName(host).hostAddress
    } catch (e: Throwable) {
        Log.w(TAG, "dns failed for $host", e)
        null
    }

    private suspend fun applyToPlaceholderTunnels(ip: String) {
        val mgr = Application.getTunnelManager()
        val tunnels = mgr.getTunnels()
        for (t in tunnels) {
            val cfg = try {
                t.getConfigAsync()
            } catch (_: Throwable) {
                continue
            }
            val peer = cfg.peers.firstOrNull() ?: continue
            val ep = peer.endpoint.orElse(null) ?: continue
            if (ep.host != "127.0.0.1" && ep.host != "0.0.0.0" && ep.host != "YOUR_VPS_IP") continue
            try {
                val rebuiltPeer = Peer.Builder()
                    .setPublicKey(peer.publicKey)
                    .addAllowedIps(peer.allowedIps)
                    .setEndpoint(InetEndpoint.parse("$ip:${ep.port}"))
                    .apply {
                        peer.preSharedKey.ifPresent { setPreSharedKey(it) }
                        peer.persistentKeepalive.ifPresent { setPersistentKeepalive(it) }
                    }
                    .build()
                val next = Config.Builder()
                    .setInterface(cfg.`interface`)
                    .addPeer(rebuiltPeer)
                    .apply { cfg.peers.drop(1).forEach { addPeer(it) } }
                    .build()
                mgr.setTunnelConfig(t, next)
            } catch (e: Throwable) {
                Log.e(TAG, "patch failed ${t.name}", e)
            }
        }
    }
}
