package com.amoolink.companion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
 * 文件管理页(由 FilesActivity 重构为页面类,供底部 dock 壳容器承载):
 * 分类胶囊 + 磁块文件行 + 搜索 + 存储条 + 底部多选操作栏;
 * v1.5 能力:排序(名称/时间/大小 × 升降)、复制/剪切/粘贴、压缩为 ZIP、解压到此处
 * (Zip Slip 防护)、文件夹分享自动打包 ZIP 推送。零外部依赖(zip 用 java.util.zip)。
 */
class FilesPage(private val act: Activity) {

    companion object {
        private const val ROOT = FileStore.ROOT
        private val TABS = listOf("最近", "图片", "视频", "音频", "文档", "安装包", "压缩包", "浏览")
    }

    private fun dp(v: Int): Int = Ui.dp(act, v)

    private lateinit var titleText: TextView
    private lateinit var sortBtn: Button
    private lateinit var pasteBtn: Button
    private lateinit var newFolderBtn: Button
    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var tabsRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var selectText: TextView
    private lateinit var shareBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var moreBtn: Button
    private lateinit var pushRow: LinearLayout
    private lateinit var pushText: TextView
    private lateinit var pushBar: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var listView: ListView
    private lateinit var adapter: Adapter
    private val tabButtons = ArrayList<Button>()

    private var tab = "最近"
    private var browsePath = ROOT
    private var items: List<FileStore.Item> = emptyList()
    private var filtered: List<FileStore.Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selectionMode = false
    private val pushQueue = ArrayDeque<File>()
    private var pushing = false

    // v1.5:排序 / 剪贴板
    private var sortBy = FileStore.SortBy.TIME
    private var sortAsc = false
    private enum class ClipMode { COPY, CUT }
    private var clipMode: ClipMode? = null
    private var clipPaths: List<String> = emptyList()

    // ── 页面构建 ────────────────────────────────────────────────────

