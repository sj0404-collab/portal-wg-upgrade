package com.wireguard.android.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object DeviceConfigScanner {
    private const val TAG = "AetherWG/Scan"
    private val nameRe = Regex(""".*\.(conf|zip|txt)$""", RegexOption.IGNORE_CASE)
    private val ipRe = Regex("""\b(\d{1,3}\.){3}\d{1,3}\b""")

    data class Result(val imported: Int, val vpsHint: String?)

    suspend fun scanAndImport(context: Context): Result {
        val seen = HashSet<String>()
        var imported = 0
        var vpsHint: String? = null
        for (file in discover(context)) {
            val key = file.absolutePath + ":" + file.length()
            if (!seen.add(key)) continue
            val name = file.name.lowercase()
            val bytes = try {
                file.readBytes()
            } catch (_: Throwable) {
                continue
            }
            if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) continue
            val text = runCatching { String(bytes) }.getOrNull().orEmpty()
            if (name == "vps.txt" || name == "vps.host" || name == "endpoint.txt") {
                ipRe.find(text)?.value?.let { vpsHint = it }
                continue
            }
            if (!name.endsWith(".conf") && !name.endsWith(".zip") && !text.contains("[Interface]")) continue
            try {
                imported += TunnelImporter.importBytes(file.name, bytes).size
            } catch (e: Throwable) {
                Log.w(TAG, "skip ${file.name}", e)
            }
        }
        if (!vpsHint.isNullOrBlank()) UserKnobs.setVpsHost(vpsHint)
        return Result(imported, vpsHint)
    }

    private fun discover(context: Context): List<File> {
        val out = ArrayList<File>()
        val roots = ArrayList<File>()
        context.getExternalFilesDir(null)?.let { roots.add(it) }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { roots.add(it) }
        try {
            if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
                roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                roots.add(File(Environment.getExternalStorageDirectory(), "Telegram/Telegram Documents"))
                roots.add(File(Environment.getExternalStorageDirectory(), "Download"))
            }
        } catch (_: Throwable) {
        }
        for (root in roots) walk(root, out, 0)
        queryMediaStore(context, out)
        return out
    }

    private fun walk(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > 4 || !dir.isDirectory) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (nameRe.matches(f.name)) out.add(f)
        }
    }

    private fun queryMediaStore(context: Context, out: MutableList<File>) {
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, proj, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
                val di = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val ni = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                var n = 0
                while (c.moveToNext() && n < 400) {
                    val name = if (ni >= 0) c.getString(ni) else null
                    val path = if (di >= 0) c.getString(di) else null
                    if (name != null && nameRe.matches(name) && !path.isNullOrBlank()) {
                        out.add(File(path))
                        n++
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mediastore", e)
        }
    }
}
