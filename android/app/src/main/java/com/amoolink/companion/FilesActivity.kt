package com.amoolink.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 内置文件管理器(程序化 UI,零外部依赖;对齐厂商文件管理的 v1 能力面):
 * 分类(最近/图片/视频/音频/文档/安装包/压缩包)+ 目录浏览(面包屑/新建文件夹/存储概览)
 * + 搜索过滤 + 多选批量(长按进入):分享到电脑(fs_push_* 反向推送)/ 删除,
 * 单选「更多」:重命名/详情。IO 全部后台线程,UI 更新 runOnUiThread。
 */
class FilesActivity : Activity() {

    companion object {
        private const val ROOT = FileStore.ROOT
        private val TABS = listOf("最近", "图片", "视频", "音频", "文档", "安装包", "压缩包", "浏览")
    }

    private lateinit var titleText: TextView
    private lateinit var storageText: TextView
    private lateinit var tabsRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var selectBar: LinearLayout
    private lateinit var selectText: TextView
    private lateinit var shareBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var moreBtn: Button
    private lateinit var newFolderBtn: Button
    private lateinit var pushRow: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: Adapter
    private val tabButtons = ArrayList<Button>()

    private var tab = "最近"
    private var browsePath = ROOT
    private var items: List<FileStore.Item> = emptyList()
    private var filtered: List<FileStore.Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selectionMode = false
    private val pushQueue = ArrayDeque<FileStore.Item>()
    private var pushing = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }

        // 顶行:标题 + 新建文件夹(仅浏览模式)
        val topLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleText = TextView(this).apply {
            text = "最近"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        topLine.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        newFolderBtn = Button(this).apply { text = "+文件夹"; visibility = View.GONE }
        newFolderBtn.setOnClickListener { onNewFolder() }
        topLine.addView(newFolderBtn, params)
        root.addView(topLine, params)

        storageText = TextView(this).apply { textSize = 12f; setPadding(0, dp(2), 0, 0); visibility = View.GONE }
        root.addView(storageText, params)

        // 分类页签
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in TABS) {
            val b = Button(this).apply {
                text = t
                textSize = 13f
                setSingleLine()
                setPadding(dp(10), 0, dp(10), 0)
            }
            b.setOnClickListener { switchTab(t) }
            tabButtons.add(b)
            tabsRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.addView(tabsRow)
        root.addView(scroll, params)

        // 搜索
        searchEdit = EditText(this).apply {
            hint = "搜索当前列表…"
            textSize = 13f
            setSingleLine()
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })
        root.addView(searchEdit, params)

        // 多选操作栏
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, dp(6))
        }
        selectText = TextView(this).apply { textSize = 13f }
        selectBar.addView(selectText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shareBtn = Button(this).apply { textSize = 13f; text = "分享到电脑" }
        deleteBtn = Button(this).apply { textSize = 13f; text = "删除" }
        moreBtn = Button(this).apply { textSize = 13f; text = "更多"; isEnabled = false }
        val cancelBtn = Button(this).apply { textSize = 13f; text = "取消" }
        shareBtn.setOnClickListener { shareSelected() }
        deleteBtn.setOnClickListener { deleteSelected() }
        moreBtn.setOnClickListener { showMoreForSingle() }
        cancelBtn.setOnClickListener { exitSelection() }
        selectBar.addView(shareBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        selectBar.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        selectBar.addView(moreBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        selectBar.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(selectBar, params)

        // 推送进度行
        pushRow = TextView(this).apply { textSize = 12f; visibility = View.GONE }
        root.addView(pushRow, params)

        // 列表
        adapter = Adapter()
        listView = ListView(this).apply { dividerHeight = 0 }
        listView.adapter = adapter
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            onRowLongPress(filtered[pos])
            true
        }
        listView.setOnItemClickListener { _, _, pos, _ -> onRowClick(filtered[pos]) }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        markTab("最近")
        load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selectionMode -> exitSelection()
            tab == "浏览" && browsePath != ROOT -> navigateUp()
            else -> super.onBackPressed()
        }
    }

    // ── 数据加载 ────────────────────────────────────────────────────

    private fun markTab(t: String) {
        tab = t
        for (b in tabButtons) {
            b.setTypeface(null, if (b.text == t) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun switchTab(t: String) {
        exitSelection()
        searchEdit.setText("")
        markTab(t)
        val browse = t == "浏览"
        newFolderBtn.visibility = if (browse) View.VISIBLE else View.GONE
        storageText.visibility = if (browse) View.VISIBLE else View.GONE
        if (browse) {
            browsePath = ROOT
            storageText.text = FileStore.storageText()
        }
        load()
    }

    private fun load() {
        val context = applicationContext
        val t = tab
        val path = browsePath
        Thread {
            val list = try {
                when (t) {
                    "最近" -> FileStore.recent()
                    "图片" -> FileStore.images(context)
                    "视频" -> FileStore.videos(context)
                    "音频" -> FileStore.audio(context)
                    "文档" -> FileStore.documents()
                    "安装包" -> FileStore.apks()
                    "压缩包" -> FileStore.archives()
                    else -> FileStore.listDir(path)
                }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (t != tab || (t == "浏览" && path != browsePath)) return@runOnUiThread // 已切换,丢弃
                items = list
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = searchEdit.text?.toString()?.trim() ?: ""
        filtered = if (q.isEmpty()) items else items.filter { it.name.contains(q, ignoreCase = true) }
        titleText.text = when (tab) {
            "浏览" -> "内部存储" + if (browsePath == ROOT) "" else browsePath.removePrefix(ROOT)
            else -> tab + (if (q.isEmpty()) "" else " · ${filtered.size} 项")
        }
        adapter.notifyDataSetChanged()
    }

    // ── 行交互 ──────────────────────────────────────────────────────

    private fun rowPath(item: FileStore.Item): String = item.path

    private fun onRowClick(item: FileStore.Item) {
        if (selectionMode) {
            toggleSelect(item)
            return
        }
        if (item.dir && tab == "浏览") {
            browsePath = item.path
            load()
            applyFilter()
        } else {
            showDetails(item)
        }
    }

    private fun onRowLongPress(item: FileStore.Item) {
        if (!selectionMode) {
            selectionMode = true
            selected.clear()
            selectBar.visibility = View.VISIBLE
            searchEdit.visibility = View.GONE
        }
        toggleSelect(item)
    }

    private fun toggleSelect(item: FileStore.Item) {
        val p = rowPath(item)
        if (!selected.add(p)) selected.remove(p)
        refreshSelectionBar()
    }

    private fun refreshSelectionBar() {
        selectText.text = "已选 ${selected.size}"
        shareBtn.text = "分享到电脑(${selected.size})"
        deleteBtn.text = "删除(${selected.size})"
        moreBtn.isEnabled = selected.size == 1
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        selectBar.visibility = View.GONE
        searchEdit.visibility = View.VISIBLE
        refreshSelectionBar()
    }

    private fun findItem(path: String): FileStore.Item? = items.firstOrNull { it.path == path }

    // ── 批量操作 ────────────────────────────────────────────────────

    private fun shareSelected() {
        val files = selected.mapNotNull { findItem(it) }.filter { !it.dir }
        if (files.isEmpty()) {
            toast("文件夹暂不支持推送,请选择文件")
            return
        }
        val svc = ConnectionService.instance
        if (svc == null || !svc.hasConnectedDesktop()) {
            toast("未连接电脑——请先在主界面配对并连接")
            return
        }
        if (pushing) return
        pushQueue.clear()
        pushQueue.addAll(files)
        pushing = true
        pushNext()
    }

    private fun pushNext() {
        val next = pushQueue.removeFirstOrNull()
        if (next == null) {
            pushing = false
            pushRow.visibility = View.GONE
            toast("全部推送完成")
            exitSelection()
            return
        }
        pushRow.visibility = View.VISIBLE
        pushRow.text = "推送中:${next.name} 0%"
        val file = File(next.path)
        val sent = ConnectionService.instance?.pushToDesktop(file, object : PushController.Listener {
            override fun onProgress(sentBytes: Long, total: Long) {
                val pct = if (total <= 0) 100 else (sentBytes * 100 / total).toInt()
                runOnUiThread {
                    pushRow.text = "推送中:${next.name} $pct%(${next.sizeText})"
                }
            }

            override fun onDone() {
                runOnUiThread {
                    toast("已发送:${next.name}")
                    pushNext()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    toast("推送失败:${next.name}($message)")
                    pushNext()
                }
            }
        })
        if (sent != true) {
            pushing = false
            pushRow.visibility = View.GONE
            toast("推送启动失败(连接可能已断开)")
        }
    }

    private fun deleteSelected() {
        val paths = selected.toList()
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("删除已选 ${paths.size} 项?此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    var okCount = 0
                    for (p in paths) if (FileStore.delete(p)) okCount++
                    runOnUiThread {
                        toast("已删除 $okCount/${paths.size} 项")
                        exitSelection()
                        load()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreForSingle() {
        if (selected.size != 1) return
        val item = findItem(selected.first()) ?: return
        val options = if (item.dir) arrayOf("重命名", "详情") else arrayOf("分享到电脑", "重命名", "详情")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "分享到电脑" -> {
                        selected.clear()
                        selected.add(item.path)
                        shareSelected()
                    }
                    "重命名" -> showRename(item)
                    "详情" -> showDetails(item)
                }
            }
            .show()
    }

    private fun showRename(item: FileStore.Item) {
        val input = EditText(this).apply { setText(item.name); setSelection(item.name.length) }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton
                Thread {
                    val ok = FileStore.rename(item.path, newName)
                    runOnUiThread {
                        toast(if (ok) "已重命名" else "重命名失败(重名或非法字符)")
                        if (ok) {
                            exitSelection()
                            load()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDetails(item: FileStore.Item) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(
                listOf(
                    "类型:${if (item.dir) "文件夹" else "文件"}",
                    "大小:${item.sizeText}",
                    "修改时间:${item.timeText}",
                    "路径:${item.path}",
                ).joinToString("\n"),
            )
            .setPositiveButton("好", null)
            .show()
    }

    // ── 浏览模式操作 ────────────────────────────────────────────────

    private fun navigateUp() {
        browsePath = File(browsePath).parent ?: ROOT
        load()
        applyFilter()
    }

    private fun onNewFolder() {
        val input = EditText(this).apply { hint = "文件夹名称" }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Thread {
                    val ok = FileStore.mkdir(browsePath, name)
                    runOnUiThread {
                        toast(if (ok) "已创建" else "创建失败(重名或非法字符)")
                        if (ok) load()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── 列表适配器 ──────────────────────────────────────────────────

    inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = filtered.size
        override fun getItem(position: Int): Any = filtered[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: RowHolder
            val view: View
            if (convertView == null) {
                val line = LinearLayout(this@FilesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                }
                val icon = TextView(this@FilesActivity).apply { textSize = 22f }
                val col = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL }
                val name = TextView(this@FilesActivity).apply {
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                }
                val meta = TextView(this@FilesActivity).apply { textSize = 11f; alpha = 0.6f }
                col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                col.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(icon, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val check = TextView(this@FilesActivity).apply { textSize = 18f; visibility = View.GONE }
                line.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                view = line
                holder = RowHolder(icon, name, meta, check)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as RowHolder
            }
            val item = filtered[position]
            holder.icon.text = item.icon
            holder.name.text = item.name
            holder.meta.text = "${item.sizeText} · ${item.timeText}"
            val checked = rowPath(item) in selected
            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.check.text = if (checked) "✅" else "⚪"
            view.setBackgroundColor(if (checked) 0x333B82F6.toInt() else 0x00000000)
            return view
        }
    }

    private class RowHolder(
        val icon: TextView,
        val name: TextView,
        val meta: TextView,
        val check: TextView,
    )
}
