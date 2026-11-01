package com.amoolink.companion.mta

import org.json.JSONObject

/** 本机能力描述:经 GATT 特征 9954 提供。字段名与 CatShare/厂商实现逐字对齐。 */
data class MtaDeviceInfo(
    val state: Int = 0,
    val key: String,
    val mac: String,
    val catShare: Int? = null,
) {
    fun toJsonBytes(): ByteArray = JSONObject()
        .put("state", state)
        .put("key", key)
        .put("mac", mac)
        .apply { if (catShare != null) put("catShare", catShare) }
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: JSONObject): MtaDeviceInfo = MtaDeviceInfo(
            state = json.optInt("state", 0),
            key = if (json.has("key") && !json.isNull("key")) json.getString("key") else "",
            mac = json.optString("mac", "02:00:00:00:00:00"),
            catShare = if (json.has("catShare")) json.optInt("catShare") else null,
        )
    }
}

/** P2P 组信息:发送方经 GATT 特征 9953 写给接收方(ECDH 有密钥时 ssid/psk/mac 加密)。 */
data class MtaP2pInfo(
    val id: String? = null,
    val ssid: String,
    val psk: String,
    val mac: String,
    val port: Int,
    val key: String? = null,
    val catShare: Int? = null,
) {
    fun toJsonBytes(): ByteArray = JSONObject()
        .apply {
            put("id", id)
            put("ssid", ssid)
            put("psk", psk)
            put("mac", mac)
            put("port", port)
            if (key != null) put("key", key)
            if (catShare != null) put("catShare", catShare)
        }
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: JSONObject): MtaP2pInfo = MtaP2pInfo(
            id = if (json.has("id") && !json.isNull("id")) json.optString("id") else null,
            ssid = json.getString("ssid"),
            psk = json.getString("psk"),
            mac = json.optString("mac", "02:00:00:00:00:00"),
            port = json.optInt("port", 0),
            key = if (json.has("key") && !json.isNull("key")) json.getString("key") else null,
            catShare = if (json.has("catShare")) json.optInt("catShare") else null,
        )
    }
}

/**
 * WS 控制面文本帧:`type:id:name?{json}`(action/ack;status 消息 id 固定 99)。
 */
data class MtaWsMessage(
    val type: String,
    val id: Int,
    val name: String,
    val payload: JSONObject? = null,
) {
    fun toText(newId: Int? = null): String {
        val sb = StringBuilder(type).append(':')
        sb.append(newId ?: id).append(':').append(name)
        if (payload != null) sb.append('?').append(payload)
        return sb.toString()
    }

    companion object {
        private val PATTERN = Regex("^(\\w+):(\\d+):(\\w+)(\\?(.*))?$")

        fun fromText(text: String): MtaWsMessage? {
            val m = PATTERN.matchEntire(text) ?: return null
            val jsonText = m.groupValues[5]
            val payload = if (jsonText.isNotEmpty()) runCatching { JSONObject(jsonText) }.getOrNull() else null
            return MtaWsMessage(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], payload)
        }

        fun status(taskId: Int, type: Int, reason: String): MtaWsMessage = MtaWsMessage(
            "action", STATUS_ID, "status",
            JSONObject().put("taskId", taskId).put("type", type).put("reason", reason),
        )

        /** status 消息在协议中的 id 固定 99。 */
        const val STATUS_ID = 99
    }
}
