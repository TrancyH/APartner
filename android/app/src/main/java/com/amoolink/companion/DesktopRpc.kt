package com.amoolink.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 桌面 RPC(负数 id 请求,与桌面 respond() 对齐):
 *   phones_list {id<0} → phones_page {phones:[{id,name,connected}]}
 *   fs_relay {id<0, target, paths:[...]} → relay_result {ok, reason?}
 * 单请求队列;回调由 ConnectionService 读循环转交 onResponse。
 */
object DesktopRpc {

    data class PhoneTarget(val id: String, val name: String, val connected: Boolean)

    private val seq = AtomicInteger(0)
    private val responses = LinkedBlockingQueue<JSONObject>()

    fun onResponse(frame: JSONObject) {
        responses.put(frame)
    }

    /** 列出桌面侧其他在线手机。 */
    fun phonesList(conn: FrameConn, timeoutMs: Long = 8_000): List<PhoneTarget> {
        val resp = sendWait(conn, JSONObject().put("type", "phones_list"), "phones_page", timeoutMs)
        val out = ArrayList<PhoneTarget>()
        val arr = resp.optJSONArray("phones") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(PhoneTarget(o.optString("id"), o.optString("name"), o.optBoolean("connected", true)))
        }
        return out
    }

    /** 请求桌面把 paths 中继到目标手机;受理即回(结果经桌面 fs-updated/通知体现)。 */
    fun relay(conn: FrameConn, targetPhoneId: String, paths: List<String>, timeoutMs: Long = 15_000): JSONObject =
        sendWait(
            conn,
            JSONObject()
                .put("type", "fs_relay")
                .put("target", targetPhoneId)
                .put("paths", JSONArray(paths)),
            "relay_result",
            timeoutMs,
        )

    private fun sendWait(conn: FrameConn, body: JSONObject, okType: String, timeoutMs: Long): JSONObject {
        val id = -(seq.incrementAndGet().toLong())
        conn.send(body.put("id", id))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = responses.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f.optLong("id") == id && f.optString("type") == okType) return f
        }
        throw java.io.IOException("电脑端无响应(请升级电脑端 AmooLink 并确认已连接)")
    }
}
