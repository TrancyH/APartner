package com.amoolink.companion.mta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import com.amoolink.companion.FileStore
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream

/**
 * MTA 发送面:清理残留组 → createGroup(GO) → ktor Netty TLS server(/websocket + /download)
 * → 原生 GATT client 交付加密 P2pInfo → 等接收方连入/下载/确认。时序照搬 CatShare(MIT)。
 */
class MtaSenderService : Service() {

    companion object {
        const val TAG = "MtaSender"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_5G = "supports5g"
        const val EXTRA_DNAME = "dname"
        const val EXTRA_PATHS = "paths"
        private const val FG_ID = 111
        private const val CH = "mta_transfer"

        fun start(
            context: Context,
            device: BluetoothDevice,
            supports5Ghz: Boolean,
            deviceName: String,
            paths: List<String>,
        ): Boolean {
            if (!MtaState.tryLock()) return false
            context.startForegroundService(
                Intent(context, MtaSenderService::class.java)
                    .putExtra(EXTRA_DEVICE, device)
                    .putExtra(EXTRA_5G, supports5Ghz)
                    .putExtra(EXTRA_DNAME, deviceName)
                    .putExtra(EXTRA_PATHS, ArrayList(paths)),
            )
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CH, "互传传输", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(FG_ID, notification("正在准备互传…"))
        p2pManager = getSystemService(WifiP2pManager::class.java) as WifiP2pManager
        p2pChannel = p2pManager.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device = intent?.getParcelableExtra<BluetoothDevice>(EXTRA_DEVICE)
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS) ?: emptyList()
        if (device == null || paths.isEmpty()) {
            MtaState.unlock()
            stopSelf()
            return START_NOT_STICKY
        }
        val supports5G = intent.getBooleanExtra(EXTRA_5G, true)
        val deviceName = intent.getStringExtra(EXTRA_DNAME) ?: device.address
        scope.launch {
            var err: String? = null
            try {
                runSend(device, supports5G, deviceName, paths.map { File(it) })
            } catch (e: Throwable) {
                Log.e(TAG, "send failed", e)
                err = e.message ?: e.javaClass.simpleName
            } finally {
                MtaState.unlock()
                notifyText(FG_ID + 10, if (err == null) "互传发送完成 → $deviceName" else "互传发送失败:$err")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun progressNotification(deviceName: String, processed: Long, total: Long): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText("正在发送到 $deviceName:${processed / 1024} KB / ${total / 1024} KB")
            .setProgress(100, if (total <= 0) 0 else ((processed * 100) / total).toInt(), false)
            .setOngoing(true)
            .build()
    }

    private fun notify(id: Int, n: Notification) = nm.notify(id, n)

    private fun notifyText(id: Int, text: String) {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentText(text)
            .setAutoCancel(true)
        nm.notify(id, builder.build())
    }

    // ── 发送主流程 ─────────────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
    private suspend fun runSend(device: BluetoothDevice, supports5G: Boolean, deviceName: String, sources: List<File>) = kotlinx.coroutines.coroutineScope {
        // 文件夹 → 打包 ZIP(复用 FileStore 零依赖实现)
        var tempZip: File? = null
        val files: List<Pair<String, File>> = if (sources.any { it.isDirectory }) {
            val tmp = File(cacheDir, "APartner-MTA-${System.currentTimeMillis()}.zip")
            if (!FileStore.zipOf(sources, tmp)) throw RuntimeException("打包 ZIP 失败")
            tempZip = tmp
            listOf(tmp.name to tmp)
        } else {
            sources.map { it.name to it }
        }
        val totalSize = files.sumOf { it.second.length() }
        val taskId = kotlin.random.Random.nextInt()

        notify(FG_ID, progressNotification(deviceName, 0, totalSize))

        val statusFuture = CompletableDeferred<Pair<Int, String>>()
        val wsConnectFuture = CompletableDeferred<Unit>()
        val handshakeFuture = CompletableDeferred<Unit>()
        val transferStartFuture = CompletableDeferred<Unit>()
        val transferCompleteFuture = CompletableDeferred<Unit>()
        val wsCloseFuture = CompletableDeferred<Unit>()
        val taskIdStr = taskId.toString()
        val senderId = MtaConst.SENDER_ID

        // ── ktor Netty TLS server ──
        val keystore = MtaCert.build(applicationContext)
        val httpServer = embeddedServer(Netty, configure = {
            sslConnector(
                keyStore = keystore,
                keyAlias = MtaCert.ALIAS,
                keyStorePassword = { MtaCert.STORE_PASSWORD.toCharArray() },
                privateKeyPassword = { MtaCert.KEY_PASSWORD.toCharArray() },
            ) {
                port = 0
            }
        }) {
            install(WebSockets)
            routing {
                webSocket(MtaConst.WSS_PATH) {
                    wsConnectFuture.complete(Unit)
                    val vnFuture = CompletableDeferred<Unit>()
                    launch {
                        try {
                            for (frame in incoming) {
                                val msg = MtaWsMessage.fromText((frame as? Frame.Text)?.readText() ?: continue)
                                    ?: throw IllegalArgumentException("invalid frame")
                                when (msg.type) {
                                    "action" -> {
                                        if (msg.name == "status") {
                                            val p = msg.payload
                                            if (p != null) statusFuture.complete(p.optInt("type") to p.optString("reason"))
                                        }
                                        send(Frame.Text(MtaWsMessage("ack", msg.id, msg.name).toText()))
                                    }

                                    "ack" -> if (msg.name.equals("versionNegotiation", true)) {
                                        vnFuture.complete(Unit)
                                    }

                                    else -> Unit
                                }
                            }
                        } finally {
                            outgoing.close()
                        }
                    }
                    send(
                        Frame.Text(
                            MtaWsMessage(
                                "action", 0, "versionNegotiation",
                                JSONObject().put("version", 1).put("versions", listOf(1)),
                            ).toText(),
                        ),
                    )
                    vnFuture.await()
                    val taskJson = JSONObject()
                        .put("taskId", taskId)
                        .put("id", taskId)
                        .put("senderId", senderId)
                        .put("senderName", "APartner")
                        .put("fileName", files.first().first)
                        .put("mimeType", "*/*")
                        .put("fileCount", files.size)
                        .put("totalSize", totalSize)
                    send(Frame.Text(MtaWsMessage("action", 1, "sendRequest", taskJson).toText()))
                    handshakeFuture.complete(Unit)
                    wsCloseFuture.await()
                }

                get(MtaConst.DOWNLOAD_PATH) {
                    if (call.request.queryParameters["taskId"] != taskIdStr) {
                        call.respondText("task not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                        return@get
                    }
                    transferStartFuture.complete(Unit)
                    call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
                        ZipOutputStream(this).use { zo ->
                            var processed = 0L
                            var lastUpd = 0L
                            for ((i, f) in files.withIndex()) {
                                zo.putNextEntry(java.util.zip.ZipEntry("$i/${f.first}"))
                                f.second.inputStream().use { input ->
                                    val buffer = ByteArray(1024 * 1024 * 4)
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n == -1) break
                                        zo.write(buffer, 0, n)
                                        processed += n
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpd > 1_000) {
                                            notify(FG_ID, progressNotification(deviceName, processed, totalSize))
                                            lastUpd = now
                                        }
                                    }
                                }
                                zo.closeEntry()
                            }
                        }
                        transferCompleteFuture.complete(Unit)
                    }
                }
            }
        }.start(wait = false)
        val serverPort = httpServer.engine.resolvedConnectors().first().port

