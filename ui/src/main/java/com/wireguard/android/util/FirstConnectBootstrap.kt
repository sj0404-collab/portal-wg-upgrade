package com.wireguard.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
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
        val already = UserKnobs.presetsReady.first()
        var presets = 0
        if (!already) {
            presets = try {
                PresetFactory.generateAll()
            } catch (e: Throwable) {
                Log.e(TAG, "presets", e)
                0
            }
            if (presets > 0) UserKnobs.setPresetsReady(true)
        }
        "файлы: ${scan.imported}, VPS: ${ip ?: scan.vpsHint ?: "нет"}, пресеты: $presets"
    }
}
