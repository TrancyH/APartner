package com.amoolink.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * 自测钩子:真机探针经 `adb shell am broadcast` 驱动端到端验证(M5 探针自动化用)。
 * 仅触发无害的自身通知回路,不涉及用户数据。
 */
class TestHookReceiver : BroadcastReceiver() {

    companion object {
        const val ACT_START_SERVICE = "com.amoolink.companion.ACTION_START_SERVICE"
        const val ACT_START_PAIRING = "com.amoolink.companion.ACTION_START_PAIRING"
        const val ACT_CONFIRM_PAIRING = "com.amoolink.companion.ACTION_CONFIRM_PAIRING"
        const val ACT_CANCEL_PAIRING = "com.amoolink.companion.ACTION_CANCEL_PAIRING"
        const val ACT_TEST_POST_REPLY = "com.amoolink.companion.ACTION_TEST_POST_REPLY"
        const val ACT_TEST_GET_CLIP = "com.amoolink.companion.ACTION_TEST_GET_CLIP"
        const val ACT_TEST_REPLY_RECV = "com.amoolink.companion.ACTION_TEST_REPLY_RECV"
        const val ACT_DUMP_PAIRING = "com.amoolink.companion.ACTION_DUMP_PAIRING"
        const val ACT_TEST_FS = "com.amoolink.companion.ACTION_TEST_FS"
        const val ACT_TEST_PUSH = "com.amoolink.companion.ACTION_TEST_PUSH"
        const val ACT_TEST_RELAY = "com.amoolink.companion.ACTION_TEST_RELAY"

        /** 最近一次自研回复收到的内容(探针经通知回路读取,不依赖此静态量)。 */
        @Volatile
        var lastReplyText: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACT_START_SERVICE -> {
                context.startForegroundService(Intent(context, ConnectionService::class.java))
            }

            ACT_START_PAIRING -> {
                if (ConnectionService.instance == null) {
                    context.startForegroundService(Intent(context, ConnectionService::class.java))
                }
                ConnectionService.instance?.let { ConnectionService.Pairing.begin() }
            }

            ACT_CONFIRM_PAIRING -> Thread { ConnectionService.instance?.confirmPairing() }.start()

            ACT_CANCEL_PAIRING -> Thread { ConnectionService.instance?.cancelPairing() }.start()

            ACT_TEST_POST_REPLY -> postReplyTestNotification(context, intent.getStringExtra("marker") ?: "M5")

            ACT_TEST_GET_CLIP -> echoClipboard(context)

            ACT_DUMP_PAIRING -> ConnectionService.instance?.dumpPairingState()
                ?: android.util.Log.i("AmooLink", "M5STATE pairing=false service=down")

            "com.amoolink.companion.ACTION_MTA_SEND" -> {
                val path = intent.getStringExtra("path")
                if (path.isNullOrEmpty()) {
                    android.util.Log.w("AmooLink", "MTASEND fail: no path")
                } else {
                    mtaSendTest(context, path, intent.getStringExtra("name"))
                }
            }

            "com.amoolink.companion.ACTION_MTA_PANEL" -> {
                if (intent.getStringExtra("mode") == "received") {
                    com.amoolink.companion.mta.MtaUiBridge.emitReceived(
                        com.amoolink.companion.mta.MtaUiBridge.Received(
                            intent.getStringExtra("file") ?: "1793510513941.jpg",
                            intent.getIntExtra("count", 1),
                            intent.getLongExtra("size", 5221435L),
                        ),
                    )
                    return
                }
                val ok = com.amoolink.companion.mta.MtaUiBridge.emit(
                    com.amoolink.companion.mta.MtaUiBridge.IncomingRequest(
                        intent.getStringExtra("sender") ?: "TranzyH",
                        intent.getStringExtra("file") ?: "1793510513941.jpg",
                        intent.getIntExtra("count", 1),
                        intent.getLongExtra("size", 5221435L),
                    ) { accept ->
                        android.util.Log.i("AmooLink", "PANEL answer=" + accept)
                    },
                )
                android.util.Log.i("AmooLink", "PANEL emitted=" + ok)
            }

            ACT_TEST_FS -> testFs(context)

            ACT_TEST_PUSH -> testPush(context)

            ACT_TEST_RELAY -> testRelay(context)

            "com.amoolink.companion.ACTION_TLS_TEST" -> {
                val host = intent.getStringExtra("host") ?: "192.168.5.6"
                val port = intent.getIntExtra("port", 47892)
                Thread {
                    try {
                        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        })
                        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                        ctx.init(null, tm, null)
                        val factory = ctx.socketFactory
                        val socket = factory.createSocket(host, port) as javax.net.ssl.SSLSocket
                        socket.startHandshake()
                        socket.getOutputStream().write("TLS-FROM-PHONE".toByteArray())
                        socket.getOutputStream().flush()
                        android.util.Log.i("AmooLink", "TLSTEST ok proto=${socket.session.protocol}")
                        socket.close()
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink", "TLSTEST fail: ${e.message}", e)
                    }
                }.start()
            }

            "com.amoolink.companion.ACTION_SIGN_TEST" -> {
                Thread {
                    try {
                        val entry = java.security.KeyStore.getInstance("AndroidKeyStore")
                            .apply { load(null) }
                            .getEntry(ConnectionService.KEYSTORE_ALIAS, null)
                            as java.security.KeyStore.PrivateKeyEntry
                        val sig = java.security.Signature.getInstance("SHA256withECDSA")
                        sig.initSign(entry.privateKey)
                        sig.update(ByteArray(32) { it.toByte() })
                        val out = sig.sign()
                        android.util.Log.i("AmooLink", "SIGNTEST ok len=${out.size}")
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink", "SIGNTEST fail: ${e.message}", e)
                    }
                }.start()
            }

            "com.amoolink.companion.ACTION_TLS_TEST2" -> {
                val host = intent.getStringExtra("host") ?: "192.168.5.6"
                val port = intent.getIntExtra("port", 47892)
                Thread {
                    try {
                        val svc = ConnectionService.instance ?: run {
                            android.util.Log.w("AmooLink", "TLSTEST2 fail: 服务未运行")
                            return@Thread
                        }
                        val ctx = svc.buildClientSslContext()
                        val socket = ctx.socketFactory.createSocket(host, port) as javax.net.ssl.SSLSocket
                        if (intent.getBooleanExtra("tls12", false)) {
                            socket.enabledProtocols = arrayOf("TLSv1.2")
                        }
                        socket.startHandshake()
                        socket.getOutputStream().write("MTLS-FROM-PHONE".toByteArray())
                        socket.getOutputStream().flush()
                        android.util.Log.i("AmooLink", "TLSTEST2 ok proto=${socket.session.protocol}")
                        socket.close()
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink", "TLSTEST2 fail: ${e.message}", e)
                    }
                }.start()
            }

            "com.amoolink.companion.ACTION_DUMP_SERVER" -> {
                Thread {
                    try {
                        java.net.ServerSocket(47891).use { srv ->
                            srv.soTimeout = 20000
                            android.util.Log.i("AmooLink", "DUMPSRV listening 47891")
                            val s = srv.accept()
                            s.use {
                                it.soTimeout = 4000
                                val buf = ByteArray(64)
                                var total = 0
                                try {
                                    while (total < 64) {
                                        val n = it.getInputStream().read(buf, total, 64 - total)
                                        if (n < 0) break
                                        total += n
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("AmooLink", "DUMPSRV read: ${e.message}")
                                }
                                android.util.Log.i(
                                    "AmooLink",
                                    "DUMPSRV got=$total head=" + buf.take(kotlin.math.min(total, 16))
                                        .joinToString("") { "%02x".format(it) },
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink", "DUMPSRV fail: ${e.message}")
                    }
                }.start()
            }

            "com.amoolink.companion.ACTION_NET_TEST" -> {
                val host = intent.getStringExtra("host") ?: "192.168.5.6"
                val port = intent.getIntExtra("port", 47890)
                Thread {
                    try {
                        java.net.Socket(host, port).use { s ->
                            s.soTimeout = 4000
                            s.getOutputStream().write("HELLO-FROM-PHONE".toByteArray())
                            s.getOutputStream().flush()
                            val n = s.getInputStream().read()
                            android.util.Log.i("AmooLink", "NETTEST ok peer-read=$n")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink", "NETTEST fail: ${e.message}")
                    }
                }.start()
            }

            ACT_TEST_REPLY_RECV -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply_key")?.toString()
                if (text != null) {
                    lastReplyText = text
                    // 回执通知:经 NLS 真实通路回到桌面端,探针以此断言回复闭环
                    postMarkerNotification(context, "AMOOREPLY:$text")
                }
            }
        }
    }

    private fun postReplyTestNotification(context: Context, marker: String) {
        val replyIntent = Intent(ACT_TEST_REPLY_RECV).setPackage(context.packageName)
        val replyPi = PendingIntent.getBroadcast(
            context, 1, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder("reply_key").setLabel("回复").build()
        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_send, "回复", replyPi,
        ).addRemoteInput(remoteInput).build()
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, ConnectionService.CHANNEL_TEST)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(10, builder
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("AmooLink 自测")
            .setContentText("AMOOTEST:$marker —— 电脑上回复这条通知验证快捷回复")
            .addAction(action)
            .setAutoCancel(true)
            .build())
    }

    /**
     * 手机↔手机中继自测:生成测试文件 → phones_list 选目标机 → fs_relay,
     * logcat 打 `M5RELAY ok=… target=… sha=…`(桌面探针据此闭环验证)。
     */
    private fun testRelay(context: Context) {
        val svc = ConnectionService.instance
        val conn = svc?.firstDesktopConn()
        if (conn == null) {
            android.util.Log.i("AmooLink", "M5RELAY ok=false err=no_desktop")
            return
        }
        Thread {
            try {
                val dir = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                dir.mkdirs()
                val file = java.io.File(dir, "apartner-relay-probe.bin")
                val data = ByteArray(512 * 1024 + 13) { (it % 253).toByte() }
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(data)
                }
                val phones = DesktopRpc.phonesList(conn)
                if (phones.isEmpty()) {
                    android.util.Log.i("AmooLink", "M5RELAY ok=false err=no_target")
                    return@Thread
                }
                val target = phones.first()
                val resp = DesktopRpc.relay(conn, target.id, listOf(file.absolutePath))
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val sha = md.digest(data).joinToString("") { "%02x".format(it) }
                android.util.Log.i(
                    "AmooLink",
                    "M5RELAY ok=${resp.optBoolean("ok", false)} target=${target.name} sha=$sha",
                )
            } catch (e: Exception) {
                android.util.Log.i("AmooLink", "M5RELAY ok=false err=${e.message}")
            }
        }.start()
    }

    private fun echoClipboard(context: Context) {
        val clip = ConnectionService.instance?.readClipboard() ?: "(不可读)"
        postMarkerNotification(context, "AMOOCLIP:$clip")
    }

    /**
     * 反向推送自测:生成 Download/apartner-push-probe.bin(512KiB+7 随机字节)并推给已连接桌面,
     * logcat 打 `M5PUSH ok=… sha=…`(桌面探针据此闭环验证 fs_push_* 全链路)。文件保留供比对。
     */
    private fun testPush(context: Context) {
        val svc = ConnectionService.instance
        if (svc == null || !svc.hasConnectedDesktop()) {
            android.util.Log.i("AmooLink", "M5PUSH ok=false err=no_desktop")
            return
        }
        val dir = java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        dir.mkdirs()
        val file = java.io.File(dir, "apartner-push-probe.bin")
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val data = ByteArray(512 * 1024 + 7) { (it % 249).toByte() }
            raf.seek(0)
            raf.write(data)
        }
        val pushed = svc.pushToDesktop(null, file, object : PushController.Listener {
            override fun onProgress(sent: Long, total: Long) {}

            override fun onDone() {
                android.util.Log.i("AmooLink", "M5PUSH ok=true path=${file.absolutePath}")
            }

            override fun onError(message: String) {
                android.util.Log.i("AmooLink", "M5PUSH ok=false err=$message")
            }
        })
        if (!pushed) android.util.Log.i("AmooLink", "M5PUSH ok=false err=push_rejected")
    }

    /**
     * fs_* 自测:在 Download/ 走一遍 写 → 读 → sha 比对 → 删,
     * logcat 打 `M5FS fs ok=… sha=…`(探针据此断言存储授权与 IO 通路)。
     */
    private fun testFs(context: Context) {
        Thread {
            var file: java.io.File? = null
            try {
                val dir = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                dir.mkdirs()
                file = java.io.File(dir, "apartner-fs-probe.bin")
                val data = ByteArray(64 * 1024) { (it % 251).toByte() }
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(data)
                }
                val readBack = ByteArray(data.size)
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(0)
                    raf.readFully(readBack)
                }
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val sha = md.digest(readBack).joinToString("") { "%02x".format(it) }
                val ok = data.contentEquals(readBack)
                android.util.Log.i("AmooLink", "M5FS fs ok=$ok sha=$sha path=${file.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.i("AmooLink", "M5FS fs ok=false err=${e.javaClass.simpleName}:${e.message}")
            } finally {
                file?.delete()
            }
        }.start()
    }

    /**
     * MTA 自测:扫 BLE 找到指定名字的互传设备,然后直接发起发送。
     * 用法:adb shell am broadcast -a com.amoolink.companion.ACTION_MTA_SEND \
     *        --es path /sdcard/Download/mta-test.txt --es name TranzyH
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun mtaSendTest(context: Context, path: String, name: String?) {
        Thread {
            try {
                val bt = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                val scanner = bt?.bluetoothLeScanner
                if (scanner == null) {
                    android.util.Log.w("AmooLink", "MTASEND fail: no scanner")
                    return@Thread
                }
                val latch = java.util.concurrent.CountDownLatch(1)
                val found = java.util.concurrent.atomic.AtomicReference<android.bluetooth.BluetoothDevice>()
                val cb = object : android.bluetooth.le.ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                        val rec = result.scanRecord
                        // MTA 的设备名在 27 字节扫描响应的 [10..25](不是 AD 里的 deviceName)
                        val mtaName = rec?.serviceData?.values?.firstOrNull { it.size == 27 }?.let { d ->
                            var b = d.copyOfRange(10, 26)
                            if (b[15] == '\t'.code.toByte()) b = b.copyOfRange(0, 15)
                            String(b, Charsets.UTF_8).trimEnd('\u0000')
                        }
                        val adName = rec?.deviceName ?: result.device.name
                        val nameHit = name == null ||
                            (mtaName != null && (mtaName == name || mtaName.contains(name))) ||
                            (adName != null && (adName == name || adName.contains(name)))
                        // MTA 设备的判据:广播里有 6 字节或 27 字节的 service data,
                        // 或者 service uuid 以 00003331 开头(名字在 27 字节里,AD 名可能为空)
                        val mtaLike = rec?.serviceData?.values?.any { it.size == 6 || it.size == 27 } == true ||
                            rec?.serviceUuids?.any { it.uuid.toString().startsWith("00003331") } == true
                        if (mtaLike) android.util.Log.i("AmooLink", "MTASEND sees name=" + mtaName + " addr=" + result.device.address)
                        val hit = nameHit || mtaLike
                        if (hit && found.compareAndSet(null, result.device)) latch.countDown()
                    }
                }
                // 不加过滤:各家 MTA 实现用的 base UUID 不完全一致(0x3331 标准 base /
                // 008123456789),按名字挑设备最稳。
                scanner.startScan(
                    null,
                    android.bluetooth.le.ScanSettings.Builder()
                        .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    cb,
                )
                val ok = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { scanner.stopScan(cb) }
                val dev = found.get()
                android.util.Log.i("AmooLink", "MTASEND found=" + (dev?.address ?: "null") + " ok=" + ok)
                if (dev != null) {
                    val started = com.amoolink.companion.mta.MtaSenderService.start(
                        context, dev, true, name ?: dev.address, listOf(path),
                    )
                    android.util.Log.i("AmooLink", "MTASEND started=" + started)
                }
            } catch (e: Throwable) {
                android.util.Log.w("AmooLink", "MTASEND fail: " + e.message)
            }
        }.start()
    }

    private fun postMarkerNotification(context: Context, text: String) {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, ConnectionService.CHANNEL_TEST)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(11, builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AmooLink 自测")
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }
}
