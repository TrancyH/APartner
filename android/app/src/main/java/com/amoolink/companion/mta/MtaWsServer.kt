package com.amoolink.companion.mta

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * 极简 WSS + HTTPS 服务端(直接用 JSSE,不走 ktor/Netty)。
 *
 * 为什么不用 Netty:ktor 的 Netty 引擎会给 SSL 配 ApplicationProtocolConfig(ALPN),
 * 而 Netty 的 JdkAlpnSslEngine 在 Android 上会把一加/OPPO 客户端协商出的 ALPN 判成
 * 「不支持」并抛 AssertionError,握手当场失败(实测我们的端口已经收到一加的 TCP 连接,
 * 死在 TLS 层)。JSSE 的 SSLServerSocket 默认不做 ALPN,握手正常。
 *
 * 只实现协议需要的两件事:
 *   GET /websocket  (Upgrade: websocket) → 101 + 文本帧收发
 *   GET /download    → 200 + chunked 的 ZIP 流
 */
class MtaWsServer(
    keyStore: KeyStore,
    keyPass: CharArray,
    private val handler: Handler,
) {
    interface Handler {
        fun onOpen()
        fun onText(text: String)
        fun onClosed()
        fun onDownloadStart()
        fun onDownloadProgress(bytes: Long)
        fun onDownloadDone()
        /** 每次 /download 调用一次,返回 ZIP 数据流(由上层负责生成)。 */
        fun openZip(): InputStream
    }

    private val tls: SSLContext
    private var serverSocket: SSLServerSocket? = null
    @Volatile private var ws: WsConn? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyPass)
        tls = SSLContext.getInstance("TLS")
        tls.init(kmf.keyManagers, null, null)
    }

    fun start(): Int {
        val ss = tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        ss.reuseAddress = true
        serverSocket = ss
        thread(isDaemon = true, name = "mta-ws-accept") {
            while (!ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true, name = "mta-ws-conn") { serve(s) }
            }
        }
        Log.i(TAG, "JSSE 服务端监听 " + ss.localPort)
        return ss.localPort
    }

    fun stop() {
        runCatching { ws?.close() }
        ws = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun sendText(text: String) {
        ws?.sendText(text)
    }

    // ── 连接处理 ────────────────────────────────────────────────
    private fun serve(socket: java.net.Socket) {
        try {
            socket.soTimeout = 0
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())
            val head = readHeaders(input) ?: return
            val lines = head.split("\r\n")
            val reqLine = lines.firstOrNull().orEmpty()
            val parts = reqLine.split(" ")
            val path = parts.getOrNull(1).orEmpty()
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }

            if (headers["upgrade"]?.lowercase()?.contains("websocket") == true) {
                val key = headers["sec-websocket-key"]
                if (key == null) {
                    writeSimple(out, "400 Bad Request", "missing key")
                    return
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                )
                val sb = StringBuilder()
                sb.append("HTTP/1.1 101 Switching Protocols\r\n")
                sb.append("Upgrade: websocket\r\n")
                sb.append("Connection: Upgrade\r\n")
                sb.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                // 对端若要求子协议就原样接受,避免因协商失败被拒
                headers["sec-websocket-protocol"]?.let {
                    val first = it.split(",").first().trim()
                    if (first.isNotEmpty()) sb.append("Sec-WebSocket-Protocol: ").append(first).append("\r\n")
                }
                sb.append("\r\n")
                out.write(sb.toString().toByteArray())
                out.flush()

                val conn = WsConn(input, out, handler)
                ws = conn
                handler.onOpen()
                conn.readLoop()
                ws = null
                return
            }

            if (path.startsWith(MtaConst.DOWNLOAD_PATH)) {
                serveDownload(out)
                return
            }
            writeSimple(out, "404 Not Found", "no route")
        } catch (e: Exception) {
            Log.w(TAG, "连接处理失败: " + e.javaClass.simpleName + " " + e.message)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            val a = buf.toByteArray()
            if (a.size >= 4 && a[a.size - 4] == '\r'.code.toByte() && a[a.size - 3] == '\n'.code.toByte() &&
                a[a.size - 2] == '\r'.code.toByte() && a[a.size - 1] == '\n'.code.toByte()
            ) {
                return String(a, 0, a.size - 4, Charsets.UTF_8)
            }
        }
        return null
    }

    private fun writeSimple(out: OutputStream, status: String, body: String) {
        val b = body.toByteArray()
        val head = "HTTP/1.1 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: " +
            b.size + "\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(b)
        out.flush()
    }

    private fun serveDownload(out: OutputStream) {
        handler.onDownloadStart()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.flush()
        var total = 0L
        var lastUpd = 0L
        handler.openZip().use { stream ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                out.write(java.lang.Integer.toHexString(n).toByteArray())
                out.write("\r\n".toByteArray())
                out.write(buf, 0, n)
                out.write("\r\n".toByteArray())
                out.flush()
                total += n
                val now = System.currentTimeMillis()
                if (now - lastUpd > 1000) {
                    lastUpd = now
                    handler.onDownloadProgress(total)
                }
            }
        }
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
        handler.onDownloadProgress(total)
        handler.onDownloadDone()
    }

    // ── WebSocket 帧 ────────────────────────────────────────────
    private class WsConn(
        private val input: InputStream,
        private val out: OutputStream,
        private val handler: Handler,
    ) {
        @Volatile private var closed = false

        fun close() {
            closed = true
            runCatching { input.close() }
        }

        fun sendText(text: String) = writeFrame(0x1, text.toByteArray(Charsets.UTF_8))

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            synchronized(out) {
                val b = ByteArrayOutputStream()
                b.write(0x80 or opcode)
                val len = payload.size
                when {
                    len < 126 -> b.write(len)
                    len < 65536 -> {
                        b.write(126); b.write(len shr 8); b.write(len and 0xff)
                    }
                    else -> {
                        b.write(127)
                        for (i in 7 downTo 0) b.write(((len.toLong() shr (8 * i)) and 0xff).toInt())
                    }
                }
                b.write(payload)
                out.write(b.toByteArray())
                out.flush()
            }
        }

        fun readLoop() {
            var frag = ByteArrayOutputStream()
            var fragOpcode = -1
            try {
                while (!closed) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val fin = (b0 and 0x80) != 0
                    val opcode = b0 and 0x0f
                    val b1 = input.read()
                    if (b1 < 0) break
                    val masked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) {
                        val hi = input.read(); val lo = input.read()
                        if (hi < 0 || lo < 0) break
                        len = ((hi shl 8) or lo).toLong()
                    } else if (len == 127L) {
                        len = 0
                        for (i in 0 until 8) {
                            val x = input.read()
                            if (x < 0) break
                            len = (len shl 8) or x.toLong()
                        }
                    }
                    val mask = if (masked) ByteArray(4).also { readFully(it) } else null
                    val payload = ByteArray(len.toInt())
                    readFully(payload)
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                        }
                    }
                    when (opcode) {
                        0x1, 0x2 -> {
                            if (fin) handler.onText(String(payload, Charsets.UTF_8))
                            else {
                                frag = ByteArrayOutputStream(); frag.write(payload); fragOpcode = opcode
                            }
                        }
                        0x0 -> {
                            frag.write(payload)
                            if (fin) {
                                handler.onText(String(frag.toByteArray(), Charsets.UTF_8))
                                frag = ByteArrayOutputStream(); fragOpcode = -1
                            }
                        }
                        0x8 -> { close(); break }
                        0x9 -> writeFrame(0xA, payload)
                        else -> {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                closed = true
                handler.onClosed()
            }
        }

        private fun readFully(dst: ByteArray) {
            var off = 0
            while (off < dst.size) {
                val n = input.read(dst, off, dst.size - off)
                if (n <= 0) throw java.io.EOFException()
                off += n
            }
        }
    }

    companion object {
        private const val TAG = "MtaWsServer"
    }
}
