/* PROBE_MARKER_LINE_1 */
package com.amoolink.companion.mta

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.CompletableDeferred

/**
 * WifiP2pManager 挂起封装(照搬 CatShare P2pUtils)+ 双端残留组清理(上游已知坑:
 * 不清理则下一次连接失败)。
 */
val __probe_line = 2

object MtaP2p {

    @SuppressLint("MissingPermission")
    suspend fun groupInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? {
        val future = CompletableDeferred<WifiP2pGroup?>()
        manager.requestGroupInfo(channel) { future.complete(it) }
        return future.await()
    }

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
