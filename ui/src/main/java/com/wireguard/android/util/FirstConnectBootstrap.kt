package com.wireguard.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FirstConnectBootstrap {
    private const val TAG = "AetherWG/Boot"
    private val lock = Mutex()

    suspend fun run(context: Context): String = lock.withLock {
        val scan = try {
            DeviceConfigScanner.scanAndImport(context)
        } catch (e: Throwable) {
            Log.e(TAG, "scan", e)
            DeviceConfigScanner.Result(0, null)
        }
        val ip = try {
            VpsEndpoint.refresh()
        } catch (e: Throwable) {
            Log.e(TAG, "vps", e)
            null
        }
        "файлы: ${scan.imported}, VPS: ${ip ?: scan.vpsHint ?: "нет"}"
    }
}