    fun build(): View {
        val match = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(14), dp(16), dp(14), 0)
        }

        // 顶栏:标题 + 排序 / 粘贴 / 新建文件夹
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleText = Ui.text(act, "最近", 20, Ui.TEXT, Ui.TITLE)
            .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        top.addView(titleText, LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f))
        pasteBtn = Ui.ghostButton(act, "粘贴").apply { visibility = View.GONE }
        pasteBtn.setOnClickListener { pasteClipboard() }
        top.addView(pasteBtn)
        sortBtn = Ui.ghostButton(act, "排序").apply {
            setOnClickListener { showSortMenu() }
            (top as LinearLayout).let { }
        }
        top.addView(sortBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        newFolderBtn = Ui.ghostButton(act, "+ 文件夹").apply { visibility = View.GONE }
        newFolderBtn.setOnClickListener { onNewFolder() }
        top.addView(newFolderBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        root.addView(top, match)

        // 存储占用(浏览模式)
        storageBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_HIGH)
            visibility = View.GONE
        }
        root.addView(storageBar, match.apply { topMargin = dp(8) })
        storageText = Ui.text(act, "", 11, Ui.TEXT_3).apply { visibility = View.GONE }
        root.addView(storageText, match)

        // 分类胶囊
        val scroll = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.BG)
        }
        tabsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in TABS) {
            val b = Button(act).apply {
                text = t
                textSize = 13f
                setSingleLine()
                setAllCaps(false)
                stateListAnimator = null
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setTextColor(Ui.TEXT_2)
                background = Ui.ripple(act, Ui.card(act, Ui.SURFACE, 999, Ui.STROKE, 1))
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
        searchEdit = EditText(act).apply {
            hint = "搜索当前列表…"
            textSize = 13f
            setSingleLine()
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(act, Ui.SURFACE, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })
        root.addView(searchEdit, match.apply { topMargin = dp(10) })

        // 推送进度条
        pushRow = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        pushText = Ui.text(act, "", 12, Ui.TEXT_2)
        pushRow.addView(pushText, match)
        pushBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_HIGH)
        }
        pushRow.addView(pushBar, match)
        root.addView(pushRow, match)

        // 空态
        emptyText = Ui.text(act, "", 13, Ui.TEXT_3).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(48), 0, 0)
        }
        root.addView(emptyText, match)

        // 列表
        adapter = Adapter()
        listView = ListView(act).apply {
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

        // 底部多选操作栏
        val bottomWrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE_HIGH)
            setPadding(dp(14), dp(10), dp(14), dp(14))
            visibility = View.GONE
        }
        selectText = Ui.text(act, "已选 0 项", 12, Ui.TEXT_2, Ui.TITLE)
        bottomWrap.addView(selectText, match)
        val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shareBtn = Ui.primaryButton(act, "分享")
        deleteBtn = Ui.ghostButton(act, "删除", danger = true)
        moreBtn = Ui.ghostButton(act, "更多").apply { isEnabled = false }
        val cancelBtn = Ui.ghostButton(act, "取消")
        shareBtn.setOnClickListener { shareSelected() }
        deleteBtn.setOnClickListener { deleteSelected() }
        moreBtn.setOnClickListener { showMore() }
        cancelBtn.setOnClickListener { exitSelection() }
        actions.addView(shareBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        actions.addView(moreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        actions.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bottomWrap.addView(actions, match.apply { topMargin = dp(8) })

        val screen = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        screen.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screen.addView(bottomWrap, match)
        bottomBar = bottomWrap
        markTab(tab)
        return screen
    }

    /** 壳容器切到本页时刷新数据。 */
    fun onResume() {
        if (tab == "浏览") refreshStorage()
        load()
    }

    /** @return true = 已消费(返回键不退出页面)。 */
    fun onBackPressed(): Boolean {
        return when {
            selectionMode -> {
                exitSelection()
                true
            }
            tab == "浏览" && browsePath != ROOT -> {
                navigateUp()
                true
            }
            else -> false
        }
    }

    // ── 数据加载 ────────────────────────────────────────────────────

    private fun markTab(t: String) {
        tab = t
        for (b in tabButtons) {
            val on = b.text == t
            b.setTextColor(if (on) Color.parseColor("#0A2B29") else Ui.TEXT_2)
            b.background = if (on) {
                Ui.ripple(act, Ui.pill(act, Ui.ACCENT))
            } else {
                Ui.ripple(act, Ui.card(act, Ui.SURFACE, 999, Ui.STROKE, 1))
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
            sortBy = FileStore.SortBy.NAME
            sortAsc = true
            refreshStorage()
        } else {
            sortBy = FileStore.SortBy.TIME
            sortAsc = false
        }
        updatePasteVisibility()
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
        val context = act.applicationContext
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
            act.runOnUiThread {
                if (t != tab || (t == "浏览" && path != browsePath)) return@runOnUiThread
                items = list
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val q = searchEdit.text?.toString()?.trim() ?: ""
        val base = if (q.isEmpty()) items else items.filter { it.name.contains(q, ignoreCase = true) }
        filtered = FileStore.sorted(base, sortBy, sortAsc)
        val sortTag = if (q.isEmpty()) "" else " · ${filtered.size} 项"
        titleText.text = when (tab) {
            "浏览" -> "内部存储" + if (browsePath == ROOT) "" else browsePath.removePrefix(ROOT)
            else -> tab + sortTag
        }
        val empty = filtered.isEmpty()
        emptyText.text = when {
            !empty -> ""
            q.isNotEmpty() -> "没有匹配「$q」的文件"
            tab == "浏览" -> "空目录 · 点右上「+ 文件夹」创建"
            else -> "这里还没有文件"
        }
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ── 排序 / 粘贴 ─────────────────────────────────────────────────

    private fun showSortMenu() {
        val options = listOf("名称 ↑", "名称 ↓", "时间 ↑", "时间 ↓", "大小 ↑", "大小 ↓")
        AlertDialog.Builder(act)
            .setTitle("排序(目录恒在前)")
            .setItems(options.toTypedArray()) { _, w ->
                when (w) {
                    0 -> { sortBy = FileStore.SortBy.NAME; sortAsc = true }
                    1 -> { sortBy = FileStore.SortBy.NAME; sortAsc = false }
                    2 -> { sortBy = FileStore.SortBy.TIME; sortAsc = true }
                    3 -> { sortBy = FileStore.SortBy.TIME; sortAsc = false }
                    4 -> { sortBy = FileStore.SortBy.SIZE; sortAsc = true }
                    5 -> { sortBy = FileStore.SortBy.SIZE; sortAsc = false }
                }
                applyFilter()
            }
            .show()
    }

    private fun updatePasteVisibility() {
        pasteBtn.visibility = if (tab == "浏览" && clipPaths.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun copySelected() = clipSelected(ClipMode.COPY)

    private fun cutSelected() = clipSelected(ClipMode.CUT)

    private fun clipSelected(mode: ClipMode) {
        val paths = selected.toList()
        if (paths.isEmpty()) return
        val hasDir = paths.any { path -> items.firstOrNull { it.path == path }?.dir == true }
        clipMode = mode
        clipPaths = paths
        exitSelection()
        updatePasteVisibility()
        toast(
            when {
                mode == ClipMode.COPY && hasDir -> "已复制 ${paths.size} 项(文件夹将整体复制),去目标目录点「粘贴」"
                mode == ClipMode.COPY -> "已复制 ${paths.size} 项,去目标目录点「粘贴」"
                hasDir -> "已剪切 ${paths.size} 项,去目标目录点「粘贴」"
                else -> "已剪切 ${paths.size} 项,去目标目录点「粘贴」"
            },
        )
    }

    private fun pasteClipboard() {
        val mode = clipMode ?: return
        val paths = clipPaths
        val dest = File(browsePath)
        Thread {
            var okCount = 0
            for (p in paths) {
                val f = File(p)
                if (!f.exists()) continue
                // 防自嵌套:目录不能粘贴进自身或其后代
                if (f.isDirectory) {
                    val self = f.canonicalPath
                    val target = dest.canonicalPath
                    if (target == self || target.startsWith(self + File.separator)) continue
                }
                val ok = if (mode == ClipMode.COPY) FileStore.copyTo(f, dest) else FileStore.moveTo(f, dest)
                if (ok) okCount++
            }
            val moved = mode == ClipMode.CUT
            act.runOnUiThread {
                if (moved) {
                    clipMode = null
                    clipPaths = emptyList()
                    updatePasteVisibility()
                }
                toast("已${if (moved) "移动" else "复制"} $okCount/${paths.size} 项")
                load()
            }
        }.start()
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
        bottomBar.visibility = View.VISIBLE
    }

    private fun toggleSelect(item: FileStore.Item) {
        if (!selected.add(item.path)) selected.remove(item.path)
        refreshSelectionBar()
    }

    private fun refreshSelectionBar() {
        selectText.text = "已选 ${selected.size} 项"
        shareBtn.text = "分享(${selected.size})"
        deleteBtn.text = "删除(${selected.size})"
        moreBtn.isEnabled = selected.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        bottomBar.visibility = View.GONE
        refreshSelectionBar()
    }

    // ── 批量操作 ────────────────────────────────────────────────────

    private fun selectedItems(): List<FileStore.Item> = selected.mapNotNull { p -> items.firstOrNull { it.path == p } }

    private fun shareSelected() {
        val sel = selectedItems()
        if (sel.isEmpty()) return
        val svc = ConnectionService.instance
        if (svc == null || !svc.hasConnectedDesktop()) {
            toast("未连接电脑——请先在首页配对并连接")
            return
        }
        if (pushing) return
        val files = sel.filter { !it.dir }
        val folders = sel.filter { it.dir }
        if (folders.isEmpty()) {
            pushQueue.clear()
            pushQueue.addAll(files.map { File(it.path) })
            pushing = true
            pushNext()
            return
        }
        // 含文件夹 → 自动打包为一个 ZIP 临时文件再推送
        Thread {
            val tmp = File(act.cacheDir, "APartner-${System.currentTimeMillis()}.zip")
            val ok = FileStore.zipOf(sel.map { File(it.path) }, tmp)
            act.runOnUiThread {
                if (!ok) {
                    toast("打包 ZIP 失败,已取消推送")
                } else {
                    pushQueue.clear()
                    pushQueue.add(tmp)
                    pushing = true
                    pushNext(cleanupAfter = tmp)
                }
            }
        }.start()
    }

    /** 依次推送队列;cleanupAfter 非空时全部推完删临时包。 */
    private fun pushNext(cleanupAfter: File? = null) {
        val next = pushQueue.removeFirstOrNull()
        if (next == null) {
            pushing = false
            pushRow.visibility = View.GONE
            cleanupAfter?.delete()
            toast("全部推送完成")
            exitSelection()
            return
        }
        pushRow.visibility = View.VISIBLE
        pushBar.progress = 0
        pushText.text = "推送中:${next.name}"
        val sent = ConnectionService.instance?.pushToDesktop(next, object : PushController.Listener {
            override fun onProgress(sentBytes: Long, total: Long) {
                val pct = if (total <= 0) 100 else ((sentBytes * 100) / total).toInt()
                act.runOnUiThread {
                    pushBar.progress = pct
                    pushText.text = "推送中:${next.name} $pct%"
                }
            }

            override fun onDone() {
                act.runOnUiThread {
                    toast("已发送:${next.name}")
                    pushNext(cleanupAfter)
                }
            }

            override fun onError(message: String) {
                act.runOnUiThread {
                    toast("推送失败:${next.name}($message)")
                    pushNext(cleanupAfter)
                }
            }
        })
        if (sent != true) {
            pushing = false
            pushRow.visibility = View.GONE
            cleanupAfter?.delete()
            toast("推送启动失败(连接可能已断开)")
        }
    }

    private fun deleteSelected() {
        val paths = selected.toList()
        AlertDialog.Builder(act)
            .setTitle("删除")
            .setMessage("删除已选 ${paths.size} 项?此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    var okCount = 0
                    for (p in paths) if (FileStore.delete(p)) okCount++
                    act.runOnUiThread {
                        toast("已删除 $okCount/${paths.size} 项")
                        exitSelection()
                        load()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 更多菜单:作用于全部已选项;单项追加 重命名/详情,浏览模式 zip 追加 解压到此处。 */
    private fun showMore() {
        if (selected.isEmpty()) return
        val sel = selectedItems()
        val single = sel.singleOrNull()
        val options = mutableListOf("复制", "剪切")
        val zipFile = single?.let { !it.dir && it.name.endsWith(".zip", ignoreCase = true) } == true
        if (sel.any { !it.dir }) options.add("压缩为 ZIP")
        if (single != null && zipFile && tab == "浏览") options.add("解压到此处")
        if (single != null) options.add("重命名")
        options.add("详情")
        AlertDialog.Builder(act)
            .setTitle(if (sel.size == 1) sel[0].name else "已选 ${sel.size} 项")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "复制" -> copySelected()
                    "剪切" -> cutSelected()
                    "压缩为 ZIP" -> zipSelected()
                    "解压到此处" -> single?.let { unzipHere(it) }
                    "重命名" -> single?.let { showRename(it) }
                    "详情" -> single?.let { showDetails(it) }
                }
            }
            .show()
    }

    private fun zipSelected() {
        val sel = selectedItems().filter { !it.dir || true } // 文件夹也参与打包
        if (sel.isEmpty()) return
        val outDir = if (tab == "浏览") File(browsePath) else File(ROOT, "Download")
        val baseName = if (sel.size == 1) sel[0].name.removeSuffix(".zip") else "APartner"
        Thread {
            val out = FileStore.uniqueChild(outDir, "$baseName.zip")
            val ok = FileStore.zipOf(sel.map { File(it.path) }, out)
            act.runOnUiThread {
                toast(if (ok) "已压缩为 ${out.name}" else "压缩失败")
                if (ok) load()
            }
        }.start()
    }

    private fun unzipHere(item: FileStore.Item) {
        val zip = File(item.path)
        val dest = File(browsePath)
        Thread {
            val ok = FileStore.unzip(zip, dest)
            act.runOnUiThread {
                toast(if (ok) "已解压到当前目录" else "解压失败(损坏或路径非法)")
                if (ok) load()
            }
        }.start()
    }

    private fun showRename(item: FileStore.Item) {
        val input = EditText(act).apply {
            setText(item.name)
            setSelection(item.name.length)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(act, Ui.SURFACE_HIGH, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(act)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton
                Thread {
                    val ok = FileStore.rename(item.path, newName)
                    act.runOnUiThread {
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
        AlertDialog.Builder(act)
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
        val input = EditText(act).apply {
            hint = "文件夹名称"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(act, Ui.SURFACE_HIGH, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(act)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Thread {
                    val ok = FileStore.mkdir(browsePath, name)
                    act.runOnUiThread {
                        toast(if (ok) "已创建" else "创建失败(重名或非法字符)")
                        if (ok) load()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
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
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val tile = TextView(act).apply {
                    gravity = Gravity.CENTER
                    textSize = 17f
                }
                line.addView(tile, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                val name = TextView(act).apply {
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                }
                val meta = TextView(act).apply { textSize = 11f; setTextColor(Ui.TEXT_3) }
                col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                col.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val check = TextView(act).apply {
                    textSize = 15f
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                }
                check.background = Ui.card(act, Ui.SURFACE_HIGH, 999, Ui.STROKE, 1)
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
            holder.tile.background = Ui.tile(act, item.tileColor)
            holder.name.text = item.name
            holder.meta.text = "${item.sizeText} · ${item.timeText}"
            val checked = item.path in selected
            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.check.text = if (checked) "✓" else ""
            holder.check.background = if (checked) {
                Ui.card(act, Ui.ACCENT, 999, strokeWidth = 0)
            } else {
                Ui.card(act, Ui.SURFACE_HIGH, 999, Ui.STROKE, 1)
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
