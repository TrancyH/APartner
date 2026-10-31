package com.amoolink.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** M5-1:开机自启恢复前台服务(遵循应用内「开机自启」开关,默认开)。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        context.startForegroundService(Intent(context, ConnectionService::class.java))
    }
}
