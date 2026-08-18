package com.wireguard.android.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.fragment.app.FragmentManager
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.fragment.ConfigNamingDialogFragment
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object TunnelImporter {
    suspend fun importTunnel(contentResolver: ContentResolver, uri: Uri, messageCallback: (CharSequence) -> Unit) = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        val created = ArrayList<ObservableTunnel>()
        val throwables = ArrayList<Throwable>()
        try {
            var name = ""
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
            }
            if (name.isEmpty()) name = Uri.decode(uri.lastPathSegment ?: "tunnel")
            name = name.substringAfterLast('/')
            val bytes = contentResolver.openInputStream(uri)!!.readBytes()
            val isZip = name.lowercase().endsWith(".zip") || bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            if (isZip) {
                collectZip(bytes, created, throwables)
            } else {
                val base = name.removeSuffix(".conf").removeSuffix(".CONF").ifBlank { "tunnel" }
                try {
                    val cfg = ConfSanitizer.parse(String(bytes, StandardCharsets.UTF_8))
                    VpsEndpoint.rememberFromConfig(cfg)
                    created.add(createUnique(base, cfg))
                } catch (e: Throwable) {
                    throwables.add(e)
                }
            }
            if (created.isEmpty()) {
                if (throwables.size == 1) throw throwables[0]
                require(throwables.isNotEmpty()) { context.getString(R.string.no_configs_error) }
            }
            withContext(Dispatchers.Main.immediate) { onTunnelImportFinished(created, throwables, messageCallback) }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main.immediate) { onTunnelImportFinished(emptyList(), listOf(e), messageCallback) }
        }
    }

    suspend fun importBytes(name: String, bytes: ByteArray): List<ObservableTunnel> {
        val created = ArrayList<ObservableTunnel>()
        val throwables = ArrayList<Throwable>()
        val isZip = name.lowercase().endsWith(".zip") || bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (isZip) collectZip(bytes, created, throwables)
        else {
            try {
                val cfg = ConfSanitizer.parse(String(bytes, StandardCharsets.UTF_8))
                VpsEndpoint.rememberFromConfig(cfg)
                val base = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "tunnel" }
                if (!Application.getTunnelManager().getTunnels().containsKey(base.replace(Regex("[^A-Za-z0-9_+=.-]"), "_")))
                    created.add(createUnique(base, cfg))
            } catch (e: Throwable) {
                throwables.add(e)
                Log.w(TAG, "importBytes $name", e)
            }
        }
        return created
    }

    private suspend fun collectZip(
        bytes: ByteArray,
        created: ArrayList<ObservableTunnel>,
        throwables: ArrayList<Throwable>,
        depth: Int = 0
    ) {
        if (depth > 3) return
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val raw = zip.readBytes()
                val leaf = entry.name.substringAfterLast('/').lowercase()
                if (leaf.endsWith(".zip") || (raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte())) {
                    collectZip(raw, created, throwables, depth + 1)
                    continue
                }
                if (!(leaf.endsWith(".conf") || leaf.endsWith(".txt") || String(raw, Charsets.UTF_8).contains("[Interface]"))) continue
                val base = entry.name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "tunnel" }
                try {
                    val cfg = ConfSanitizer.parse(String(raw, StandardCharsets.UTF_8))
                    VpsEndpoint.rememberFromConfig(cfg)
                    created.add(createUnique(base, cfg))
                } catch (e: Throwable) {
                    throwables.add(e)
                }
            }
        }
    }

    private suspend fun createUnique(wanted: String, cfg: Config): ObservableTunnel {
        val mgr = Application.getTunnelManager()
        var name = wanted.replace(Regex("[^A-Za-z0-9_+=.-]"), "_").ifBlank { "tunnel" }
        var i = 2
        val existing = mgr.getTunnels()
        while (existing.containsKey(name)) {
            name = "$wanted-$i"
            i++
        }
        return mgr.create(name, cfg)
    }

    fun importTunnel(parentFragmentManager: FragmentManager, configText: String, messageCallback: (CharSequence) -> Unit) {
        try {
            ConfSanitizer.parse(configText)
            ConfigNamingDialogFragment.newInstance(configText).show(parentFragmentManager, null)
        } catch (e: Throwable) {
            onTunnelImportFinished(emptyList(), listOf(e), messageCallback)
        }
    }

    private fun onTunnelImportFinished(tunnels: List<ObservableTunnel>, throwables: Collection<Throwable>, messageCallback: (CharSequence) -> Unit) {
        val context = Application.get().applicationContext
        var message = ""
        for (throwable in throwables) {
            val error = ErrorMessages[throwable]
            message = context.getString(R.string.import_error, error)
            Log.e(TAG, message, throwable)
        }
        if (tunnels.size == 1 && throwables.isEmpty())
            message = context.getString(R.string.import_success, tunnels[0].name)
        else if (tunnels.isEmpty() && throwables.size == 1)
            Unit
        else if (throwables.isEmpty())
            message = context.resources.getQuantityString(R.plurals.import_total_success, tunnels.size, tunnels.size)
        else if (throwables.isNotEmpty())
            message = context.resources.getQuantityString(
                R.plurals.import_partial_success,
                tunnels.size + throwables.size,
                tunnels.size, tunnels.size + throwables.size
            )
        messageCallback(message)
    }

    private const val TAG = "WireGuard/TunnelImporter"
}
