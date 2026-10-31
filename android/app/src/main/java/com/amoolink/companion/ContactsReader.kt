package com.amoolink.companion

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/** M5-4:通讯录读取(聚合联系人 + 号码列表;桌面端按需查询并可导出 vCard)。 */
object ContactsReader {
    @SuppressLint("Recycle")
    fun query(context: Context, max: Int): JSONArray {
        val items = JSONArray()
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return items
        }
        val cr = context.contentResolver
        val order = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            order,
        )?.use { cursor ->
            val byContact = LinkedHashMap<Long, JSONObject>()
            while (cursor.moveToNext() && byContact.size < max) {
                val contactId = cursor.getLong(0)
                val name = cursor.getString(1) ?: "未命名"
                val number = cursor.getString(2) ?: continue
                val item = byContact.getOrPut(contactId) {
                    JSONObject().apply {
                        put("id", contactId)
                        put("name", name)
                        put("phones", JSONArray())
                    }
                }
                item.getJSONArray("phones").put(number)
            }
            byContact.values.forEach { items.put(it) }
        }
        return items
    }

    /** 来电号码 → 联系人姓名(PhoneLookup,匹配失败返回 null)。 */
    @SuppressLint("Recycle")
    fun lookupName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }
}
