package com.amoolink.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * M5-1/M5-2:唯一前台服务。TLS 1.3 Server(端口 47888),每桌面端一条帧连接;
 * 配对态机(docs/04 §4:OTP 不上线 + SAS 六位比对 + 证书钉扎);
 * 事件扇出(通知/来电/剪贴板)与请求处理(剪贴板/回复/短信/通讯录)。
 */
class ConnectionService : Service() {

    companion object {
        const val CHANNEL_CONN = "conn"
        const val CHANNEL_CALL = "call"
        const val CHANNEL_TEST = "test"
        const val KEYSTORE_ALIAS = "amoolink-id-v2"
        const val PREFS = "companion"

        @Volatile
        var instance: ConnectionService? = null
            private set

        val random = SecureRandom()
    }

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val seq = AtomicLong(1)
    private val sessions = ConcurrentHashMap<String, FrameConn>() // key=桌面证书指纹

    /** 配对态机(一次性 OTP,5 分钟过期;docs/04 §4.2)。 */
    object Pairing {
        @Volatile
        var otp: String? = null
            private set

        @Volatile
        var expiresAt = 0L
            private set

        @Volatile
        var pendingDesktopFp: String? = null

        @Volatile
        var pendingSas: String? = null

        @Volatile
        var pendingDesktopName: String? = null

        fun begin(): String {
            val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
            val otp = buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
            this.otp = otp
            this.expiresAt = System.currentTimeMillis() + 5 * 60_000
            this.pendingDesktopFp = null
            this.pendingSas = null
            this.pendingDesktopName = null
            return otp
        }

        fun active(): Boolean = otp != null && System.currentTimeMillis() < expiresAt

        fun invalidate() {
            otp = null
            pendingDesktopFp = null
            pendingSas = null
            pendingDesktopName = null
        }
    }

    @Volatile
    private var serverThread: Thread? = null

