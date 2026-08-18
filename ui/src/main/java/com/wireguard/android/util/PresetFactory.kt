package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.config.Config
import com.wireguard.config.Peer

/** Copies keys+peer from a working tunnel; only routing/DNS change. New keys never handshake. */
object PresetFactory {
    private const val TAG = "AetherWG/Preset"

    data class Preset(
        val id: String,
        val title: String,
        val dns: String,
        val allowedIps: String,
        val keepalive: Int
    )

    val all = listOf(
        Preset("full", "Полный туннель", "1.1.1.1, 1.0.0.1", "0.0.0.0/0, ::/0", 25),
        Preset("split", "Только локальные сети", "1.1.1.1", "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16", 25),
        Preset("dns", "Только DNS", "1.1.1.1, 1.0.0.1", "1.1.1.1/32, 1.0.0.1/32", 25)
    )

    suspend fun generateAll(): Int {
        var n = 0
        for (p in all) {
            try {
                generateOne(p)
                n++
            } catch (e: Throwable) {
                Log.e(TAG, "preset ${p.id}", e)
            }
        }
        return n
    }

    suspend fun generateOne(p: Preset) {
        val mgr = Application.getTunnelManager()
        val existing = mgr.getTunnels()
        val skip = all.map { it.id }.toSet() + setOf("AetherWG")
        val donorTun = existing.firstOrNull { it.name !in skip }
            ?: existing.firstOrNull { it.name != p.id }
            ?: throw IllegalStateException("нет рабочего туннеля-донора")
        val donor = donorTun.getConfigAsync()
        val donorPeer = donor.peers.firstOrNull() ?: throw IllegalStateException("у донора нет пира")
        val iface = com.wireguard.config.Interface.Builder()
            .parsePrivateKey(donor.`interface`.keyPair.privateKey.toBase64())
            .addAddresses(donor.`interface`.addresses)
            .parseDnsServers(p.dns)
            .apply {
                donor.`interface`.mtu.ifPresent { parseMtu(it.toString()) }
            }
            .build()
        val peerB = Peer.Builder()
            .setPublicKey(donorPeer.publicKey)
            .parseAllowedIPs(p.allowedIps)
            .setPersistentKeepalive(p.keepalive)
        donorPeer.preSharedKey.ifPresent { peerB.setPreSharedKey(it) }
        donorPeer.endpoint.ifPresent { peerB.setEndpoint(it) }
        val cfg = Config.Builder().setInterface(iface).addPeer(peerB.build()).build()
        if (existing.containsKey(p.id)) mgr.setTunnelConfig(existing[p.id]!!, cfg)
        else mgr.create(p.id, cfg)
        Log.i(TAG, "preset ${p.id} cloned from ${donorTun.name}")
    }
}
