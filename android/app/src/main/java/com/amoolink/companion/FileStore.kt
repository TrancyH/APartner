package com.amoolink.companion

import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理数据层(零外部依赖;MANAGE_EXTERNAL_STORAGE 下直接文件系统访问):
 * 目录列举(文件夹优先)、分类查询(图片/视频/音频走 MediaStore,文档/安装包/压缩包/
 * 最近按扩展名扫常见目录)、重命名/删除/新建、存储概览。
 * 全部方法为阻塞 IO,调用方自行放后台线程。
 */
object FileStore {

    const val ROOT = "/storage/emulated/0"

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class Item(
        val name: String,
        val path: String,
        val dir: Boolean,
        val size: Long,
        val mtime: Long,
    ) {
        val icon: String
            get() = when {
                dir -> "📁"
                iconFor(name) != null -> iconFor(name)!!
                else -> "📄"
            }

        val sizeText: String
            get() = when {
                dir -> "目录"
                size <= 0 -> "0 B"
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
                size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024)
                else -> "%.2f GB".format(size / 1024.0 / 1024 / 1024)
            }

        val timeText: String
            get() = if (mtime <= 0) "" else DATE_FMT.format(Date(mtime))
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "dng")
    private val AUDIO_EXT = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "amr", "opus")
    private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub")
    private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")

    fun iconFor(name: String): String? {
        val ext = extOf(name)
        return when {
            ext in IMAGE_EXT -> "🖼️"
            ext in VIDEO_EXT -> "🎬"
            ext in AUDIO_EXT -> "🎵"
            ext == "apk" -> "📦"
            ext in ARCHIVE_EXT -> "🗜️"
            ext in DOC_EXT -> "📝"
            else -> null
        }
    }

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0) "" else name.substring(i + 1).lowercase(Locale.US)
    }

    // ── 目录浏览 ────────────────────────────────────────────────────

    fun listDir(path: String): List<Item> {
        val dir = File(path)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .map { Item(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            .sortedWith(compareBy({ !it.dir }, { it.name.lowercase(Locale.US) }))
    }

    fun storageText(): String {
        return try {
            val stat = android.os.StatFs(ROOT)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            "内部存储 已用 ${human(used)} / 共 ${human(total)}(可用 ${human(free)})"
        } catch (_: Exception) {
            ""
        }
    }

    private fun human(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
        else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
    }

    // ── 分类(全部阻塞 IO)────────────────────────────────────────

    fun recent(): List<Item> {
        val dirs = listOf("Download", "Documents", "DCIM/Camera", "Pictures", "Movies", "Music")
            .map { File(ROOT, it) }
        val all = ArrayList<Item>()
        for (d in dirs) collectFiles(d, all, null, 400)
        return all.sortedByDescending { it.mtime }.take(100)
    }

    fun images(context: Context): List<Item> = mediaStoreQuery(
        context,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    )

    fun videos(context: Context): List<Item> = mediaStoreQuery(
        context,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )

    fun audio(context: Context): List<Item> = mediaStoreQuery(
        context,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
    )

    private fun mediaStoreQuery(context: Context, uri: android.net.Uri): List<Item> {
        val out = ArrayList<Item>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            context.contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 200) {
                    val path = c.getString(0) ?: continue
                    val f = File(path)
                    if (!f.isFile) continue
                    out.add(Item(f.name, path, false, c.getLong(2), c.getLong(3) * 1000))
                }
            }
        } catch (_: Exception) {
            // 无权限/无媒体时降级为空列表
        }
        return out
    }

    fun documents(): List<Item> = scanByExt(listOf("Download", "Documents"), DOC_EXT)

    fun apks(): List<Item> = scanByExt(listOf("Download", "Documents"), setOf("apk"))

    fun archives(): List<Item> = scanByExt(listOf("Download", "Documents"), ARCHIVE_EXT)

    private fun scanByExt(relDirs: List<String>, exts: Set<String>): List<Item> {
        val all = ArrayList<Item>()
        for (rel in relDirs) collectFiles(File(ROOT, rel), all, exts, 200)
        return all.sortedByDescending { it.mtime }
    }

    private fun collectFiles(dir: File, out: ArrayList<Item>, exts: Set<String>?, limit: Int) {
        if (out.size >= limit) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (out.size >= limit) return
            if (f.isDirectory) {
                collectFiles(f, out, exts, limit)
            } else if (exts == null || extOf(f.name) in exts) {
                out.add(Item(f.name, f.absolutePath, false, f.length(), f.lastModified()))
            }
        }
    }

    // ── 操作 ────────────────────────────────────────────────────────

    fun rename(path: String, newName: String): Boolean {
        if (newName.isBlank() || newName.contains('/') || newName == "." || newName == "..") return false
        val f = File(path)
        if (!f.exists()) return false
        return try {
            val target = File(f.parentFile, newName)
            if (target.exists()) false else f.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        if (f.absolutePath == ROOT) return false
        return try {
            f.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }

    fun mkdir(parent: String, name: String): Boolean {
        if (name.isBlank() || name.contains('/')) return false
        return try {
            File(parent, name).mkdir()
        } catch (_: Exception) {
            false
        }
    }
}
