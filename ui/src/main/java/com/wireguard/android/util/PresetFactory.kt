package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.Application
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.flow.first

object PresetFactory {
    private const val TAG = "AetherWG/Preset"

    data class Preset(
        val id: String,
        val title: String,
        val address: String,
        val dns: String,
        val allowedIps: String,
        val keepalive: Int,
        val mtu: Int
    )

    val all = listOf(
        Preset("full", "Полный туннель", "10.66.66.2/32", "1.1.1.1, 1.0.0.1", "0.0.0.0/0, ::/0", 25, 1280),
        Preset("split", "Только локальные сети", "10.66.66.2/32", "1.1.1.1", "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16", 25, 1280),
        Preset("dns", "Только DNS", "10.66.66.2/32", "1.1.1.1, 1.0.0.1", "1.1.1.1/32, 1.0.0.1/32", 25, 1280)
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
        val donor = existing.firstOrNull { it.name != p.id }?.getConfigAsync()
            ?: existing.firstOrNull()?.getConfigAsync()
        val donorPeer = donor?.peers?.firstOrNull()
        val host = UserKnobs.vpsHost.first()?.trim().orEmpty()
        val endpoint = when {
            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) -> "$host:51820"
            donorPeer?.endpoint?.isPresent == true &&
                donorPeer.endpoint.get().host !in setOf("127.0.0.1", "0.0.0.0") ->
                donorPeer.endpoint.get().toString()
            else -> "127.0.0.1:51820"
        }
        val kp = KeyPair()
        val iface = Interface.Builder()
            .parsePrivateKey(kp.privateKey.toBase64())
            .parseAddresses(p.address)
            .parseDnsServers(p.dns)
            .parseMtu(p.mtu.toString())
            .build()
        val peerB = Peer.Builder()
        if (donorPeer != null) {
            peerB.setPublicKey(donorPeer.publicKey)
            donorPeer.preSharedKey.ifPresent { peerB.setPreSharedKey(it) }
        } else {
            peerB.parsePublicKey(KeyPair().publicKey.toBase64())
        }
        peerB.parseAllowedIPs(p.allowedIps)
        peerB.parseEndpoint(endpoint)
        peerB.setPersistentKeepalive(p.keepalive)
        val cfg = Config.Builder().setInterface(iface).addPeer(peerB.build()).build()
        if (existing.containsKey(p.id)) {
            mgr.setTunnelConfig(existing[p.id]!!, cfg)
        } else {
            mgr.create(p.id, cfg)
        }
        Log.i(TAG, "preset ${p.id} client pubkey=${kp.publicKey.toBase64()}")
    }
}