    @Volatile
    private var phoneCertEncoded: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        startForeground(1, buildStatusNotification("服务启动中…"))
        ensureIdentity()
        // StepOS 会冻结后台应用(即使 FGS)导致线程停摆 —— 部分唤醒锁缓解 + 引导电池白名单
        holdWakeLock()
        startServerLoop()
        startPhoneWatcher()
        attachClipboardListener()
        updateStatus()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun holdWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "amoolink:companion").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            android.util.Log.w("AmooLink", "唤醒锁获取失败:${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        serverThread?.interrupt()
        for (conn in sessions.values) conn.close()
        Pairing.invalidate()
        super.onDestroy()
    }

    // ── 身份密钥(Android Keystore 自签证书,TLS 服务端凭据)──────────────

    private fun ensureIdentity() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.getCertificate(KEYSTORE_ALIAS) == null) {
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // DIGEST_NONE 必须授权:Conscrypt 在 TLS CertificateVerify 中先自行完成
                    // 摘要、再要求密钥做裸签名(实测 StepOS 上缺失该授权即握手失败)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=AmooLink Phone"))
                    .setCertificateNotBefore(java.util.Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 3650L * 86_400_000L))
                    .build()
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
            phoneCertEncoded = ks.getCertificate(KEYSTORE_ALIAS).encoded
        } catch (e: Exception) {
            android.util.Log.e("AmooLink", "身份密钥初始化失败", e)
        }
    }

    fun phoneFp(): String = Proto.fp(phoneCertEncoded ?: ByteArray(0))

    // ── TLS 服务器 ───────────────────────────────────────────────────

    private fun startServerLoop() {
        if (serverThread?.isAlive == true) return
        serverThread = Thread {
            try {
                // 47888 = 信箱端口(纯 TCP):桌面端发送一行 CONNECT <ip> <port> 触发本机出向回连。
                // (StepOS 实测:入站 TLS 服务端无论 Keystore 与否均被即刻掐断;出向 TLS 畅通,
                //  故 TLS 服务端在桌面端,手机做出向客户端,身份经协议内挑战-签名认证。)
                val srv = java.net.ServerSocket(Proto.PORT)
                android.util.Log.i("AmooLink", "mailbox listening ${Proto.PORT}")
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val s = srv.accept()
                        Thread { handleMailbox(s) }.start()
                    } catch (e: Exception) {
                        // 单次 accept 失败(fd 紧张等)不终止信箱循环
                        android.util.Log.w("AmooLink", "mailbox accept 异常:${e.message}")
                        Thread.sleep(500)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AmooLink", "信箱端口退出", e)
            }
        }.apply { name = "amoolink-mailbox"; start() }
    }

    private fun handleMailbox(s: java.net.Socket) {
        try {
            s.soTimeout = 4000
            val line = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream())).readLine() ?: return
            android.util.Log.i("AmooLink", "mailbox line=$line")
            val m = Regex("CONNECT (\\S+) (\\d+)").matchEntire(line.trim()) ?: run {
                android.util.Log.w("AmooLink", "mailbox 不识别:$line")
                return
            }
            val host = m.groupValues[1]
            val port = m.groupValues[2].toInt()
            android.util.Log.i("AmooLink", "mailbox spawn dial $host:$port")
            Thread { dialDesktop(host, port) }.start()
        } catch (_: Exception) {
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 出向连接桌面端 TLS 服务器;认证后进入帧分发(与桌面端原协议语义一致)。 */
    private fun dialDesktop(host: String, port: Int) {
        android.util.Log.i("AmooLink", "dial-entry ${host}:${port}")
        var socket: SSLSocket? = null
        var conn: FrameConn? = null
        try {
            val ctx = buildClientSslContext()
            val s = ctx.socketFactory.createSocket(host, port) as SSLSocket
            socket = s
            android.util.Log.i("AmooLink", "dial ${host}:${port}")
            s.startHandshake()
            s.soTimeout = 8000 // 握手完成后再设:实测握手前设置会干扰 Conscrypt 读时序
            val peerCert = s.session.peerCertificates.firstOrNull() ?: return
            val desktopFp = Proto.fp(peerCert.encoded)
            val pinned = pinnedFps().contains(desktopFp)
            if (!pinned && !Pairing.active()) return
            val c = FrameConn(
                BufferedInputStream(s.inputStream),
                BufferedOutputStream(s.outputStream),
            )
            conn = c
            // 应用层认证:应答桌面端挑战(Keystore 直接签名;实测 Conscrypt TLS 内签名不可用)
            val challenge = c.read()
            android.util.Log.i("AmooLink", "challenge 帧到达:${challenge?.optString("type")}")
            if (challenge == null || challenge.optString("type") != "auth_challenge") return
            // 会话期改为无限等待:保活交给桌面端 10s 心跳(8s 空闲超时会误杀解锁等待等长静默期)
            s.soTimeout = 0
            val sig = signWithIdentity(Proto.unb64(challenge.optString("nonce")))
            c.send(JSONObject().apply {
                put("id", challenge.opt("id"))
                put("type", "auth")
                put("cert", phoneCertPem())
                put("sig", Proto.b64(sig))
                put("name", Build.MODEL ?: "Android")
            })
            if (pinned) sessions[desktopFp] = c
            updateStatus()
            try {
                while (c.open) {
                    val frame = c.read() ?: break
                    if (!handleFrame(c, desktopFp, pinned, frame)) break
                }
            } finally {
                if (sessions[desktopFp] === c) sessions.remove(desktopFp)
                updateStatus()
            }
        } catch (e: Exception) {
            android.util.Log.w("AmooLink", "回连桌面失败 ${host}:${port} ${e.message}")
        } finally {
            // 全路径关闭,防 fd 泄漏拖垮信箱线程
            try {
                conn?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** AndroidKeystore 身份密钥直接签名(JCA 通路;数据 = 待签原文)。 */
    private fun signWithIdentity(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(KEYSTORE_ALIAS, null) as java.security.KeyStore.PrivateKeyEntry
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(entry.privateKey)
        sig.update(data)
        return sig.sign()
    }

    /** 出向 TLS 上下文:不携带客户端证书(身份走应用层挑战-签名)。 */
    fun buildClientSslContext(): SSLContext {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, random)
        return ctx
    }

    /** @return false = 断开该连接。 */
    private fun handleFrame(conn: FrameConn, peerFp: String, pinned: Boolean, frame: JSONObject): Boolean {
        val id = frame.opt("id")
        val type = frame.optString("type")
        when (type) {
            "pair_init" -> return handlePairInit(conn, peerFp, frame)
            "ping" -> conn.send(ok(id, "pong") { put("t", frame.optLong("t")) })
            "capability" -> conn.send(ok(id, "capability") { put("caps", Proto.PHONE_CAPS) })
            "clipboard_set" -> {
                val text = frame.optString("text")
                setClipboard(text)
                conn.send(ok(id, "reply_result") { put("ok", true) })
            }
            "clipboard_get" -> {
                val clip = readClipboard()
                if (clip != null) conn.send(ok(id, "clipboard_data") { put("ok", true); put("text", clip) })
                else conn.send(ok(id, "clipboard_data") { put("ok", false); put("error", "focus_required") })
            }
            "reply" -> {
                val ok = CompanionNotificationListener.sendReply(
                    this, frame.optString("key"), frame.optInt("actionIdx", -1), frame.optString("inputKey"), frame.optString("text"),
                )
                conn.send(ok(id, "reply_result") {
                    put("ok", ok)
                    if (!ok) put("error", "reply_failed")
                })
            }
            "sms_query" -> {
                val items = SmsReader.query(this, frame.optInt("max", 200))
                conn.send(ok(id, "sms_page") { put("items", items) })
            }
            "contacts_query" -> {
                val items = ContactsReader.query(this, frame.optInt("max", 500))
                conn.send(ok(id, "contacts_page") { put("items", items) })
            }
            else -> conn.send(ok(id, "error") { put("code", "unknown_type"); put("message", type) })
        }
        // 配对模式但收到非配对消息且未钉扎 → 断开
        return true
    }

    private fun ok(id: Any?, type: String, body: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply {
            put("id", if (id == JSONObject.NULL) null else id)
            put("type", type)
            body()
        }

    // ── 配对(docs/04 §4.2,QR/手动输入 OTP 的兜底路径)────────────────

    private fun handlePairInit(conn: FrameConn, desktopFp: String, frame: JSONObject): Boolean {
        if (!Pairing.active()) {
            conn.send(ok(frame.opt("id"), "error") { put("code", "no_pairing"); put("message", "未在配对模式") })
            return false
        }
        val nonceD = Proto.unb64(frame.optString("nonce"))
        Pairing.pendingDesktopFp = desktopFp
        Pairing.pendingDesktopName = frame.optString("name")
        val nonceP = ByteArray(16).also { random.nextBytes(it) }
        val transcript = Proto.transcript(nonceD, nonceP, hexToBytes(phoneFp()), hexToBytes(desktopFp))
        Pairing.pendingSas = Proto.sas6(Pairing.otp!!, transcript)
        conn.send(ok(frame.opt("id"), "pair_response") {
            put("nonce", Proto.b64(nonceP))
            put("name", Build.MODEL ?: "Android")
        })
        // 保存待确认连接:用户在手机上点「确认配对」后经此发送 paired + historyKey
        pairingConn = conn
        pairingDesktopFp = desktopFp
        return true
    }

    @Volatile
    private var pairingConn: FrameConn? = null

    @Volatile
    private var pairingDesktopFp: String? = null

    /** 用户在手机 UI 比对 SAS 一致后点确认:钉扎桌面证书并下发 historyKey。 */
    fun confirmPairing() {
        val conn = pairingConn ?: return
        val desktopFp = pairingDesktopFp ?: return
        val historyKey = ByteArray(32).also { random.nextBytes(it) }
        prefs.edit()
            .putStringSet("pinned", (pinnedFps() + desktopFp).toSet())
            .putString("name.$desktopFp", Pairing.pendingDesktopName ?: "桌面")
            .putString("hkey.$desktopFp", Proto.b64(historyKey))
            .apply()
        conn.send(JSONObject().apply {
            put("id", JSONObject.NULL)
            put("type", "paired")
            put("name", Build.MODEL ?: "Android")
            put("historyKey", Proto.b64(historyKey))
            put("phoneCert", phoneCertPem())
        })
        Pairing.invalidate()
        pairingConn = null
        pairingDesktopFp = null
        // 对端将断开并以 mTLS 重连
        updateStatus()
    }

    fun cancelPairing() {
        pairingConn?.let { it.send(JSONObject().apply { put("id", JSONObject.NULL); put("type", "error"); put("code", "pairing_canceled") }) }
        pairingConn?.close()
        pairingConn = null
        pairingDesktopFp = null
        Pairing.invalidate()
    }

    fun forget(desktopFp: String) {
        prefs.edit()
            .putStringSet("pinned", pinnedFps().filter { it != desktopFp }.toSet())
            .remove("name.$desktopFp")
            .remove("hkey.$desktopFp")
            .apply()
        sessions[desktopFp]?.close()
        sessions.remove(desktopFp)
        updateStatus()
    }

    private fun phoneCertPem(): String {
        val cert = phoneCertEncoded ?: return ""
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    fun pinnedFps(): Set<String> = prefs.getStringSet("pinned", emptySet()) ?: emptySet()

    fun pinnedName(fp: String): String = prefs.getString("name.$fp", "桌面") ?: "桌面"

    fun historyKeyOf(fp: String): ByteArray? =
        prefs.getString("hkey.$fp", null)?.let { Proto.unb64(it) }

    // ── 事件扇出(NLS / 来电 / 剪贴板)─────────────────────────────────

    fun broadcastEvent(frame: JSONObject) {
        for (conn in sessions.values) conn.send(frame)
    }

    fun connectedDesktops(): List<String> = sessions.keys.toList()

    // ── 剪贴板 ───────────────────────────────────────────────────────

    private fun attachClipboardListener() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            // Android 10+:后台收不到他人复制回调;此监听仅在自身可读时产生推送
            val text = readClipboard()
            if (text != null && text.isNotEmpty()) {
                broadcastEvent(JSONObject().apply {
                    put("id", JSONObject.NULL)
                    put("type", "clipboard")
                    put("text", text)
                    put("src", "phone")
                    put("ts", System.currentTimeMillis())
                })
            }
        }
    }

    /** 焦点受限下的尽力读取(无焦点返回 null,能力上报 CLIPBOARD_READ_FOCUS)。 */
    fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun setClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AmooLink", text))
        } catch (e: Exception) {
            android.util.Log.w("AmooLink", "写剪贴板失败:${e.message}")
        }
    }

    // ── 来电(docs/04 §3.2 PhoneStateWatcher)─────────────────────────

    private fun startPhoneWatcher() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            android.util.Log.w("AmooLink", "电话状态监听失败:${e.message}")
        }
    }

    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            // 监听回调跑主线程,任何异常都会杀进程 —— 全量兜底
            try {
                handleCallState(state, phoneNumber)
            } catch (e: Exception) {
                android.util.Log.w("AmooLink", "来电事件处理异常:${e.message}")
            }
        }
    }

    private var lastCallState: Int = -1

    private fun handleCallState(state: Int, phoneNumber: String?) {
        if (state == lastCallState) return // 注册时的初始 IDLE 回调不上报
        lastCallState = state
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val stateName = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        val name = phoneNumber?.let { ContactsReader.lookupName(this, it) }
        broadcastEvent(JSONObject().apply {
            put("id", JSONObject.NULL)
            put("type", "call")
            put("state", stateName)
            if (!phoneNumber.isNullOrEmpty()) {
                put("numberHash", Proto.fp(phoneNumber.toByteArray()).substring(0, 16))
            }
            if (name != null) put("contactName", name)
            put("ts", System.currentTimeMillis())
        })
        if (stateName == "ringing") notifyCall(name)
    }

    private fun notifyCall(name: String?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_CALL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        nm.notify(2, builder
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("来电")
            .setContentText(name ?: "未知号码")
            .setAutoCancel(true)
            .build())
    }

    // ── 前台通知与状态 ───────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CONN, "连接服务", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_CALL, "来电提醒", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TEST, "自测", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun buildStatusNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_CONN)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AmooLink 伴侣")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    fun updateStatus() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val connCount = sessions.size
        val pairing = if (Pairing.active()) " · 配对中" else ""
        nm.notify(1, buildStatusNotification("已连接桌面 $connCount 台 · 端口 ${Proto.PORT}$pairing"))
    }

    fun localIps(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val enums: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in enums) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.address.size == 4) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** 自测钩子:配对状态导出到 logcat(探针经 M5STATE 行读取,免屏幕依赖)。 */
    fun dumpPairingState() {
        val ip = localIps().firstOrNull() ?: "-"
        val otp = Pairing.otp ?: "-"
        val sas = Pairing.pendingSas ?: "-"
        android.util.Log.i(
            "AmooLink",
            "M5STATE pairing=${Pairing.active()} ip=$ip otp=$otp sas=$sas pinned=${pinnedFps().size}",
        )
    }
}

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte() }
