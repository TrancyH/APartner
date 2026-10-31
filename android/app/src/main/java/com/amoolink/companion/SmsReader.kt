package com.amoolink.companion

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** M5-4:短信读取(content://sms 分页,时间倒序;仅在桌面端请求时读,不后台同步)。 */
object SmsReader {
    @SuppressLint("Recycle")
    fun query(context: Context, max: Int): JSONArray {
        val items = JSONArray()
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return items
        }
        val cr = context.contentResolver
        cr.query(
            Uri.parse("content://sms"),
            arrayOf("_id", "address", "person", "date", "type", "body"),
            null,
            null,
            "date DESC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < max) {
                items.put(JSONObject().apply {
                    put("id", cursor.getLong(0))
                    put("address", cursor.getString(1) ?: "")
                    put("dateMs", cursor.getLong(3))
                    put("type", cursor.getInt(4))
                    put("body", cursor.getString(5) ?: "")
                })
                count++
            }
        }
        return items
    }
}
