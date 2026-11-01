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
import kotlinx.coroutines.delay
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
@android.annotation.SuppressLint("MissingPermission", "NewApi")
class MtaSenderService : Service() {

    companion object {
        const val TAG = "MtaSender"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_5G = "supports5g"
        const val EXTRA_DNAME = "dname"
        const val EXTRA_PATHS = "paths"
        /** 实验开关:是否在 P2pInfo 里带 catShare(小米需要;一加自己发的没有)。 */
        /** 是否在 P2pInfo 里带 catShare:小米(HyperOS)靠它走兼容流程,必须带;
         *  实测一加不带也能走到同一阶段,所以保持开启。 */
        private const val CAT_SHARE_COMPAT = true
        /** 实验开关:发送期间是否广播。一加会因此把它当成「可发送目标」,先关掉验证。 */
        private const val SENDER_ADVERTISE = false
        /** 实验开关:P2pInfo 里是否带 freq(一加是带的;但我们带上后它反而不入组,做二分)。 */
        private const val SEND_FREQ = true
        /** 实验开关:让系统自己建组(group_capab 可能因此带上 IP 地址分配位)。 */
        private const val USE_SYSTEM_GROUP = false
        /** 实验:GO 侧客户端 IP 分配模式(0=IPv4 DHCP,1=IPv6 link-local)。 */
        private const val IP_MODE = WifiP2pConfig.GROUP_CLIENT_IP_PROVISIONING_MODE_IPV4_DHCP  // 框架不允许不带 SSID/PSK 建组(实测抛 IllegalStateException)
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
    /** 本次 P2P 组的工作频率(写进 P2pInfo.freq,OPPO/一加 入组要用)。 */
    @Volatile
    private var groupFreq: Int = 0

    /** 写完凭据后继续保持的 GATT 链路(见 bleDeliverOnce 注释)。 */
    private var keepAliveGatt: android.bluetooth.BluetoothGatt? = null

    private var senderAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private val senderAdvCallback = object : android.bluetooth.le.AdvertiseCallback() {}

    private lateinit var p2pManager: WifiP2pManager
    private lateinit var p2pChannel: WifiP2pManager.Channel
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CH, "互传传输", NotificationManager.IMPORTANCE_LOW),
        )
        try {
            startForeground(FG_ID, notification("正在准备互传…"))
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground 失败,发送取消", e)
            MtaState.unlock()
            stopSelf()
            return
        }
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
        val vnFuture = CompletableDeferred<Unit>()
        val taskIdStr = taskId.toString()
        val senderId = MtaConst.SENDER_ID

        // 把待发文件打成 ZIP 供 /download 取走(只打一次,对端可能重试)
        var cachedZip: File? = null
        fun zipOnce(): java.io.InputStream {
            val f = cachedZip ?: run {
                val out = File.createTempFile("mta", ".zip", cacheDir)
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(out))).use { zo ->
                    for ((i, entry) in files.withIndex()) {
                        // 图片/视频/压缩包这类已经是压缩格式的,不再 DEFLATE 一遍(省 CPU、更快)
                        zo.setLevel(FileStore.zipLevelFor(entry.first))
                        zo.putNextEntry(java.util.zip.ZipEntry(i.toString() + "/" + entry.first))
                        entry.second.inputStream().use { it.copyTo(zo, 262144) }
                        zo.closeEntry()
                    }
                }
                cachedZip = out
                out
            }
            return f.inputStream()
        }

        // ── JSSE WSS/TLS 服务端(不用 Netty:它的 ALPN 在 Android 上会把一加握手打断)──
        val keystore = MtaCert.build(applicationContext)
        lateinit var server: MtaWsServer
        server = MtaWsServer(
            keyStore = keystore,
            keyPass = MtaCert.KEY_PASSWORD.toCharArray(),
            handler = object : MtaWsServer.Handler {
                override fun onOpen() {
                    wsConnectFuture.complete(Unit)
                }

                override fun onText(text: String) {
                    val msg = MtaWsMessage.fromText(text) ?: return
                    when (msg.type) {
                        "action" -> {
                            if (msg.name == "status") {
                                val p = msg.payload
                                if (p != null) {
                                    statusFuture.complete(p.optInt("type") to p.optString("reason"))
                                }
                            }
                            server.sendText(MtaWsMessage("ack", msg.id, msg.name).toText())
                        }

                        "ack" -> if (msg.name.equals("versionNegotiation", true)) {
                            vnFuture.complete(Unit)
                        }

                        else -> Unit
                    }
                }

                override fun onClosed() {
                    wsCloseFuture.complete(Unit)
                }

                override fun onDownloadStart() {
                    transferStartFuture.complete(Unit)
                }

                override fun onDownloadProgress(bytes: Long) {
                    notify(FG_ID, progressNotification(deviceName, bytes, totalSize))
                }

                override fun onDownloadDone() {
                    transferCompleteFuture.complete(Unit)
                }

                override fun openZip(): java.io.InputStream = zipOnce()
            },
        )
        val serverPort = server.start()
        Log.i(TAG, "WSS server listening on " + serverPort)

        // WS 建立后的协商流程(原来是 ktor 路由里的那段)
        scope.launch {
            try {
                wsConnectFuture.await()
                server.sendText(
                    MtaWsMessage(
                        "action", 0, "versionNegotiation",
                        // 必须用 JSONArray:put(String, List) 会被 org.json 当成普通对象,
                        // 序列化成字符串 "[1]",对端解析 versions 时会报 JSONException。
                        JSONObject().put("version", 1).put("versions", org.json.JSONArray().put(1)),
                    ).toText(),
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
                server.sendText(MtaWsMessage("action", 1, "sendRequest", taskJson).toText())
                handshakeFuture.complete(Unit)
            } catch (_: Throwable) {
            }
        }

        try {
            // ── P2P GO ──
            MtaP2p.clearResidualGroup(p2pManager, p2pChannel)
            var ssid = "DIRECT-" + randomChars(16)   // 对齐小米的 SSID 形态(DIRECT- + 16)
            var psk = randomChars(8)
            // 频段交给系统协商(对齐上游:对端支持 5G 走 AUTO,否则 2.4G)。
            // 强制 2.4G 会让「STA 在 5G 的接收端」走 MCC(多信道并发)判断,
            // 小米 MiConnect 会因此不发起入组(isWifiP2p5gMcc)。
            val cfgBuilder = WifiP2pConfig.Builder()
                // 实测:一加(ColorOS 15)在 STA 处于 5GHz 时只会加入 5GHz 的 P2P 组
                // (小米发起的组是 5220,它入组成功;我们的组落在 2412 时它一声不吭)。
                // 所以优先 5GHz,只有对端明确不支持 5G 时才退回 2.4GHz。
                .setGroupOperatingBand(
                    if (supports5G) WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                    else WifiP2pConfig.GROUP_OWNER_BAND_2GHZ,
                )
                .enablePersistentMode(false)
                // 实验:GO 的客户端 IP 分配模式。默认是 IPv4 DHCP;换成 IPv6 link-local
                // 看是否能改变 GO 在 P2P IE 里声明的「IP 地址分配」能力位
                // (一加/OPPO 只肯加入带这一位的 GO)。
                .setGroupClientIpProvisioningMode(IP_MODE)
            // 自己指定 SSID/PSK 时,GO 的 group_capab 只有 0x9;实测小米(0x89)会带
            // 「IP 地址分配」能力位,而一加只肯加入带这位的 GO。所以试着让系统自己建组,
            // 再从 WifiP2pGroup 读回它生成的 SSID/PSK。
            if (!USE_SYSTEM_GROUP) {
                cfgBuilder.setNetworkName(ssid)
                cfgBuilder.setPassphrase(psk)
            }
            applyWfdInfo(cfgBuilder)
            val config = cfgBuilder.build()
            MtaP2p.createGroup(p2pManager, p2pChannel, config)
            if (USE_SYSTEM_GROUP) {
                val cred = MtaP2p.groupCredentials(p2pManager, p2pChannel)
                if (cred != null) {
                    ssid = cred.first
                    psk = cred.second
                    Log.i(TAG, "系统建组 ssid=" + ssid + " pskLen=" + psk.length)
                } else {
                    Log.w(TAG, "读不回系统组的 SSID/PSK,改用自建参数")
                    ssid = "DIRECT-" + randomChars(8)
                    psk = randomChars(8)
                    runCatching { MtaP2p.removeGroup(p2pManager, p2pChannel) }
                    val retry = WifiP2pConfig.Builder()
                        .setNetworkName(ssid).setPassphrase(psk)
                        .enablePersistentMode(false)
                        .build()
                    MtaP2p.createGroup(p2pManager, p2pChannel, retry)
                }
            }
            // 组建好之后再读:此时拿到的才是本次会话真正生效的 p2p0 地址
            val resolvedMac = MtaP2p.resolveLocalMac(p2pManager, p2pChannel)
            Log.i(TAG, "p2p0 MAC resolved=$resolvedMac")
            groupFreq = MtaP2p.groupFrequency(p2pManager, p2pChannel) ?: 0
            Log.i(TAG, "p2p0 group freq=$groupFreq")
            resolvedMac?.let { MtaState.rememberSessionMac(it) }
            val p2pMac = MtaState.mac(applicationContext)
            Log.i(TAG, "P2pInfo 使用 MAC=" + p2pMac)
            if (p2pMac == MtaConst.PLACEHOLDER_MAC) {
                // 实测(小米 14 / Android 16):P2pInfo 里的 mac 就是 GO 的 BSSID,
                // 对端会拿它做关联校验。给占位符时对端能入组但不会发起 WSS,
                // 我们这边只会白等两分钟报超时——不如立刻说清楚。
                throw RuntimeException(
                    "未设置 p2p0 MAC:互传联盟要求发送方提供真实 p2p0 地址," +
                        "请到首页「p2p0 MAC」填写(或连桌面让电脑读取)",
                )
            }

            // ── 发送方广播(让对端能把 BLE 设备与 P2P 设备对上)──
            if (SENDER_ADVERTISE) startSenderAdvertising()

            // ── GATT client 交付凭据 ──
            withTimeout(30_000) {
                bleDeliver(device, ssid, psk, p2pMac, serverPort)
            }

            // ── 等待接收方(实测:小米入组后需数秒才发起 WSS,窗口必须给足)──
            val transferJob = async {
                try {
                    withTimeout(120_000) { wsConnectFuture.await() }  // WS 连入
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    // 实测:一加(ColorOS 15)/OPPO 的接收端只会做到 saveP2pAddress + P2P 搜索,
                    // 不会主动入组;小米/华为等可以。这种情况让对方反向发送才是可行路径。
                    throw RuntimeException(
                        "对方没有发起连接。若是 OPPO/一加,请让对方在「互传」里选本机反向发送" +
                            "(这类机型目前不接受第三方 App 的推送)",
                    )
                }
                withTimeout(30_000) { handshakeFuture.await() }       // 版本协商+sendRequest
                // /download 开始之前对端也可能直接回 status(拒绝)。
                // 注意:对端收到 sendRequest 后是「弹给用户点接收」的(小米是灵动岛提示),
                // 120 秒是留给人反应的时间;期间提示语要写清楚。
                notify(FG_ID, notification("等待 " + deviceName + " 确认接收…"))
                withTimeout(120_000) {
                    while (!transferStartFuture.isCompleted && !statusFuture.isCompleted) delay(200)
                }
                if (!transferStartFuture.isCompleted && statusFuture.isCompleted) {
                    val (type, reason) = statusFuture.getCompleted()
                    throw RuntimeException(
                        when (type) {
                            3 -> "对方拒绝接收(${reason.ifEmpty { "user refuse" }})"
                            else -> "对方中止传输(type=$type ${reason})"
                        },
                    )
                }
                transferCompleteFuture.await()
                // 数据发完了,但对端(一加/OPPO)此时还在弹「是否接收」的确认框:
                // 实测它会先下载、再等用户点接收。组一拆它就报「接收失败」,
                // 所以这里必须继续保持组和 WS,直到它回 status 或超时。
                notify(FG_ID, notification("已发送,等待 " + deviceName + " 确认接收…"))
                withTimeoutOrNull(60_000) { statusFuture.await() }
            }
            transferJob.await()
            Log.i(TAG, "发送完成 → $deviceName")
        } finally {
            stopSenderAdvertising()
            runCatching { keepAliveGatt?.close() }
            keepAliveGatt = null
            runCatching { MtaP2p.removeGroup(p2pManager, p2pChannel) }
            tempZip?.delete()
            cachedZip?.delete()
            server.stop()
        }
    }

    /**
     * 给 GO 的 P2P 配置挂上 Wi-Fi Display 能力(隐藏 API,反射)。
     *
     * 实测对比:小米发起组时,一加看到的条目是
     *   P2P-DEVICE-FOUND ... group_capab=0x89 wfd_dev_info=0x00101c440032
     * 我们的是 group_capab=0x9 且没有 wfd —— 一加(OPPO)据此不发起入组。
     * WFD 公开 API 只有 WifiP2pWfdInfo,挂到 config 上的 Builder#setWfdInfo 是 @hide;
     * 实测 Android 16(API 36)上这个方法已经被移除(NoSuchMethodException),
     * 所以下面这段在 16 上必然失败——保留是为了在还有该隐藏 API 的机型上生效。
     */
    private fun applyWfdInfo(builder: WifiP2pConfig.Builder) {
        try {
            val wfd = android.net.wifi.p2p.WifiP2pWfdInfo()
            wfd.isEnabled = true
            wfd.deviceType = android.net.wifi.p2p.WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK
            wfd.setSessionAvailable(false)
            val m = builder.javaClass.getMethod(
                "setWfdInfo", android.net.wifi.p2p.WifiP2pWfdInfo::class.java,
            )
            m.invoke(builder, wfd)
            Log.i(TAG, "GO 已挂 WFD 能力")
        } catch (e: Throwable) {
            Log.w(TAG, "挂 WFD 失败: " + e.javaClass.simpleName + " " + e.message)
        }
    }

    // ── 发送方 BLE 广播 ────────────────────────────────────────────
    /**
     * 发送方也广播自己的互传身份(service UUID 3331 + senderId + 设备名)。
     *
     * 实测(一加 Ace Pro / ColorOS 15):只做 GATT 写入时,对端能收到凭据、也会在
     * P2P 搜索里找到我们的 GO,但日志里一路 "bluetoothDevice.getName() is null",
     * 之后不再发起入组。原生互传发送端在发送期间同样保持广播。
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startSenderAdvertising() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val advertiser = btManager.adapter?.bluetoothLeAdvertiser ?: return
            senderAdvertiser = advertiser
            val idBytes = MtaConst.SENDER_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val advData = android.bluetooth.le.AdvertiseData.Builder().apply {
                addServiceUuid(android.os.ParcelUuid(MtaConst.ADV_SERVICE_UUID))
                addServiceData(
                    android.os.ParcelUuid(MtaConst.ADV_DATA_5G_BRAND_UUID),
                    byteArrayOf(idBytes[0], idBytes[1], 0x00, 0x00, 0x00, 0x00),
                )
                setIncludeTxPowerLevel(false)
                setIncludeDeviceName(false)
            }.build()
            val nameBytes = "APartner".toByteArray(Charsets.UTF_8)
            val resp = ByteArray(27).apply {
                System.arraycopy(idBytes, 0, this, 8, 2)
                System.arraycopy(nameBytes, 0, this, 10, minOf(nameBytes.size, 16))
                this[26] = 0x01
            }
            val scanResponse = android.bluetooth.le.AdvertiseData.Builder().apply {
                addServiceData(android.os.ParcelUuid(MtaConst.SCAN_RESP_UUID), resp)
                setIncludeTxPowerLevel(false)
                setIncludeDeviceName(false)
            }.build()
            val settings = android.bluetooth.le.AdvertiseSettings.Builder()
                .setAdvertiseMode(android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build()
            advertiser.startAdvertising(settings, advData, scanResponse, senderAdvCallback)
            Log.i(TAG, "发送方广播已开启")
        } catch (e: Exception) {
            Log.w(TAG, "发送方广播开启失败: " + e.message)
        }
    }

    private fun stopSenderAdvertising() {
        try {
            senderAdvertiser?.stopAdvertising(senderAdvCallback)
        } catch (_: Exception) {
        }
        senderAdvertiser = null
    }

    // ── 原生 GATT client ───────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission", "NewApi")
    /** GATT 凭据交付(带 3 次重试:status=133/超时等瞬时错误常见)。 */
    private suspend fun bleDeliver(
        device: BluetoothDevice,
        ssid: String,
        psk: String,
        p2pMac: String,
        serverPort: Int,
    ) {
        var lastErr: Throwable = RuntimeException("未尝试")
        repeat(3) { attempt ->
            if (attempt > 0) {
                Log.i(TAG, "BLE retry attempt=$attempt after: $lastErr")
                delay(600L * attempt)
            }
            try {
                bleDeliverOnce(device, ssid, psk, p2pMac, serverPort)
                return
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw e
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        throw lastErr
    }


    private suspend fun bleDeliverOnce(
        device: android.bluetooth.BluetoothDevice,
        ssid: String,
        psk: String,
        p2pMac: String,
        serverPort: Int,
    ) = kotlinx.coroutines.coroutineScope {
        val connected = CompletableDeferred<Unit>()
        val discovered = CompletableDeferred<android.bluetooth.BluetoothGatt>()
        val readDone = CompletableDeferred<ByteArray>()
        val writeDone = CompletableDeferred<Unit>()
        val mtuDone = CompletableDeferred<Unit>()

        @Suppress("DEPRECATION")
        val callback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "BLE onConnectionStateChange status=$status newState=$newState")
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "BLE connected, discoverServices")
                    connected.complete(Unit)
                    gatt.discoverServices()
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "BLE disconnected(status=$status)")
                    val ex = RuntimeException("BLE 连接断开(status=$status)")
                    connected.completeExceptionally(ex)
                    discovered.completeExceptionally(ex)
                    readDone.completeExceptionally(ex)
                }
            }

            override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                Log.i(TAG, "BLE onServicesDiscovered status=$status services=${gatt.services.joinToString { it.uuid.toString() }}")
                discovered.complete(gatt)
            }

            override fun onMtuChanged(gatt: android.bluetooth.BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "BLE onMtuChanged mtu=$mtu status=$status")
                mtuDone.complete(Unit)
            }

            // API 33+ 新签名(compileSdk 34 起旧签名不再回调——这就是"读无响应"的根因)
            override fun onCharacteristicRead(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                Log.i(TAG, "BLE [33+] onCharacteristicRead status=$status len=${value.size}")
                readDone.complete(value)
            }

            override fun onCharacteristicWrite(
                gatt: android.bluetooth.BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                status: Int,
            ) {
                Log.i(TAG, "BLE [33+] onCharacteristicWrite status=$status")
                writeDone.complete(Unit)
            }
        }

        Log.i(TAG, "BLE connectGatt → ${device.address}")
        val gatt = device.connectGatt(this@MtaSenderService, false, callback)
        try {
            connected.await()
            // 注意:不主动 createBond()——协议不要求加密链路(上游 CatShare 同样不配对),
            // 而 createBond 会在对端弹出系统配对框并阻塞十余秒。
            withTimeout(20_000) { discovered.await() }
            val svc = gatt.getService(MtaConst.SERVICE_UUID)
            Log.i(TAG, "BLE service 9955 found=${svc != null}, all=${gatt.services.joinToString { it.uuid.toString() }}")
            if (svc == null) throw RuntimeException("BLE service not found")
            @Suppress("DEPRECATION")
            val statusChar = svc.getCharacteristic(MtaConst.CHAR_STATUS_UUID)
                ?: throw RuntimeException("status char not found")
            @Suppress("DEPRECATION")
            val p2pChar = svc.getCharacteristic(MtaConst.CHAR_P2P_UUID)
                ?: throw RuntimeException("p2p char not found")

            gatt.requestMtu(512)
            withTimeoutOrNull(3_000) { mtuDone.await() }
            var rdBytes: ByteArray? = null
            for (attempt in 0 until 2) {
                if (attempt > 0) delay(300)
                val rr = gatt.readCharacteristic(statusChar)
                Log.i(TAG, "BLE [33+] readCharacteristic ret=$rr (attempt=$attempt)")
                rdBytes = withTimeoutOrNull(5_000) { readDone.await() }
                if (rdBytes != null) break
                Log.w(TAG, "read 9954 attempt=$attempt timed out")
            }
            val rd = rdBytes ?: throw RuntimeException("读 9954 无响应(链路未加密或已断开)")
            val rdInfo = MtaDeviceInfo.fromJson(JSONObject(String(rd!!, Charsets.UTF_8)))

            val cipher = if (rdInfo.key.isNotEmpty()) MtaSecurity.deriveSessionKey(rdInfo.key) else null
            val p2pInfo = MtaP2pInfo(
                id = MtaConst.SENDER_ID,
                ssid = cipher?.encrypt(ssid) ?: ssid,
                psk = cipher?.encrypt(psk) ?: psk,
                mac = cipher?.encrypt(p2pMac) ?: p2pMac,
                freq = groupFreq.takeIf { SEND_FREQ && it > 0 },
                port = serverPort,
                key = cipher?.let { MtaSecurity.getEncodedPublicKey() },
                // 对端(CatShare 兼容实现)靠此字段识别兼容流程;缺失则走厂商私有流程并
                // 以"接收端P2P连接超时"取消(实测 HyperOS 16)。取 CatShare v1.6 的 VERSION_CODE
                catShare = if (CAT_SHARE_COMPAT) MtaConst.CAT_SHARE_VERSION else null,
            )
            val payload = p2pInfo.toJsonBytes()
            var wr = 0
            for (wAttempt in 0 until 3) {
                if (wAttempt > 0) delay(400L * wAttempt)
                wr = gatt.writeCharacteristic(p2pChar, payload, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                Log.i(TAG, "BLE [33+] writeCharacteristic ret=$wr len=${payload.size} (attempt=$wAttempt)")
                if (wr == android.bluetooth.BluetoothStatusCodes.SUCCESS) break
            }
            if (wr != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                throw RuntimeException("写 9953 失败 ret=$wr")
            }
            withTimeout(8_000) { writeDone.await() }
            Log.i(TAG, "BLE 凭据交付完成,等待接收方连入 WSS …")
            // 实测:Mi Share 发送时 BLE 链路会在写完后继续保持数秒,而一加/OPPO 的接收
            // 确认流程依赖这条链路(我们立刻断开时它会入组后 0.5s 自退)。
            keepAliveGatt = gatt
            return@coroutineScope
        } finally {
            // 实测:保持 GATT 不关对 OPPO/一加没有帮助(它退组的真正原因是拿不到 IP),
            // 所以仍按上游时序在写完后立即断开,避免长期占着对端的连接槽。
            runCatching { gatt.close() }
        }
    }

    private fun randomChars(n: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = java.util.Random()
        return buildString { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } }
    }
}
