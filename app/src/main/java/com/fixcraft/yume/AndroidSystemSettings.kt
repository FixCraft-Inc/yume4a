package com.fixcraft.yume

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object AndroidSystemSettings {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun openBatteryOptimizationControls(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intents = buildList {
            if (BuildConfig.ALLOW_DIRECT_BATTERY_OPTIMIZATION_REQUEST &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !isIgnoringBatteryOptimizations(context)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        return launchFirst(context, intents)
    }

    fun openAppDetails(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        return launchFirst(
            context,
            listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)),
        )
    }

    private fun launchFirst(
        context: Context,
        intents: List<Intent>,
    ): Boolean {
        intents.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val canHandle = intent.resolveActivity(context.packageManager) != null
            if (!canHandle) {
                return@forEach
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) {
                return true
            }
        }
        return false
    }
}
