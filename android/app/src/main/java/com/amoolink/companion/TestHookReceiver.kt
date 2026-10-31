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

    private fun echoClipboard(context: Context) {
        val clip = ConnectionService.instance?.readClipboard() ?: "(不可读)"
        postMarkerNotification(context, "AMOOCLIP:$clip")
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
