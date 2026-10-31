package com.amoolink.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 远程文件管理 fs_* 处理器(协议 v2;与桌面 packages/companion/src/fs-protocol.ts 对齐):
 *
 *   fs_list   {path}                    → fs_entries {path, entries:[{name,dir,size,mtime}]}
 *   fs_stat   {path}                    → fs_info    {path,name,dir,size,mtime,exists}
 *   fs_read   {path, offset, len}       → fs_data    {path, offset, data(b64), eof, sha256(块级 hex)}
 *   fs_write  {path, offset, data(b64), sha256?, truncate?} → fs_ack {path, offset, bytes}
 *   fs_write_end {path, size, sha256?}  → fs_done    {path, ok, size, sha256?}
 *   fs_delete {path}                    → fs_result  {path, ok}
 *
 * 错误复用 error 帧:code ∈ fs_permission_required / fs_not_found / fs_path_denied / fs_io_error。
 * 磁盘 IO 全部走单线程 executor(不占连接读循环线程);conn.send 自带线程安全队列。
 */
object FsHandler {

    private const val CHUNK = 512 * 1024
    /** 虚拟根:远程路径 "/" 映射到共享存储根,与桌面 fs-protocol.ts FS_ROOT 对齐。 */
    private const val ROOT = "/storage/emulated/0"

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "amoolink-fs").apply { isDaemon = true }
    }

    /** @return true = 已受理(含异步回帧);false = 非 fs 类型,调用方继续走 v1 分支。 */
    fun handle(context: Context, conn: FrameConn, id: Any?, type: String, frame: JSONObject): Boolean {
        when (type) {
            "fs_list", "fs_stat", "fs_read", "fs_write", "fs_write_end", "fs_delete" -> {
                val virtual = frame.optString("path")
                io.execute {
                    try {
                        when (type) {
                            "fs_list" -> list(context, conn, id, virtual)
                            "fs_stat" -> stat(context, conn, id, virtual)
                            "fs_read" -> read(context, conn, id, virtual, frame)
                            "fs_write" -> write(context, conn, id, virtual, frame)
                            "fs_write_end" -> writeEnd(context, conn, id, virtual, frame)
                            "fs_delete" -> delete(context, conn, id, virtual)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AmooLink-FS", "fs $type failed: $virtual", e)
                        err(conn, id, "fs_io_error", e.message ?: e.javaClass.simpleName)
                    }
                }
                return true
            }
            else -> return false
        }
    }

    // ── 路径安全 ────────────────────────────────────────────────────

    /** 路径 → 真实 File。拒绝 ..;空/"/" → 根;结果必须落在 ROOT 内。
     *  兼容两种形态:虚拟路径("/Download/x")与手机本地物理绝对路径("/storage/emulated/0/Download/x",
     *  文件管理器 Item.path / fs_relay 传入的即是),后者先剥 ROOT 前缀。 */
    private fun resolve(virtualPath: String): File? {
        var raw = virtualPath.trim()
        if (raw == ROOT) return File(ROOT)
        if (raw.startsWith("$ROOT/")) raw = raw.removePrefix("$ROOT/")
        val abs = if (raw.startsWith("/")) raw else "/$raw"
        val parts = ArrayList<String>()
        for (seg in abs.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> return null
                else -> parts.add(seg)
            }
        }
        val joined = parts.joinToString("/")
        val f = if (joined.isEmpty()) File(ROOT) else File("$ROOT/$joined")
        // 纵深防御:符号链接等极端情形下 canonicalPath 不得逃出根
        val canonical = try {
            f.canonicalFile.absolutePath
        } catch (_: IOException) {
            f.absolutePath
        }
        return if (canonical == ROOT || canonical.startsWith("$ROOT/")) f else null
    }

    /** 存储访问授权自查:API≥30 看「所有文件访问」,更早看经典读写权限。 */
    private fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    // ── 六种处理器(均在 io 线程执行)──────────────────────────────

    private fun list(context: Context, conn: FrameConn, id: Any?, virtual: String) {
        val dir = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        if (!dir.exists()) return err(conn, id, "fs_not_found", virtual)
        if (!dir.isDirectory) return err(conn, id, "fs_io_error", "不是目录")
        val entries = JSONArray()
        // Android/data 等受限目录 listFiles 可能返回 null(API30+):容忍为空列表
        val files = dir.listFiles()
        if (files != null) {
            for (f in files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
                entries.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("dir", f.isDirectory)
                        .put("size", if (f.isDirectory) 0L else f.length())
                        .put("mtime", f.lastModified()),
                )
            }
        }
        conn.send(resp(id, "fs_entries") { put("path", virtual); put("entries", entries) })
    }

    private fun stat(context: Context, conn: FrameConn, id: Any?, virtual: String) {
        val file = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        val exists = file.exists()
        android.util.Log.i(
            "AmooLink",
            "FS-STAT virtual=$virtual resolved=${file.absolutePath} exists=$exists canRead=${file.canRead()} len=${if (exists) file.length() else -1}",
        )
        conn.send(
            resp(id, "fs_info") {
                put("path", virtual)
                put("name", file.name)
                put("dir", exists && file.isDirectory)
                put("size", if (exists && file.isFile) file.length() else 0L)
                put("mtime", if (exists) file.lastModified() else 0L)
                put("exists", exists)
            },
        )
    }

    private fun read(context: Context, conn: FrameConn, id: Any?, virtual: String, frame: JSONObject) {
        val file = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        if (!file.isFile) return err(conn, id, "fs_not_found", virtual)
        val offset = frame.optLong("offset", 0).coerceAtLeast(0)
        val len = frame.optLong("len", CHUNK.toLong()).coerceIn(1, CHUNK.toLong())
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (offset >= size) {
                conn.send(
                    resp(id, "fs_data") {
                        put("path", virtual); put("offset", offset)
                        put("data", ""); put("eof", true); put("sha256", hex(Proto.sha256(ByteArray(0))))
                    },
                )
                return
            }
            raf.seek(offset)
            val buf = ByteArray(minOf(len, size - offset).toInt())
            raf.readFully(buf)
            conn.send(
                resp(id, "fs_data") {
                    put("path", virtual); put("offset", offset)
                    put("data", Proto.b64(buf))
                    put("eof", offset + buf.size >= size)
                    put("sha256", hex(Proto.sha256(buf)))
                },
            )
        }
    }

    private fun write(context: Context, conn: FrameConn, id: Any?, virtual: String, frame: JSONObject) {
        val file = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        val data = try {
            Proto.unb64(frame.optString("data"))
        } catch (_: IllegalArgumentException) {
            return err(conn, id, "fs_io_error", "base64 解码失败")
        }
        val offset = frame.optLong("offset", 0).coerceAtLeast(0)
        if (data.size > CHUNK) return err(conn, id, "fs_io_error", "块超限 ${data.size}")
        val want = frame.optString("sha256")
        if (want.isNotEmpty() && hex(Proto.sha256(data)) != want) {
            return err(conn, id, "fs_io_error", "块 sha256 不匹配")
        }
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (offset == 0L && frame.optBoolean("truncate")) raf.setLength(0)
            raf.seek(offset)
            raf.write(data)
            conn.send(resp(id, "fs_ack") { put("path", virtual); put("offset", offset); put("bytes", data.size) })
        }
    }

    private fun writeEnd(context: Context, conn: FrameConn, id: Any?, virtual: String, frame: JSONObject) {
        val file = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        if (!file.isFile) return err(conn, id, "fs_not_found", virtual)
        val wantSize = frame.optLong("size", -1)
        if (wantSize >= 0 && file.length() != wantSize) {
            return err(conn, id, "fs_io_error", "大小不符 ${file.length()} != $wantSize")
        }
        val wantSha = frame.optString("sha256")
        if (wantSha.isNotEmpty()) {
            val actual = sha256File(file)
            if (actual != wantSha) return err(conn, id, "fs_io_error", "整文件 sha256 不匹配")
            conn.send(resp(id, "fs_done") { put("path", virtual); put("ok", true); put("size", file.length()); put("sha256", actual) })
        } else {
            conn.send(resp(id, "fs_done") { put("path", virtual); put("ok", true); put("size", file.length()) })
        }
    }

    private fun delete(context: Context, conn: FrameConn, id: Any?, virtual: String) {
        val file = resolve(virtual) ?: return err(conn, id, "fs_path_denied", virtual)
        if (!hasAccess(context)) return err(conn, id, "fs_permission_required", "外部存储未授权")
        if (file.absolutePath == ROOT) return err(conn, id, "fs_path_denied", "拒绝删除根目录")
        if (!file.exists()) return err(conn, id, "fs_not_found", virtual)
        val okDel = file.deleteRecursively()
        conn.send(resp(id, "fs_result") { put("path", virtual); put("ok", okDel) })
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private fun resp(id: Any?, type: String, body: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply {
            put("id", if (id == JSONObject.NULL) null else id)
            put("type", type)
            body()
        }

    private fun err(conn: FrameConn, id: Any?, code: String, message: String) {
        conn.send(resp(id, "error") { put("code", code); put("message", message) })
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
}
