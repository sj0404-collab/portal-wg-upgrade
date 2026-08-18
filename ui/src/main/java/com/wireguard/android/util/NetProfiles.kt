package com.wireguard.android.util

import com.wireguard.android.Application
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object NetProfiles {
    val catalog = linkedMapOf(
        "keep" to null,
        "cloudflare" to "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001",
        "google" to "8.8.8.8, 8.8.4.4, 2001:4860:4860::8888, 2001:4860:4860::8844",
        "quad9" to "9.9.9.9, 149.112.112.112, 2620:fe::fe, 2620:fe::9",
        "opendns" to "208.67.222.222, 208.67.220.220, 2620:119:35::35, 2620:119:53::53"
    )

    suspend fun dnsLine(): String? {
        val id = UserKnobs.dnsProfile.first()?.trim().orEmpty().ifEmpty { "keep" }
        if (id == "keep") return null
        if (id == "custom") {
            val c = UserKnobs.dnsCustom.first()?.trim().orEmpty()
            return c.ifEmpty { null }
        }
        return catalog[id]
    }

    suspend fun withDns(cfg: Config): Config {
        val line = dnsLine() ?: return cfg
        val old = cfg.`interface`
        val iface = Interface.Builder()
            .setKeyPair(old.keyPair)
            .addAddresses(old.addresses)
            .parseDnsServers(line)
            .excludeApplications(old.excludedApplications)
            .includeApplications(old.includedApplications)
            .apply {
                old.listenPort.ifPresent { setListenPort(it) }
                val m = old.mtu.orElse(0)
                setMtu(if (m <= 1280) 1420 else m)
            }
            .build()
        return Config.Builder().setInterface(iface).addPeers(cfg.peers).build()
    }

    suspend fun applyToStoredTunnels(): Int {
        val mgr = Application.getTunnelManager()
        var n = 0
        for (t in mgr.getTunnels()) {
            if (t.name in setOf("full", "split", "dns", "AetherWG")) continue
            try {
                val next = withDns(t.getConfigAsync())
                mgr.setTunnelConfig(t, next)
                n++
            } catch (_: Throwable) {
            }
        }
        return n
    }

    suspend fun openHttp(url: String): HttpURLConnection {
        val socks = UserKnobs.proxySocks.first()?.trim().orEmpty()
        val http = UserKnobs.proxyHttp.first()?.trim().orEmpty()
        val u = URL(url)
        val conn = when {
            socks.isNotEmpty() -> u.openConnection(parseProxy(socks, Proxy.Type.SOCKS))
            http.isNotEmpty() -> u.openConnection(parseProxy(http, Proxy.Type.HTTP))
            else -> u.openConnection()
        }
        return conn as HttpURLConnection
    }

    private fun parseProxy(raw: String, type: Proxy.Type): Proxy {
        val s = raw.removePrefix("socks5://").removePrefix("socks://").removePrefix("http://").removePrefix("https://")
        val host = s.substringBeforeLast(':').trim().ifEmpty { "127.0.0.1" }
        val port = s.substringAfterLast(':', "1080").toIntOrNull() ?: 1080
        return Proxy(type, InetSocketAddress(host, port))
    }
}
