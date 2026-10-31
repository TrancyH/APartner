package com.amoolink.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac

/**
 * AmooLink 伴侣协议 v1(docs/04 §5 的 TLV 后备方案落地):
 * TLS 1.3 之上按「4 字节大端长度 + UTF-8 JSON」分帧,双向对称。
 * 消息公共字段:id(请求/响应关联,null=事件)、type。
 */
object Proto {
    const val PORT = 47888
    const val MAX_FRAME = 1 shl 20

    /** 手机端能力位图(docs/04 §5.3;投屏/文件等走 ADB 通道不在 APK v1 内)。 */
    const val CAP_CLIPBOARD_WRITE = 1L shl 5
    const val CAP_CLIPBOARD_READ_FOCUS = 1L shl 6
    const val CAP_NOTIF_READ = 1L shl 8
    const val CAP_NOTIF_REPLY = 1L shl 9
    const val CAP_SMS_READ = 1L shl 10
    const val CAP_CONTACTS_READ = 1L shl 12
    const val CAP_CALL_STATE = 1L shl 13
    const val PHONE_CAPS = CAP_CLIPBOARD_WRITE or CAP_CLIPBOARD_READ_FOCUS or
        CAP_NOTIF_READ or CAP_NOTIF_REPLY or CAP_SMS_READ or CAP_CONTACTS_READ or CAP_CALL_STATE

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun fp(certEncoded: ByteArray): String =
        sha256(certEncoded).joinToString("") { "%02x".format(it) }

    /**
     * SAS 六位比对码:HMAC-SHA256(key=OTP 大写 UTF-8, msg=transcript) 前 4 字节
     * 按 BE u32 取模 1e6 补零六位。桌面端 packages/companion 以相同定义实现。
     */
    fun sas6(otp: String, transcript: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(otp.uppercase().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(transcript)
        val v = ((out[0].toLong() and 0xff) shl 24) or ((out[1].toLong() and 0xff) shl 16) or
            ((out[2].toLong() and 0xff) shl 8) or (out[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000)
    }

    /** transcript = SHA256(nonceD ‖ nonceP ‖ 手机证书指纹 ‖ 桌面证书指纹)(全部原始字节)。 */
    fun transcript(nonceD: ByteArray, nonceP: ByteArray, phoneFp: ByteArray, desktopFp: ByteArray): ByteArray =
        sha256(nonceD + nonceP + phoneFp + desktopFp)

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** 一条已建立的对端连接:套接字 + 帧收发(单发送线程队列,任意线程可安全调用 send)。 */
class FrameConn(
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
) {
    private val outQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    @Volatile
    var open = true
        private set

    private val writer = Thread {
        while (open) {
            try {
                val buf = outQueue.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                output.write(buf)
                output.flush()
            } catch (_: IOException) {
                open = false
                break
            }
        }
    }.apply { name = "amoolink-writer"; isDaemon = true; start() }

    fun send(frame: JSONObject): Boolean {
        if (!open) return false
        val payload = frame.toString().toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4 + payload.size)
        buf[0] = (payload.size ushr 24).toByte()
        buf[1] = (payload.size ushr 16).toByte()
        buf[2] = (payload.size ushr 8).toByte()
        buf[3] = payload.size.toByte()
        System.arraycopy(payload, 0, buf, 4, payload.size)
        outQueue.put(buf)
        return true
    }

    fun read(): JSONObject? {
        if (!open) return null
        val head = ByteArray(4)
        if (!readFully(input, head)) {
            open = false
            return null
        }
        val len = ((head[0].toInt() and 0xff) shl 24) or ((head[1].toInt() and 0xff) shl 16) or
            ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        if (len <= 0 || len > Proto.MAX_FRAME) {
            open = false
            return null
        }
        val payload = ByteArray(len)
        if (!readFully(input, payload)) {
            open = false
            return null
        }
        return JSONObject(String(payload, Charsets.UTF_8))
    }

    fun close() {
        open = false
        outQueue.clear()
        try {
            input.close()
        } catch (_: IOException) {
        }
        try {
            output.close()
        } catch (_: IOException) {
        }
    }

    private fun readFully(stream: BufferedInputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}

/** 通知事件里的可回复槽位。 */
data class ReplySlot(val actionIdx: Int, val inputKey: String, val hints: List<String>)

fun JSONObject.optSlots(name: String): JSONArray? = optJSONArray(name)
