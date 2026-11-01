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

    /**
     * 广播 serviceData 的 UUID(6 字节:2 字节 senderId + 4 字节 0)。
     *
     * 实测真机(小米 14 / 一加 Ace Pro)广播用的都是 **0x1B1E**,不是 CatShare 的 0x01FF。
     * 用错 UUID 的后果:小米的分享面板把我们标成「未识别的设备」,一加(OPPO)的接收端
     * 干脆不认这个发送方、只做 P2P 搜索却不入组。已改成原生值。
     */
    val ADV_DATA_5G_BRAND_UUID: UUID = UUID.fromString("00001b1e-0000-1000-8000-00805f9b34fb")

    /** 扫描响应 serviceData 的 UUID:27 字节 = 8零 + senderId2 + 设备名16 + 1(原生同为 0x2000)。 */
    val SCAN_RESP_UUID: UUID = UUID.fromString("00002000-0000-1000-8000-00805f9b34fb")

    /**
     * 设备短 ID:进程级 2 随机字节,%04x。
     * 注意必须 & 0xff——Byte.toInt() 会符号扩展,得到 8 位十六进制(如 ffffff9a),
     * 而协议里这个字段固定 4 位。
     */
    val SENDER_ID: String = run {
        val b = ByteArray(2)
        Random().nextBytes(b)
        String.format("%04x", ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff))
    }

    /** CatShare 兼容版本号(= 上游 VERSION_CODE 1.6 为 7);对端据此走兼容流程而非厂商私有流程。 */
    const val CAT_SHARE_VERSION = 7

    /** AES/CTR 固定 IV(MTA 协议既定,重写会无法互通)。 */
    const val CTR_IV = "0102030405060708"

    /** MAC 缺省占位(拿不到真实 p2p0 MAC 时;部分厂商原生端可能拒认)。 */
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    const val WSS_PATH = "/websocket"
    const val DOWNLOAD_PATH = "/download"
}
