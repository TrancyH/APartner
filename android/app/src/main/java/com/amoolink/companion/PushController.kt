package com.amoolink.companion

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 手机 → 桌面文件推送会话状态机(协议 v2 反向 fs_push_*;与桌面 fs-protocol.ts 对齐):
 *
 *   fs_push_begin {id<0, pushId, path, name, size}  → fs_push_ready {id, ok, reason?}
 *   fs_push_chunk {id<0, pushId, offset, data, sha256} → fs_push_ack {id, ok, reason?}
 *   fs_push_end {id<0, pushId, sha256}              → fs_push_done {id, ok}
 *
 * id 一律负数递减(桌面自身请求 id 恒正,互不冲突;桌面 respond() 原样回显)。
 * 逐块等 ack 即流控;单活动会话;响应帧由 ConnectionService 读循环线程转交 onResponse,
 * 推送线程经 sendWait 匹配(其他来源的响应丢弃)。回调在推送线程触发,UI 自行切线程。
 */
object PushController {

    interface Listener {
        /** 每块 ack 后触发(推送线程)。 */
        fun onProgress(sent: Long, total: Long)

        fun onDone()

        fun onError(message: String)
    }

    private const val CHUNK = 512 * 1024

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "amoolink-push").apply { isDaemon = true }
    }

    /** 来自桌面的响应帧(ready/ack/done),由推送线程按 id 匹配消费。 */
    private val responses = LinkedBlockingQueue<JSONObject>()

    @Volatile
    private var active: Boolean = false

    /** @return false = 已有推送在进行(单会话)。 */
    fun push(conn: FrameConn, file: File, displayName: String, listener: Listener): Boolean {
        synchronized(this) {
            if (active) return false
            active = true
        }
        io.execute { run(conn, file, displayName, listener) }
        return true
    }

    /** ConnectionService 读循环线程转交桌面响应帧。 */
    fun onResponse(frame: JSONObject) {
        responses.put(frame)
    }

    private fun run(conn: FrameConn, file: File, displayName: String, listener: Listener) {
        val pushId = UUID.randomUUID().toString().take(8)
        val seq = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            val size = file.length()
            // ── begin ──
            sendWait(
                conn, seq,
                JSONObject()
                    .put("type", "fs_push_begin")
                    .put("pushId", pushId)
                    .put("path", file.absolutePath)
                    .put("name", displayName)
                    .put("size", size),
                "fs_push_ready", 15_000,
            )
            // ── chunks(读时增量 sha256)──
            val md = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(CHUNK)
                while (offset < size) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    val part = buf.copyOf(n)
                    md.update(part, 0, n)
                    sendWait(
                        conn, seq,
                        JSONObject()
                            .put("type", "fs_push_chunk")
                            .put("pushId", pushId)
                            .put("offset", offset)
                            .put("data", Proto.b64(part))
                            .put("sha256", hex(Proto.sha256(part))),
                        "fs_push_ack", 30_000,
                    )
                    offset += n
                    listener.onProgress(offset, size)
                }
            }
            // ── end ──
            sendWait(
                conn, seq,
                JSONObject()
                    .put("type", "fs_push_end")
                    .put("pushId", pushId)
                    .put("sha256", hex(md.digest())),
                "fs_push_done", 30_000,
            )
            listener.onDone()
        } catch (e: Exception) {
            listener.onError(e.message ?: e.javaClass.simpleName)
        } finally {
            responses.clear()
            synchronized(this) { active = false }
        }
    }

    /** 发一帧并等待对应响应;ok=false 视为失败(reason 透传);超时 = 电脑端过旧或断开。 */
    private fun sendWait(
        conn: FrameConn,
        seq: java.util.concurrent.atomic.AtomicInteger,
        body: JSONObject,
        okType: String,
        timeoutMs: Long,
    ) {
        val id = -(seq.incrementAndGet().toLong())
        conn.send(body.put("id", id))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = responses.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f.optLong("id") == id && f.optString("type") == okType) {
                if (!f.optBoolean("ok", false)) {
                    throw IOException("电脑端拒绝:${f.optString("reason", "unknown")}")
                }
                return
            }
            // 非本请求的响应(不应发生,单会话)直接丢弃
        }
        throw IOException("电脑端无响应(请升级电脑端 AmooLink 并确认已连接)")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
