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

    /**
     * 直接从 p2p0 的 IPv6 链路本地地址(EUI-64)反推 MAC。
     *
     * 为什么必须这样:Android 13+ 的「P2P MAC randomization」让 p2p0 的硬件地址
     * **每建一次组就换一个**,而系统对普通应用把 deviceAddress 抹成 02:00:00:00:00:00、
     * sysfs 又被 SELinux 拒。唯一还accessible 的是接口的 IPv6 link-local:
     *   fe80::cc33:c1ff:fe11:fffe  →  内嵌 EUI-64  cc 33 c1 ff fe 11 ff fe
     *   → MAC = (cc^0x02):33:c1:11:ff:fe = ce:33:c1:11:ff:fe
     * 给它错的地址,对端(小米/一加)在 P2P 搜索里匹配不上,就不会入组。
     */
    fun readP2p0MacNow(verbose: Boolean = false): String? {
        return try {
            val names = mutableListOf<String>()
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                names.add(ni.name)
                if (ni.name != "p2p0") continue
                for (addr in ni.inetAddresses) {
                    if (addr !is java.net.Inet6Address || !addr.isLinkLocalAddress) continue
                    val b = addr.address
                    if (b.size != 16) continue
                    // EUI-64 的插入标记 0xFF 0xFE 必须出现在 b[11..12]
                    if (b[11] != 0xff.toByte() || b[12] != 0xfe.toByte()) continue
                    val mac = byteArrayOf(
                        (b[8].toInt() xor 0x02).toByte(), b[9], b[10], b[13], b[14], b[15],
                    )
                    return mac.joinToString(":") { String.format("%02x", it) }
                }
            }
            if (verbose) android.util.Log.w("MtaState", "p2p0 不在接口列表里: $names")
            null
        } catch (e: Exception) {
            if (verbose) android.util.Log.w("MtaState", "读接口失败: ${e.message}")
            null
        }
    }

    /**
     * 本会话由 WifiP2pManager.requestDeviceInfo 读到的 p2p0 地址。
     * p2p0 的 MAC 每个会话可能重生成,发起传输前现读一次最准(CatShare README 亦提示
     * "待机与发起传输时 MAC 可能不同")。
     */
    @Volatile
    private var sessionMac: String? = null

    fun rememberSessionMac(mac: String?) {
        val cleaned = cleanMac(mac) ?: return
        if (cleaned != MtaConst.PLACEHOLDER_MAC) sessionMac = cleaned
    }

    fun mac(context: Context): String {
        // 优先级:**本次会话现读** > 手动/桌面注入 > NetworkInterface > 占位符。
        // p2p0 的 MAC 每次建组都会变(P2P MAC randomization),所以从接口反推出来的
        // 现读值永远比历史注入值可信——注入值一旦过期,对端就永远匹配不上 GO。
        sessionMac?.let { return it }
        prefs(context).getString("mac", null)?.let { return it }
        try {
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni.name == "p2p0") {
                    val hw = ni.hardwareAddress
                    if (hw != null && hw.size == 6) {
                        return hw.joinToString(":") { String.format("%02x", it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return MtaConst.PLACEHOLDER_MAC
    }

    /** @return 是否为新写入(值未变化返回 false)。 */
    fun setMac(context: Context, mac: String?): Boolean {
        val cleaned = cleanMac(mac)
        val old = mac(context)
        prefs(context).edit().putString("mac", cleaned).apply()
        return cleaned != null && old != cleaned
    }

    fun isMacInjected(context: Context): Boolean = prefs(context).getString("mac", null) != null

    /** 真实 MAC 未取到(= 占位符)时,发起传输一定失败,UI 要显式提示。 */
    fun isMacPlaceholder(context: Context): Boolean = mac(context) == MtaConst.PLACEHOLDER_MAC

    /** 规整为 aa:bb:cc:dd:ee:ff;不合法返回 null。 */
    fun cleanMac(mac: String?): String? =
        mac?.trim()?.lowercase()?.takeIf { it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
