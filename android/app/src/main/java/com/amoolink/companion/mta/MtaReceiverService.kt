package com.amoolink.companion.mta

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.bluetooth.le.AdvertiseData
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.consumeEach
import android.net.wifi.p2p.WifiP2pConfig
import io.ktor.websocket.readText
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext

/**
 * MTA 接收面:BLE 广播 + GATT server(9954 读/9953 写)+ WiFi P2P GC +
 * WSS 控制面 + HTTPS 下载 ZIP 落盘 `Download/APartner/MTA/`。
 * 协议时序照搬 CatShare P2pReceiverService/GattServerService(MIT)。
 */
@android.annotation.SuppressLint("MissingPermission")
class MtaReceiverService : Service() {

    companion object {
        const val TAG = "MtaReceiver"
        const val EXTRA_P2P_INFO = "p2p_info"
        const val EXTRA_ACCEPT = "accept"
        const val ACTION_STOP = "com.amoolink.companion.mta.STOP_RECEIVE"
        private const val FG_ID = 101
        private const val ASK_ID = 102
        private const val PROGRESS_ID = 103
        private const val CH = "mta_transfer"

        /** 首页「互传联盟接收」开关。 */
        fun setEnabled(context: Context, enabled: Boolean) {
            if (enabled) {
                context.startForegroundService(Intent(context, MtaReceiverService::class.java))
            } else {
                context.startService(Intent(context, MtaReceiverService::class.java).setAction(ACTION_STOP))
                context.stopService(Intent(context, MtaReceiverService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel
    private var gattServer: android.bluetooth.BluetoothGattServer? = null
    private var gattCallback: GattServerCallback? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private val receivers = mutableListOf<BroadcastReceiver>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "互传传输", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(FG_ID, notification("互传接收已开启 · 等待附近的设备"))

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || adapter.bluetoothLeAdvertiser == null) {
            Log.w(TAG, "BLE advertising 不可用,接收关闭")
            stopSelf()
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        gattCallback = GattServerCallback()
        gattServer = btManager.openGattServer(this, gattCallback)
        gattServer?.addService(gattCallback!!.buildService())
        startAdvertising()

        p2pManager = getSystemService(WifiP2pManager::class.java) as WifiP2pManager
        p2pChannel = p2pManager.initialize(this, mainLooper, null)

        // P2P 连接结果 → LinkBus
        val p2pRx: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                @Suppress("DEPRECATION")
                val info = i.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (info != null && info.groupFormed) MtaLinkBus.feedP2p(info)
            }
        }
        registerReceiver(p2pRx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
        receivers.add(p2pRx)

        // 接受/拒绝(通知按钮经 startService 走 EXTRA_ACCEPT;此处兜底广播)
        val answerRx: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.hasExtra(EXTRA_ACCEPT) == true) MtaLinkBus.userAccept = i.getBooleanExtra(EXTRA_ACCEPT, false)
            }
        }
        registerReceiver(answerRx, IntentFilter(ACTION_STOP + ".answer"), Context.RECEIVER_NOT_EXPORTED)
        receivers.add(answerRx)

        val stopRx: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = stopSelf()
        }
        registerReceiver(stopRx, IntentFilter(ACTION_STOP), Context.RECEIVER_NOT_EXPORTED)
        receivers.add(stopRx)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.hasExtra(EXTRA_ACCEPT)) {
            MtaLinkBus.userAccept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
            return START_NOT_STICKY
        }
        val bytes = intent.getByteArrayExtra(EXTRA_P2P_INFO) ?: return START_NOT_STICKY
        val p2pInfo = try {
            MtaP2pInfo.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "P2pInfo 解析失败", e)
            return START_NOT_STICKY
        }
        if (!MtaState.tryLock()) {
            Log.w(TAG, "收发互斥:已有传输进行,忽略")
            return START_NOT_STICKY
        }
        scope.launch {
            var err: String? = null
            try {
                runReceive(p2pInfo)
            } catch (e: Throwable) {
                Log.e(TAG, "接收失败", e)
                err = e.message ?: e.javaClass.simpleName
            } finally {
                MtaState.unlock()
                notify(PROGRESS_ID, "互传接收" + (if (err == null) "完成" else "失败:$err"))
                startAdvertising() // 继续可被发现
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        gattServer?.close()
        receivers.forEach { runCatching { unregisterReceiver(it) } }
        receivers.clear()
        scope.cancel()
        super.onDestroy()
    }

    // ── BLE 广播 + GATT server ─────────────────────────────────────

    private fun startAdvertising() {
        try {
            val idBytes = MtaConst.SENDER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val advData = AdvertiseData.Builder().apply {
                addServiceUuid(android.os.ParcelUuid(MtaConst.ADV_SERVICE_UUID))
                addServiceData(
                    android.os.ParcelUuid(MtaConst.ADV_DATA_5G_BRAND_UUID),
                    byteArrayOf(idBytes[0], idBytes[1], 0x01, 0xff.toByte(), 0x00, 0x00),
                )
                setIncludeTxPowerLevel(false)
                setIncludeDeviceName(false)
            }.build()
            val nameBytes = deviceNameBytes("AmooLink")
            val resp = ByteArray(27).apply {
                System.arraycopy(idBytes, 0, this, 8, 2)
                System.arraycopy(nameBytes, 0, this, 10, nameBytes.size)
                this[26] = 0x01
            }
            val scanResponse = AdvertiseData.Builder().apply {
                addServiceData(android.os.ParcelUuid(MtaConst.SCAN_RESP_UUID), resp)
                setIncludeTxPowerLevel(false)
                setIncludeDeviceName(false)
            }.build()
            val settings = android.bluetooth.le.AdvertiseSettings.Builder()
                .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build()
            advertiser?.startAdvertising(
                settings, advData, scanResponse,
                object : android.bluetooth.le.AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: android.bluetooth.le.AdvertiseSettings?) {
                        Log.i(TAG, "advertising started")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.e(TAG, "advertising failed errorCode=$errorCode")
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "advertising failed", e)
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(object : android.bluetooth.le.AdvertiseCallback() {})
        } catch (_: Exception) {
        }
        advertiser = null
    }

    private fun deviceNameBytes(raw: String): ByteArray {
        var bytes = raw.toByteArray(Charsets.UTF_8)
        var truncated = false
        while (bytes.size > 16) {
            bytes = bytes.copyOfRange(0, bytes.lastIndex)
            truncated = true
        }
        if (truncated && bytes.isNotEmpty()) bytes[bytes.lastIndex] = '\t'.code.toByte()
        return bytes
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun notify(id: Int, text: String) {
        getSystemService(NotificationManager::class.java).notify(id, notification(text))
    }

    private fun askNotification(taskId: Int, senderName: String, fileName: String, fileCount: Int, totalSize: Long): Notification {
        fun pi(accept: Boolean): PendingIntent = PendingIntent.getService(
            this,
            if (accept) taskId else taskId + 1,
            Intent(this, MtaReceiverService::class.java).putExtra(EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("$senderName 要发送 $fileCount 个文件")
            .setContentText("$fileName 等,共 ${totalSize / 1024} KB")
            .addAction(android.R.drawable.ic_input_add, "接受", pi(true))
            .addAction(android.R.drawable.ic_delete, "拒绝", pi(false))
            .setOngoing(true)
            .build()
    }

    // ── GATT server ────────────────────────────────────────────────

    private inner class GattServerCallback : android.bluetooth.BluetoothGattServerCallback() {
        private val writeBuffers = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, Int>>()

        fun buildService(): android.bluetooth.BluetoothGattService {
            @Suppress("DEPRECATION")
            fun characteristic(uuid: java.util.UUID) = android.bluetooth.BluetoothGattCharacteristic(
                uuid,
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ or
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE,
                android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ or
                    android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            @Suppress("DEPRECATION")
            val svc = android.bluetooth.BluetoothGattService(
                MtaConst.SERVICE_UUID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            svc.addCharacteristic(characteristic(MtaConst.CHAR_STATUS_UUID))
            svc.addCharacteristic(characteristic(MtaConst.CHAR_P2P_UUID))
            return svc
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != MtaConst.CHAR_STATUS_UUID) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val bytes = MtaDeviceInfo(
                state = 0,
                key = MtaSecurity.getEncodedPublicKey(),
                mac = MtaState.mac(this@MtaReceiverService),
                catShare = null,
            ).toJsonBytes()
            val from = offset.coerceAtMost(bytes.size)
            gattServer?.sendResponse(
                device, requestId,
                android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset,
                bytes.copyOfRange(from, bytes.size),
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid != MtaConst.CHAR_P2P_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }
            var complete: ByteArray? = null
            if (preparedWrite) {
                val old = writeBuffers[device.address]?.first ?: ByteArray(1024)
                val end = (offset + value.size).coerceAtMost(old.size)
                if (offset < old.size) System.arraycopy(value, 0, old, offset, end - offset)
                writeBuffers[device.address] = old to end
            } else {
                complete = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            complete?.let { handleP2pInfo(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onExecuteWrite(device: android.bluetooth.BluetoothDevice, requestId: Int, execute: Boolean) {
            val entry = writeBuffers.remove(device.address)
            gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            entry?.let { (buf, len) -> handleP2pInfo(buf.copyOf(len)) }
        }

        private fun handleP2pInfo(payload: ByteArray) {
            val plain = try {
                val info = MtaP2pInfo.fromJson(JSONObject(String(payload, Charsets.UTF_8)))
                if (info.key != null) {
                    // ECDH 解密 ssid/psk/mac;id 覆写为本机 senderId(对齐上游 GattServerService)
                    val cipher = MtaSecurity.deriveSessionKey(info.key!!)
                    MtaP2pInfo(
                        id = MtaConst.SENDER_ID,
                        ssid = cipher.decrypt(info.ssid),
                        psk = cipher.decrypt(info.psk),
                        mac = cipher.decrypt(info.mac),
                        port = info.port,
                        key = null,
                    )
                } else info
            } catch (e: Exception) {
                Log.e(TAG, "P2pInfo 解析失败", e)
                return
            }
            Log.i(TAG, "收到 P2pInfo ssid=${plain.ssid} port=${plain.port}")
            startService(
                Intent(this@MtaReceiverService, MtaReceiverService::class.java)
                    .putExtra(EXTRA_P2P_INFO, plain.toJsonBytes()),
            )
        }
    }

    // ── 接收主流程 ─────────────────────────────────────────────────

    @android.annotation.SuppressLint("NewApi", "MissingPermission")
    private suspend fun runReceive(p2pInfo: MtaP2pInfo) = kotlinx.coroutines.coroutineScope {
        val nm = getSystemService(NotificationManager::class.java)
        MtaLinkBus.freshAccept()
        val linkDeferred = CompletableDeferred<WifiP2pInfo>()
        val linkRx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                @Suppress("DEPRECATION")
                val info = i.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (info != null && info.groupFormed) MtaLinkBus.feedP2p(info)
                if (info != null && info.groupFormed) linkDeferred.complete(info)
            }
        }.also {
            registerReceiver(it, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
        }

        val tm = TrustAllTrustManager()
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(tm), SecureRandom())
        val client = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    sslSocketFactory(sslContext.socketFactory, tm)
                    hostnameVerifier { _, _ -> true }
                    connectTimeout(3, TimeUnit.SECONDS)
                }
            }
        }

        var transferOk = false
        try {
            MtaP2p.clearResidualGroup(p2pManager, p2pChannel)
            p2pManager.connect(
                p2pChannel,
                WifiP2pConfig.Builder().setNetworkName(p2pInfo.ssid).setPassphrase(p2pInfo.psk).build(),
                null,
            )
            val wifiInfo = withTimeout(10_000) { linkDeferred.await() }
            val hostPort = "${wifiInfo.groupOwnerAddress.hostAddress}:${p2pInfo.port}"
            Log.i(TAG, "P2P connected GO=$hostPort")

            val ws = client.webSocketSession("wss://$hostPort${MtaConst.WSS_PATH}")

            var sendRequestPayload: JSONObject? = null
            var transferOkLocal = false

            val readerJob = launch {
                ws.incoming.consumeEach { frame ->
                    val text = (frame as? io.ktor.websocket.Frame.Text)?.readText() ?: return@consumeEach
                    val msg = MtaWsMessage.fromText(text) ?: return@consumeEach
                    when (msg.type) {
                        "action" -> when (msg.name.lowercase()) {
                            "versionnegotiation" -> ws.send(Frame.Text(MtaWsMessage("ack", msg.id, msg.name, JSONObject().put("version", 1).put("threadLimit", 5)).toText()))

                            "sendrequest" -> {
                                ws.send(Frame.Text(MtaWsMessage("ack", msg.id, msg.name).toText()))
                                sendRequestPayload = msg.payload
                            }

                            else -> Unit
                        }

                        else -> Unit
                    }
                }
            }

            val payload = withTimeoutOrNull(5_000) {
                while (sendRequestPayload == null) delay(100)
                sendRequestPayload!!
            } ?: throw RuntimeException("等待发送请求超时")

            val taskIdStr = payload.optString("taskId", payload.optString("id", "0"))
            val senderName = payload.optString("senderName", "设备")
            val fileName = payload.optString("fileName", "")
            val totalSize = payload.optLong("totalSize", 0)
            val fileCount = payload.optInt("fileCount", 1)

            // 用户确认:前台=应用内确认面板(20s),后台=通知(10s);超时=拒绝
            MtaLinkBus.userAccept = null
            val usePanel = MtaState.appInForeground && MtaUiBridge.panelEmitter != null
            var answer: Boolean? = null
            if (usePanel) {
                val accepted = CompletableDeferred<Boolean>()
                val emitted = MtaUiBridge.emit(
                    MtaUiBridge.IncomingRequest(senderName, fileName, fileCount, totalSize) { accept ->
                        accepted.complete(accept)
                    },
                )
                if (emitted) {
                    answer = withTimeoutOrNull(60_000) { accepted.await() }
                } else {
                    nm.notify(ASK_ID, askNotification(taskIdStr.toIntOrNull() ?: 0, senderName, fileName, fileCount, totalSize))
                    answer = withTimeoutOrNull(10_000) {
                        while (MtaLinkBus.userAccept == null) delay(150)
                        MtaLinkBus.userAccept
                    }
                }
            } else {
                nm.notify(ASK_ID, askNotification(taskIdStr.toIntOrNull() ?: 0, senderName, fileName, fileCount, totalSize))
                answer = withTimeoutOrNull(10_000) {
                    while (MtaLinkBus.userAccept == null) delay(150)
                    MtaLinkBus.userAccept
                }
            }
            nm.cancel(ASK_ID)
            if (answer != true) {
                ws.send(Frame.Text(MtaWsMessage.status(taskIdStr.toIntOrNull() ?: 0, 3, "user refuse").toText()))
                throw RuntimeException("用户拒绝接收")
            }
            nm.notify(PROGRESS_ID, notification("正在接收:$fileName"))

            // 数据面:HTTPS GET → ZIP 流 → MediaStore(OkHttp 直读)
            val okClient = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, TrustAllTrustManager())
                .hostnameVerifier { _, _ -> true }
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://$hostPort${MtaConst.DOWNLOAD_PATH}?taskId=$taskIdStr")
                .build()
            okClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("下载失败 HTTP ${resp.code}")
                val input = resp.body!!.byteStream()
                ZipInputStream(input).use { zis ->
                    var processed = 0L
                    var lastUpd = 0L
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        saveToMediaStore(zis, entry)
                        processed += entry.size.coerceAtLeast(0)
                        zis.closeEntry()
                        val now = System.currentTimeMillis()
                        if (now - lastUpd > 1_000) {
                            nm.notify(PROGRESS_ID, notification("正在接收:$fileName(${processed / 1024} KB)"))
                            lastUpd = now
                        }
                    }
                }
            }

            ws.send(Frame.Text(MtaWsMessage.status(taskIdStr.toIntOrNull() ?: 0, 1, "ok").toText()))
            transferOkLocal = true
            delay(1_000)
            readerJob.cancel()
            transferOk = transferOkLocal
        } finally {
            try {
                unregisterReceiver(linkRx)
            } catch (_: Exception) {
            }
            client.close()
            runCatching { MtaP2p.removeGroup(p2pManager, p2pChannel) }
            runCatching { MtaP2p.cancelConnect(p2pManager, p2pChannel) }
        }
        if (!transferOk) throw RuntimeException("传输未完成")
    }

    private fun saveToMediaStore(input: java.io.InputStream, entry: java.util.zip.ZipEntry) {
        val name = java.io.File(entry.name).name.ifEmpty { "file_${System.currentTimeMillis()}" }
        val ext = name.substringAfterLast('.', "")
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/APartner/MTA")
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("MediaStore 写入被拒绝")
            resolver.openOutputStream(uri)?.use { out ->
                BufferedOutputStream(out).use { bos ->
                    val buf = ByteArray(1024 * 1024 * 4)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        bos.write(buf, 0, n)
                    }
                }
            } ?: throw RuntimeException("MediaStore 输出流打开失败")
        } else {
            // API 26-28:经典外部存储直写(已授予 WRITE_EXTERNAL_STORAGE / 全文件访问)
            val dir = java.io.File(
                Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS + "/APartner/MTA",
            )
            dir.mkdirs()
            val out = java.io.FileOutputStream(java.io.File(dir, name))
            out.use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    val buf = ByteArray(1024 * 1024 * 4)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        bos.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** P2P 连接信息总线(广播 ↔ 接收协程/确认状态)。 */
    object MtaLinkBus {
        @Volatile
        var userAccept: Boolean? = null

        private val p2p = AtomicReference<CompletableDeferred<WifiP2pInfo>?>(null)

        fun feedP2p(info: WifiP2pInfo) {
            p2p.get()?.complete(info)
        }

        fun freshAccept() {
            userAccept = null
        }
    }
}

/** 全信任 X509TrustManager(MTA 链路安全性依赖 WiFi 直连链路 + ECDH 凭据加密,与上游一致)。 */
class TrustAllTrustManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
}
