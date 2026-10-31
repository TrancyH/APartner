package com.amoolink.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 内置文件管理器(暗夜链路设计系统):
 * 顶栏(标题 + 存储占用条)+ 分类胶囊 + 磁块文件行 + 底部多选操作栏 + 推送进度条。
 * 逻辑与 v1 完全一致(FileStore 数据层 / PushController 分享 / 多选批量)。
 */
class FilesActivity : Activity() {

    companion object {
        private const val ROOT = FileStore.ROOT
        private val TABS = listOf("最近", "图片", "视频", "音频", "文档", "安装包", "压缩包", "浏览")
    }

    private lateinit var titleText: TextView
    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var tabsRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var selectText: TextView
    private lateinit var shareBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var moreBtn: Button
    private lateinit var newFolderBtn: Button
    private lateinit var pushRow: LinearLayout
    private lateinit var pushText: TextView
    private lateinit var pushBar: ProgressBar
    private lateinit var emptyText: TextView
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

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()
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

    // ── UI 构建 ─────────────────────────────────────────────────────

    private fun buildUi() {
        val match = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(14), dp(16), dp(14), 0)
        }

        // 顶栏:标题 + 新建文件夹
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleText = Ui.text(this, "最近", 20, Ui.TEXT, Ui.TITLE)
            .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        top.addView(titleText, LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f))
        newFolderBtn = Ui.ghostButton(this, "+ 文件夹").apply { visibility = View.GONE }
        newFolderBtn.setOnClickListener { onNewFolder() }
        top.addView(newFolderBtn)
        root.addView(top, match)

        // 存储占用(浏览模式)
        storageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_HIGH)
            visibility = View.GONE
        }
        root.addView(storageBar, match.apply { topMargin = dp(8) })
        storageText = Ui.text(this, "", 11, Ui.TEXT_3).apply { visibility = View.GONE }
        root.addView(storageText, match)

        // 分类胶囊
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.BG)
        }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in TABS) {
            val b = Button(this).apply {
                text = t
                textSize = 13f
                setSingleLine()
                setAllCaps(false)
                stateListAnimator = null
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setTextColor(Ui.TEXT_2)
                background = Ui.ripple(this@FilesActivity, Ui.card(this@FilesActivity, Ui.SURFACE, 999, Ui.STROKE, 1))
            }
            b.setOnClickListener { switchTab(t) }
            tabButtons.add(b)
            tabsRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        scroll.addView(tabsRow)
        root.addView(scroll, match.apply { topMargin = dp(12) })

        // 搜索
        searchEdit = EditText(this).apply {
            hint = "搜索当前列表…"
            textSize = 13f
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(this@FilesActivity, Ui.SURFACE, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })
        root.addView(searchEdit, match.apply { topMargin = dp(10) })

        // 推送进度条
        pushRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        pushText = Ui.text(this, "", 12, Ui.TEXT_2)
        pushRow.addView(pushText, match)
        pushBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_HIGH)
        }
        pushRow.addView(pushBar, match)
        root.addView(pushRow, match)

        // 空态
        emptyText = Ui.text(this, "", 13, Ui.TEXT_3).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(48), 0, 0)
        }
        root.addView(emptyText, match)

        // 列表
        adapter = Adapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
        }
        listView.adapter = adapter
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            onRowLongPress(filtered[pos])
            true
        }
        listView.setOnItemClickListener { _, _, pos, _ -> onRowClick(filtered[pos]) }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6)
        })

        // 底部多选操作栏(固定底,多选时滑入)
        val bottomWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE_HIGH)
            setPadding(dp(14), dp(10), dp(14), dp(14))
            visibility = View.GONE
        }
        selectText = Ui.text(this, "已选 0", 12, Ui.TEXT_2, Ui.TITLE)
        bottomWrap.addView(selectText, match)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shareBtn = Ui.primaryButton(this, "分享")
        deleteBtn = Ui.ghostButton(this, "删除", danger = true)
        moreBtn = Ui.ghostButton(this, "更多").apply { isEnabled = false }
        val cancelBtn = Ui.ghostButton(this, "取消")
        shareBtn.setOnClickListener { shareSelected() }
        deleteBtn.setOnClickListener { deleteSelected() }
        moreBtn.setOnClickListener { showMoreForSingle() }
        cancelBtn.setOnClickListener { exitSelection() }
        actions.addView(shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        actions.addView(moreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        actions.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bottomWrap.addView(actions, match.apply { topMargin = dp(8) })

        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        screen.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screen.addView(bottomWrap, match)

        setContentView(screen)
    }

    // ── 数据加载 ────────────────────────────────────────────────────

    private fun markTab(t: String) {
        tab = t
        for (b in tabButtons) {
            val on = b.text == t
            b.setTextColor(if (on) Color.parseColor("#0A2B29") else Ui.TEXT_2)
            b.background = if (on) {
                Ui.ripple(this, Ui.pill(this, Ui.ACCENT))
            } else {
                Ui.ripple(this, Ui.card(this, Ui.SURFACE, 999, Ui.STROKE, 1))
            }
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun switchTab(t: String) {
        exitSelection()
        searchEdit.setText("")
        markTab(t)
        val browse = t == "浏览"
        newFolderBtn.visibility = if (browse) View.VISIBLE else View.GONE
        storageBar.visibility = if (browse) View.VISIBLE else View.GONE
        storageText.visibility = if (browse) View.VISIBLE else View.GONE
        if (browse) {
            browsePath = ROOT
            refreshStorage()
        }
        load()
    }

    private fun refreshStorage() {
        try {
            val stat = android.os.StatFs(ROOT)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            storageBar.progress = if (total <= 0) 0 else ((used * 100) / total).toInt()
            storageText.text = FileStore.storageText()
        } catch (_: Exception) {
            storageText.text = ""
        }
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
                if (t != tab || (t == "浏览" && path != browsePath)) return@runOnUiThread
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
        val empty = filtered.isEmpty()
        emptyText.text = when {
            !empty -> ""
            q.isNotEmpty() -> "没有匹配「$q」的文件"
            tab == "浏览" -> "空目录"
            else -> "这里还没有文件"
        }
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ── 行交互 ──────────────────────────────────────────────────────

    private fun onRowClick(item: FileStore.Item) {
        if (selectionMode) {
            toggleSelect(item)
            return
        }
        if (item.dir && tab == "浏览") {
            browsePath = item.path
            refreshStorage()
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
            selectBarShow()
        }
        toggleSelect(item)
    }

    private fun selectBarShow() {
        // 底部操作栏固定在 screen 根的第 2 个子 View
        findBottomBar()?.visibility = View.VISIBLE
    }

    private fun findBottomBar(): LinearLayout? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val screen = content.getChildAt(0) as? LinearLayout ?: return null
        return screen.getChildAt(1) as? LinearLayout
    }

    private fun toggleSelect(item: FileStore.Item) {
        val p = item.path
        if (!selected.add(p)) selected.remove(p)
        refreshSelectionBar()
    }

    private fun refreshSelectionBar() {
        selectText.text = "已选 ${selected.size} 项"
        shareBtn.text = "分享(${selected.size})"
        deleteBtn.text = "删除(${selected.size})"
        moreBtn.isEnabled = selected.size == 1
        adapter.notifyDataSetChanged()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        findBottomBar()?.visibility = View.GONE
        refreshSelectionBar()
    }

    // ── 批量操作 ────────────────────────────────────────────────────

    private fun shareSelected() {
        val files = selected.mapNotNull { p -> items.firstOrNull { it.path == p } }.filter { !it.dir }
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
        pushBar.progress = 0
        pushText.text = "推送中:${next.name}"
        val file = File(next.path)
        val sent = ConnectionService.instance?.pushToDesktop(file, object : PushController.Listener {
            override fun onProgress(sentBytes: Long, total: Long) {
                val pct = if (total <= 0) 100 else ((sentBytes * 100) / total).toInt()
                runOnUiThread {
                    pushBar.progress = pct
                    pushText.text = "推送中:${next.name} $pct%(${next.sizeText})"
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
        val item = items.firstOrNull { it.path == selected.first() } ?: return
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
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(this@FilesActivity, Ui.SURFACE_HIGH, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
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
        refreshStorage()
        load()
        applyFilter()
    }

    private fun onNewFolder() {
        val input = EditText(this).apply {
            hint = "文件夹名称"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(this@FilesActivity, Ui.SURFACE_HIGH, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
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

    // ── 列表适配器(磁块行)────────────────────────────────────────

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
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val tile = TextView(this@FilesActivity).apply {
                    gravity = Gravity.CENTER
                    textSize = 17f
                }
                line.addView(tile, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
                val col = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL }
                val name = TextView(this@FilesActivity).apply {
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                }
                val meta = TextView(this@FilesActivity).apply { textSize = 11f; setTextColor(Ui.TEXT_3) }
                col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                col.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val check = TextView(this@FilesActivity).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
                check.background = Ui.card(this@FilesActivity, Ui.SURFACE_HIGH, 999, Ui.STROKE, 1)
                line.addView(check, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
                view = line
                holder = RowHolder(tile, name, meta, check, line)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as RowHolder
            }
            val item = filtered[position]
            holder.tile.text = item.icon
            holder.tile.background = Ui.tile(this@FilesActivity, item.tileColor)
            holder.name.text = item.name
            holder.meta.text = "${item.sizeText} · ${item.timeText}"
            val checked = item.path in selected
            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.check.text = if (checked) "✓" else ""
            holder.check.background = if (checked) {
                Ui.card(this@FilesActivity, Ui.ACCENT, 999, strokeWidth = 0)
            } else {
                Ui.card(this@FilesActivity, Ui.SURFACE_HIGH, 999, Ui.STROKE, 1)
            }
            holder.check.setTextColor(Color.parseColor("#0A2B29"))
            holder.row.setBackgroundColor(if (checked) Color.parseColor("#182031") else Color.TRANSPARENT)
            return view
        }
    }

    private class RowHolder(
        val tile: TextView,
        val name: TextView,
        val meta: TextView,
        val check: TextView,
        val row: LinearLayout,
    )
}
