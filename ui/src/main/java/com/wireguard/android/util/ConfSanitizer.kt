package com.wireguard.android.util

import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

object ConfSanitizer {
    private val ifaceKeys = setOf(
        "privatekey", "address", "dns", "mtu", "listenport", "fwmark",
        "table", "preup", "postup", "predown", "postdown", "saveconfig"
    )
    private val peerKeys = setOf(
        "publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive"
    )

    fun strip(raw: String): String {
        val out = StringBuilder()
        var section = ""
        for (orig in raw.replace("\r\n", "\n").split('\n')) {
            var line = orig
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash)
            line = line.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                if (section == "[interface]" || section == "[peer]") {
                    out.append(line).append('\n')
                } else {
                    section = "[skip]"
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val allowed = when (section) {
                "[interface]" -> key in ifaceKeys
                "[peer]" -> key in peerKeys
                else -> false
            }
            if (allowed) out.append(line).append('\n')
        }
        return out.toString()
    }

    fun parse(raw: String): Config =
        Config.parse(ByteArrayInputStream(strip(raw).toByteArray(StandardCharsets.UTF_8)))
}
