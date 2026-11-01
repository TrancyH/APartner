package com.amoolink.companion

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder

/**
 * 第三方应用分享的文件通道(零依赖替代 androidx FileProvider):
 * content://com.amoolink.companion.sharefiles/<URL 编码的绝对路径>
 * 仅允许共享存储根(/storage/emulated/0)内的文件;配合 FLAG_GRANT_READ_URI_PERMISSION 使用。
 */
class ShareFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.amoolink.companion.sharefiles"
        private const val ALLOWED_ROOT = "/storage/emulated/0"

        fun uriFor(path: String): Uri =
            Uri.parse("content://$AUTHORITY/" + Uri.encode(path))
    }

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val encoded = uri.lastPathSegment ?: throw FileNotFoundException()
        val path = URLDecoder.decode(encoded, "UTF-8")
        val f = File(path)
        val canonical = try {
            f.canonicalPath
        } catch (_: Exception) {
            f.absolutePath
        }
        if (canonical != ALLOWED_ROOT && !canonical.startsWith("$ALLOWED_ROOT/")) {
            throw FileNotFoundException("outside shared storage")
        }
        if (!f.isFile) throw FileNotFoundException(path)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolve(uri)
        val m = if (mode == "w") ParcelFileDescriptor.MODE_WRITE_ONLY else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, m)
    }

    override fun getType(uri: Uri): String {
        val name = File(URLDecoder.decode(uri.lastPathSegment ?: "", "UTF-8")).name
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
