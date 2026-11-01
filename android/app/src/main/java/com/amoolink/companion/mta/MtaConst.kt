package com.amoolink.companion.mta

import java.util.Random
import java.util.UUID

/**
 * 互传联盟(MTA)协议常量——与 CatShare(MIT)/各厂商系统互传逐字节对齐。
 * 原始定义见 moe.reimu.catshare.utils.BleUtils。
 */
object MtaConst {
    /** 接收方广播主 Service UUID(发送方 ScanFilter 过滤条件)。 */
    val ADV_SERVICE_UUID: UUID = UUID.fromString("00003331-0000-1000-8000-008123456789")

    /** GATT 主服务。 */
    val SERVICE_UUID: UUID = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")

    /** GATT 特征:读 → 本机 DeviceInfo JSON(公钥/MAC)。 */
    val CHAR_STATUS_UUID: UUID = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")

    /** GATT 特征:写 → P2pInfo JSON(ECDH 加密的 SSID/PSK/MAC + 端口 + 发送方公钥)。 */
    val CHAR_P2P_UUID: UUID = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")

    /** 广播 serviceData 的 UUID:16 字节大端里 [2]=支持5G [3]=品牌字节。 */
    val ADV_DATA_5G_BRAND_UUID: UUID = UUID.fromString("000001ff-0000-1000-8000-00805f9b34fb")

    /** 扫描响应 serviceData 的 UUID:27 字节 = 8零 + senderId2 + 设备名16 + 1。 */
    val SCAN_RESP_UUID: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")

    /** 设备短 ID:进程级 2 随机字节,%04x。 */
    val SENDER_ID: String = run {
        val b = ByteArray(2)
        Random().nextBytes(b)
        String.format("%04x", (b[0].toInt() shl 8) or b[1].toInt())
    }

    /** AES/CTR 固定 IV(MTA 协议既定,重写会无法互通)。 */
    const val CTR_IV = "0102030405060708"

    /** MAC 缺省占位(拿不到真实 p2p0 MAC 时;部分厂商原生端可能拒认)。 */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    const val WSS_PATH = "/websocket"
    const val DOWNLOAD_PATH = "/download"
}
