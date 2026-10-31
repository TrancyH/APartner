package com.amoolink.companion

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * M5-3:通知监听(实时、含 RemoteInput 回复句柄;docs/04 §2.1 对比 dumpsys 轮询的优势)。
 * 回复 = 组装 RemoteInput 结果回发 action 的 PendingIntent —— 该句柄仅本服务可得。
 */
class CompanionNotificationListener : NotificationListenerService() {

    companion object {
        private const val CACHE_LIMIT = 64

        /** key → SBN(LRU),承接桌面端 ReplyAction。 */
        private val cache = object : LinkedHashMap<String, StatusBarNotification>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StatusBarNotification>?): Boolean =
                size > CACHE_LIMIT
        }

        @Synchronized
        fun cachePut(sbn: StatusBarNotification) {
            cache[sbn.key] = sbn
        }

        @Synchronized
        private fun cacheTake(key: String): StatusBarNotification? = cache[key]

        /** 桌面端快捷回复:填充 RemoteInput 结果并 send() 对应 action。 */
        fun sendReply(context: android.content.Context, key: String, actionIdx: Int, inputKey: String, text: String): Boolean {
            val sbn = cacheTake(key) ?: return false
            val actions = sbn.notification.actions ?: return false
            val action = actions.getOrNull(actionIdx) ?: return false
            val remote = action.remoteInputs?.firstOrNull() ?: return false
            return try {
                val filled = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(if (inputKey.isNotEmpty()) inputKey else remote.resultKey, text)
                RemoteInput.addResultsToIntent(arrayOf(remote), filled, bundle)
                action.actionIntent.send(context.applicationContext, 0, filled)
                true
            } catch (e: Exception) {
                android.util.Log.w("AmooLink", "回复发送失败:${e.message}")
                false
            }
        }

        @Synchronized
        fun cachedCount(): Int = cache.size
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("AmooLink", "通知监听就绪")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        // 自身常驻前台通知不上报;自测通道通知需通过(端到端验证回路)
        if (sbn.packageName == packageName && sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.packageName == packageName) {
            // 测试确认通知允许通过
        }
        cachePut(sbn)
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val replies = JSONArray()
        n.actions?.forEachIndexed { idx, action ->
            val ri = action.remoteInputs?.firstOrNull() ?: return@forEachIndexed
            val hints = JSONArray()
            ri.choices?.forEach { hints.put(it.toString()) }
            replies.put(JSONObject().apply {
                put("actionIdx", idx)
                put("inputKey", ri.resultKey ?: "")
                put("hints", hints)
            })
        }
        ConnectionService.instance?.broadcastEvent(JSONObject().apply {
            put("id", JSONObject.NULL)
            put("type", "notification")
            put("action", "posted")
            put("key", sbn.key)
            put("pkg", sbn.packageName)
            put("title", title)
            put("text", text)
            put("postTimeMs", sbn.postTime)
            put("replies", replies)
        })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName && sbn.isOngoing) return
        ConnectionService.instance?.broadcastEvent(JSONObject().apply {
            put("id", JSONObject.NULL)
            put("type", "notification")
            put("action", "removed")
            put("key", sbn.key)
            put("pkg", sbn.packageName)
        })
    }
}
