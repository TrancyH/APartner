package com.amoolink.companion.mta

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Build
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WifiP2pManager 挂起封装(照搬 CatShare P2pUtils)+ 双端残留组清理(上游已知坑:
 * 不清理则下一次连接失败)。
 */
object MtaP2p {
    private const val TAG = "MtaP2p"

    /**
     * 本机 P2P 设备地址(= 发起 P2P 时的 p2p0 MAC)。
     * p2p0 的硬件地址对普通应用不可读(NetworkInterface 返回 02:00:00:00:00:00,
     * /sys/class/net/p2p0/address 被 SELinux 拒),而 WifiP2pManager 能直接给出这个
     * 系统视角的地址——互传联盟把它当 GO 设备地址做关联校验,给错值对端会以
     * "接收端 P2P 连接超时"放弃。
     */
    @SuppressLint("MissingPermission")
    suspend fun localDeviceAddress(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = 1_500,
    ): String? {
        // requestDeviceInfo 是 API 29 才加的:minSdk 26 上调用会 NoSuchMethodError,
        // 26~28 直接返回 null,交给「接口地址 → 组主地址」那两条退路。
        if (Build.VERSION.SDK_INT < 29) return null
        val future = CompletableDeferred<String?>()
        try {
            manager.requestDeviceInfo(channel) { device ->
                future.complete(device?.deviceAddress)
            }
        } catch (e: Throwable) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) { future.await() }
            ?.let { MtaState.cleanMac(it) }
            ?.takeIf { it != MtaConst.PLACEHOLDER_MAC }
    }

    /**
     * 组建好之后解析本机 p2p0 地址:先问 requestDeviceInfo,再退到「组主设备地址」
     * (自己当 GO 时组主就是本机)。都拿不到返回 null,由调用方决定是否用占位符。
     */
    @SuppressLint("MissingPermission")
    suspend fun resolveLocalMac(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = 5_000,
    ): String? {
        // ① 最可靠:从 p2p0 的 IPv6 link-local 反推(MAC randomization 下每次组都变)。
        //    刚 createGroup 完地址可能还没配好,所以要跟着轮询反复试。
        // ② 退路:问系统(多数 ROM 会把这字段抹成 02:00:00:00:00:00)
        val deadline = System.currentTimeMillis() + timeoutMs
        var first = true
        while (true) {
            MtaState.readP2p0MacNow(verbose = first)?.let { return it }
            first = false
            localDeviceAddress(manager, channel, timeoutMs = 1_200)?.let { return it }

            val group = groupInfoOrNull(manager, channel)
            if (group != null && group.isGroupOwner) {
                @Suppress("DEPRECATION")
                val raw = group.owner?.deviceAddress
                val cleaned = MtaState.cleanMac(raw)
                Log.i(TAG, "groupInfo owner=$raw -> $cleaned")
                if (cleaned != null && cleaned != MtaConst.PLACEHOLDER_MAC) return cleaned
            }
            if (System.currentTimeMillis() >= deadline) return null
            kotlinx.coroutines.delay(300)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun groupInfoOrNull(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = 1_500,
    ): WifiP2pGroup? {
        val future = CompletableDeferred<WifiP2pGroup?>()
        try {
            manager.requestGroupInfo(channel) { future.complete(it) }
        } catch (e: Throwable) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) { future.await() }
    }

    /**
     * 读当前 P2P 组的工作频率(2412 / 5220 …)。
     * 组刚建好时 requestGroupInfo 可能还回 null,所以要轮询。
     */
    @SuppressLint("MissingPermission")
    suspend fun groupFrequency(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = 4_000,
    ): Int? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val group = groupInfoOrNull(manager, channel)
            val f = group?.frequency ?: 0
            if (f > 0) return f
            if (System.currentTimeMillis() >= deadline) return null
            kotlinx.coroutines.delay(250)
        }
    }

    /**
     * 从组信息里读回系统生成的 SSID / PSK。
     * 不自己指定 networkName/passphrase 建组时,系统的组参数只能用这种方式拿回来;
     * 也可能正是这种建组方式才会带上 P2P 的「IP 地址分配」能力位(group_capab 0x80)。
     */
    @SuppressLint("MissingPermission")
    suspend fun groupCredentials(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long = 5_000,
    ): Pair<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val g = groupInfoOrNull(manager, channel)
            val name = g?.networkName
            val pass = g?.passphrase
            if (!name.isNullOrEmpty() && !pass.isNullOrEmpty()) return name to pass
            if (System.currentTimeMillis() >= deadline) return null
            kotlinx.coroutines.delay(250)
        }
    }

    /** 注意:回调不来时会一直挂住,统一走带超时的 groupInfoOrNull。 */
    @SuppressLint("MissingPermission")
    suspend fun groupInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        groupInfoOrNull(manager, channel, timeoutMs = 3_000)

    @SuppressLint("MissingPermission")
    suspend fun removeGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        val listener = Listener()
        manager.removeGroup(channel, listener)
        listener.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun clearResidualGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (groupInfo(manager, channel) != null) removeGroup(manager, channel)
    }

    @SuppressLint("MissingPermission")
    suspend fun createGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            throw RuntimeException("互传联盟传输需要 Android 10+")
        }
        val listener = Listener()
        manager.createGroup(channel, config, listener)
        listener.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig) {
        val listener = Listener()
        manager.connect(channel, config, listener)
        listener.await()
    }

    @SuppressLint("MissingPermission")
    suspend fun cancelConnect(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        manager.cancelConnect(channel, null)
    }

    private class Listener : WifiP2pManager.ActionListener {
        val deferred = CompletableDeferred<Unit>()

        suspend fun await() = deferred.await()

        override fun onSuccess() {
            deferred.complete(Unit)
        }

        override fun onFailure(reason: Int) {
            val message = when (reason) {
                WifiP2pManager.ERROR -> "ERROR"
                WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                WifiP2pManager.BUSY -> "BUSY"
                else -> "code $reason"
            }
            deferred.completeExceptionally(RuntimeException("WiFi P2P 操作失败: $message"))
        }
    }
}
