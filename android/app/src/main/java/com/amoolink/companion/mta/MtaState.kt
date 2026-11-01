package com.amoolink.companion.mta

import android.content.Context
import android.content.SharedPreferences

/**
 * MTA 运行时状态:p2p0 MAC(prefs 持久;桌面 adb 注入/手动填入)、收发互斥 busy 锁。
 */
object MtaState {
    const val PREFS = "mta_state"

    /** APartner 是否在前台(MaintenanceActivity onResume/onPause 维护)。 */
    @Volatile
    var appInForeground: Boolean = false

    @Volatile
    var busy: Boolean = false
        private set

    /** 全局收发互斥(对齐上游 MyApplication.setBusy);@return false = 忙。 */
    fun tryLock(): Boolean = synchronized(this) {
        if (busy) false else {
            busy = true
            true
        }
    }

    fun unlock() = synchronized(this) { busy = false }

    fun mac(context: Context): String {
        val injected = prefs(context).getString("mac", null)
        return injected ?: MtaConst.PLACEHOLDER_MAC
    }

    /** @return 是否为新写入(值未变化返回 false)。 */
    fun setMac(context: Context, mac: String?): Boolean {
        val cleaned = mac?.trim()?.lowercase()?.takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) }
        val old = mac(context)
        prefs(context).edit().putString("mac", cleaned).apply()
        return cleaned != null && old != cleaned
    }

    fun isMacInjected(context: Context): Boolean = prefs(context).getString("mac", null) != null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