        try {
            // ── P2P GO ──
            MtaP2p.clearResidualGroup(p2pManager, p2pChannel)
            val ssid = "DIRECT-" + randomChars(8)
            val psk = randomChars(8)
            val config = WifiP2pConfig.Builder()
                .setGroupOperatingBand(
                    if (supports5G) WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                    else WifiP2pConfig.GROUP_OWNER_BAND_2GHZ,
                )
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .enablePersistentMode(false)
                .build()
            MtaP2p.createGroup(p2pManager, p2pChannel, config)
            val p2pMac = MtaState.mac(applicationContext)

            // ── GATT client 交付凭据 ──
            withTimeout(10_000) {
                bleDeliver(device, ssid, psk, p2pMac, serverPort)
            }

            // ── 等待接收方 ──
            val transferJob = async {
                withTimeout(10_000) { wsConnectFuture.await() }
                withTimeout(5_000) { handshakeFuture.await() }
                withTimeout(30_000) { transferStartFuture.await() }
                withTimeout(600_000) { transferCompleteFuture.await() }
            }
            val status = withTimeoutOrNull(5_000) { statusFuture.await() }
            if (status != null && status.first == 3) {
                throw RuntimeException("对方拒绝接收")
            }
            transferJob.await()
            Log.i(TAG, "发送完成 → $deviceName")
        } finally {
            runCatching { MtaP2p.removeGroup(p2pManager, p2pChannel) }
            tempZip?.delete()
            httpServer.stop(1_000, 1_000)
        }
    }

    // ── 原生 GATT client ───────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
    private suspend fun bleDeliver(
        device: BluetoothDevice,
        ssid: String,
        psk: String,
        p2pMac: String,
        serverPort: Int,
    ) = kotlinx.coroutines.coroutineScope {
        val connected = CompletableDeferred<android.bluetooth.BluetoothGatt>()
        val discovered = CompletableDeferred<android.bluetooth.BluetoothGatt>()
        val readDone = CompletableDeferred<ByteArray>()
        val writeDone = CompletableDeferred<Unit>()

        @Suppress("DEPRECATION")
        val callback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    connected.completeExceptionally(RuntimeException("BLE 连接断开"))
                    discovered.completeExceptionally(RuntimeException("BLE 连接断开"))
                }
            }

            override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                discovered.complete(if (status == 0) gatt else gatt)
            }

            override fun onCharacteristicRead(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                readDone.complete(value)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicWrite(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeDone.complete(Unit)
            }
        }

        val gatt = device.connectGatt(this@MtaSenderService, false, callback)
        try {
            connected.await()
            withTimeout(5_000) { discovered.await() }
            val svc = gatt.getService(MtaConst.SERVICE_UUID)
                ?: throw RuntimeException("BLE service not found")
            @Suppress("DEPRECATION")
            val statusChar = svc.getCharacteristic(MtaConst.CHAR_STATUS_UUID)
                ?: throw RuntimeException("status char not found")
            @Suppress("DEPRECATION")
            val p2pChar = svc.getCharacteristic(MtaConst.CHAR_P2P_UUID)
                ?: throw RuntimeException("p2p char not found")

            gatt.requestMtu(512)
            gatt.readCharacteristic(statusChar)
            val rdBytes = readDone.await()
            val rdInfo = MtaDeviceInfo.fromJson(JSONObject(String(rdBytes, Charsets.UTF_8)))

            val cipher = if (rdInfo.key.isNotEmpty()) MtaSecurity.deriveSessionKey(rdInfo.key) else null
            val p2pInfo = MtaP2pInfo(
                id = MtaConst.SENDER_ID,
                ssid = cipher?.encrypt(ssid) ?: ssid,
                psk = cipher?.encrypt(psk) ?: psk,
                mac = cipher?.encrypt(p2pMac) ?: p2pMac,
                port = serverPort,
                key = cipher?.let { MtaSecurity.getEncodedPublicKey() },
                catShare = null,
            )
            p2pChar.value = p2pInfo.toJsonBytes()
            gatt.writeCharacteristic(p2pChar)
            withTimeout(5_000) { writeDone.await() }
        } finally {
            runCatching { gatt.close() }
        }
    }

    private fun randomChars(n: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = java.util.Random()
        return buildString { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } }
    }
}
