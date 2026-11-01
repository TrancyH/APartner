package com.amoolink.companion

import android.app.Activity
import com.amoolink.companion.mta.MtaConst
import com.amoolink.companion.mta.MtaSenderService
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    private var pushTarget: String? = null

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
            sheetOverlay != null -> {
                dismissSheet()
                true
            }
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
            return
        }
        // 单击文件/分类页条目 → 底部滑出分享面板
        showShareSheet(listOf(item))
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
        showShareSheet(sel)
    }

    // ── 底部滑出分享面板(参考厂商文件管理「打开方式」面板)──────────

    private var sheetOverlay: FrameLayout? = null

    private class MtaFound(
        val device: android.bluetooth.BluetoothDevice,
        val id: String,
        val name: String,
        val supports5G: Boolean,
    )

    private var mtaScanCallback: android.bluetooth.le.ScanCallback? = null
    private val mtaFound = LinkedHashMap<String, MtaFound>()

    private fun topRoundedPanel(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            val r = dp(22).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Ui.SURFACE)
        }

    private fun dismissSheet() {
        val overlay = sheetOverlay ?: return
        sheetOverlay = null
        stopMtaScan()
        val panel = overlay.getChildAt(1)
        val scrim = overlay.getChildAt(0)
        panel.animate().translationY(panel.height.toFloat()).setDuration(220)
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
            .start()
        scrim.animate().alpha(0f).setDuration(220).start()
    }

    private fun showShareSheet(items: List<FileStore.Item>) {
        if (sheetOverlay != null) return
        val content = act.findViewById<ViewGroup>(android.R.id.content)

        val scrim = View(act).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
        scrim.setOnClickListener { dismissSheet() }

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = topRoundedPanel()
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }

        // 把手
        panel.addView(
            View(act).apply { background = Ui.pill(act, Ui.TEXT_3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )

        // 文件头卡片
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val first = items.first()
        val headTile = TextView(act).apply {
            gravity = Gravity.CENTER
            textSize = 20f
        }
        headTile.background = Ui.tile(act, first.tileColor)
        head.addView(headTile, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val headCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(Ui.text(act, if (items.size == 1) first.name else "已选 ${items.size} 项", 16, Ui.TEXT, Ui.TITLE))
        headCol.addView(
            Ui.text(
                act,
                if (items.size == 1) "${first.sizeText} · ${first.timeText}" else "共 ${items.sumOf { it.size } / 1024} KB",
                12,
                Ui.TEXT_3,
            ),
        )
        head.addView(headCol, LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f))
        if (items.size == 1 && !first.dir && FileStore.isImage(first.name)) {
            headTile.visibility = View.INVISIBLE
            FileStore.requestThumb(first.path, 128) { bmp ->
                act.runOnUiThread {
                    if (bmp != null) {
                        headTile.text = ""
                        headTile.background = android.graphics.drawable.BitmapDrawable(act.resources, bmp)
                        headTile.visibility = View.VISIBLE
                    } else {
                        headTile.text = first.icon
                        headTile.visibility = View.VISIBLE
                    }
                }
            }
        }
        panel.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bodyScroll = android.widget.ScrollView(act).apply { setBackgroundColor(Color.TRANSPARENT) }
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll.addView(body)
        panel.addView(
            bodyScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(6) },
        )

        val overlay = FrameLayout(act)
        overlay.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        content.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        sheetOverlay = overlay
        overlay.isFocusableInTouchMode = true
        overlay.requestFocus()
        overlay.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                dismissSheet()
                true
            } else {
                false
            }
        }

        // 滑入动画
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        scrim.animate().alpha(1f).setDuration(200).start()

        fillShareSections(body, items)
    }

    private fun sheetSection(body: LinearLayout, title: String): LinearLayout {
        body.addView(
            Ui.text(act, title, 11, Ui.TEXT_3, Ui.TITLE).apply {
                setPadding(dp(12), dp(10), dp(12), dp(2)); letterSpacing = 0.06f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val slot = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(slot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return slot
    }

    private fun sheetRow(glyph: String, glyphBg: Int?, title: String, sub: String, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (onClick != null) {
                background = Ui.ripple(act, Ui.card(act, Color.TRANSPARENT, 12, strokeWidth = 0))
                setOnClickListener { onClick() }
            }
        }
        val tile = TextView(act).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            text = glyph
            if (glyphBg != null) background = Ui.tile(act, glyphBg)
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(act, title, 14, if (onClick != null) Ui.TEXT else Ui.TEXT_3))
        col.addView(Ui.text(act, sub, 11, Ui.TEXT_3))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f).apply { marginStart = dp(4) })
        row.addView(Ui.text(act, "›", 14, Ui.TEXT_3))
        return row
    }

    private fun TextView.tileText(g: String): TextView {
        text = g
        return this
    }

    private fun fillShareSections(body: LinearLayout, items: List<FileStore.Item>) {
        val svc = ConnectionService.instance
        val conns = svc?.connectedDesktops() ?: emptyList()

        // 1. 发送到电脑
        val pcSlot = sheetSection(body, "发送到电脑")
        if (conns.isEmpty()) {
            pcSlot.addView(Ui.text(act, "未连接电脑——请在首页配对并连接", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) })
        } else {
            for (fp in conns) {
                val row = sheetRow("💻", null, svc?.pinnedName(fp) ?: fp, "在线 · 点击发送") {
                    exitSelection()
                    startPush(items, fp)
                    dismissSheet()
                }
                pcSlot.addView(row)
            }
        }

        // 2. 发送到手机(APartner)
        val phoneSlot = sheetSection(body, "发送到手机(APartner)")
        val phoneLoading = Ui.text(act, "正在获取…", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        phoneSlot.addView(phoneLoading)
        val conn = svc?.firstDesktopConn()
        if (conn != null) {
            Thread {
                val phones = try {
                    DesktopRpc.phonesList(conn)
                } catch (e: Exception) {
                    emptyList()
                }
                act.runOnUiThread {
                    if (sheetOverlay == null) return@runOnUiThread
                    phoneSlot.removeAllViews()
                    if (phones.isEmpty()) {
                        phoneSlot.addView(
                            Ui.text(act, "附近没有其他安装了 APartner 的手机", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) },
                        )
                        return@runOnUiThread
                    }
                    for (p in phones) {
                        phoneSlot.addView(
                            sheetRow("📱", null, p.name, "在线 · 经电脑中继接收") {
                                exitSelection()
                                startRelay(items, p)
                                dismissSheet()
                            },
                        )
                    }
                }
            }.start()
        } else {
            phoneSlot.addView(Ui.text(act, "电脑未连接,无法发现其他手机", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) })
        }

        // 3. 互传联盟设备(BLE 发现,6 秒窗口)
        val mtaSlot = sheetSection(body, "互传联盟设备")
        val mtaLoading = Ui.text(act, "正在扫描附近开启互传的设备…", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        mtaSlot.addView(mtaLoading)
        startMtaScan(mtaSlot, mtaLoading, items)

        // 4. 其他应用(系统 ACTION_SEND 目标)
        val appSlot = sheetSection(body, "其他应用")
        fillThirdPartyApps(appSlot, items)
    }

    /** 互传联盟 BLE 扫描(照搬 CatShare ShareActivity 的过滤与解析)。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startMtaScan(slot: LinearLayout, loading: TextView, items: List<FileStore.Item>) {
        val btManager = act.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = btManager.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            loading.text = "蓝牙未开启,无法发现互传联盟设备"
            return
        }
        // BLUETOOTH_SCAN 是运行时权限:未授予时 startScan 会抛 SecurityException
        if (act.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loading.text = "缺少蓝牙扫描权限——请在首页权限引导中授权"
            return
        }
        mtaFound.clear()
        // 双 filter(OR):①小米原生广播的标准 base 3331(实测 HyperOS AD 形式)
        // ②CatShare 形式 008123456789 base(兼容 CatShare/其他类原生接收端)
        val filterXiaomi = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(java.util.UUID.fromString("00003331-0000-1000-8000-00805f9b34fb")))
            .build()
        val filterCatShare = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(MtaConst.ADV_SERVICE_UUID))
            .build()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(true)
            .build()
        val callback = object : android.bluetooth.le.ScanCallback() {
            private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val record = result.scanRecord
                val now = System.currentTimeMillis()
                val last = seen[result.device.address] ?: 0
                if (now - last < 2000) return
                seen[result.device.address] = now
                val svcUuids = record?.serviceUuids?.joinToString { it.uuid.toString() } ?: "-"
                val svcData = record?.serviceData?.entries?.joinToString(";") { (u, d) ->
                    "${u.uuid}=" + d.take(8).joinToString("") { "%02x".format(it) }
                } ?: "-"
                val mfg = record?.manufacturerSpecificData?.let { m ->
                    (0 until m.size()).map { k -> String.format("0x%04x[%dB]", m.keyAt(k), m.valueAt(k).size) }.joinToString(",")
                } ?: "-"
                android.util.Log.i(
                    "MtaScanDebug",
                    "addr=${result.device.address} name=${result.device.name} rssi=${result.rssi} " +
                        "uuids=[$svcUuids] svcData=[$svcData] mfg=[$mfg] raw=${record?.bytes?.take(20)?.joinToString("") { "%02x".format(it) }}",
                )
                val dataMap = record?.serviceData ?: return
                var supports5G = true
                var hasBrand = false
                var id = ""
                var name = ""
                for ((pu, d) in dataMap) {
                    if (d.size == 6 && pu.uuid == MtaConst.ADV_DATA_5G_BRAND_UUID) {
                        // UUID 字节大端:[2]=支持5G [3]=品牌
                        val buf = java.nio.ByteBuffer.allocate(16)
                        buf.putLong(pu.uuid.mostSignificantBits)
                        buf.putLong(pu.uuid.leastSignificantBits)
                        supports5G = buf.array()[2].toInt() == 1
                        hasBrand = true
                    }
                    if (pu.uuid == MtaConst.SCAN_RESP_UUID && d.size == 27) {
                        id = String.format("%04x", (d[8].toInt() shl 8) or d[9].toInt())
                        var nameBytes = d.copyOfRange(10, 26)
                        if (nameBytes[15] == '\t'.code.toByte()) nameBytes = nameBytes.copyOfRange(0, 15)
                        name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    }
                }
                // 小米原生广播:name 缺省、id 在 svcData/scanResp 因厂商而异 → 兜底
                val finalName = name.ifEmpty {
                    (result.scanRecord?.deviceName ?: result.device.name ?: "").ifEmpty { "MTA-${result.device.address.takeLast(5)}" }
                }
                // 排除自身:广播 serviceData(000001ff-…)前 2 字节 == 本机 SENDER_ID
                // (不能按 UUID base 排除——会误杀同为该 base 形式的其他互传设备,如 HUAWEI)
                val selfBeacon = (result.scanRecord?.serviceData?.get(
                    android.os.ParcelUuid(MtaConst.ADV_DATA_5G_BRAND_UUID),
                ))?.let { d -> d.size >= 2 && String.format("%04x", (d[0].toInt() shl 8) or d[1].toInt()) == MtaConst.SENDER_ID } == true
                if (selfBeacon) {
                    return@onScanResult
                }
                val finalId = id.ifEmpty { result.device.address }
                Log.i("MtaScan", "discovered id=$finalId name=$finalName 5g=$supports5G rssi=${result.rssi}")
                mtaFound[finalId] = MtaFound(result.device, finalId, finalName, supports5G)
                renderMtaRows(slot, loading, items)
            }

            override fun onScanFailed(errorCode: Int) {
                act.runOnUiThread { loading.text = "扫描失败(err=$errorCode)" }
            }
        }
        mtaScanCallback = callback
        try {
            scanner.startScan(listOf(filterXiaomi, filterCatShare), settings, callback)
        } catch (e: SecurityException) {
            mtaScanCallback = null
            loading.text = "蓝牙扫描被拒绝——请授权 BLUETOOTH_SCAN"
            return
        }
        act.runOnUiThread {
            if (mtaFound.isNotEmpty()) renderMtaRows(slot, loading, items)
        }
        // 6 秒窗口后停止扫描并落定列表
        act.window.decorView.postDelayed({
            stopMtaScan()
            if (sheetOverlay != null) renderMtaRows(slot, loading, items)
        }, 12_000)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopMtaScan() {
        mtaScanCallback?.let {
            try {
                act.getSystemService(Context.BLUETOOTH_SERVICE).let { svc ->
                    (svc as android.bluetooth.BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(it)
                }
            } catch (_: Exception) {
            }
        }
        mtaScanCallback = null
    }

    private fun renderMtaRows(slot: LinearLayout, loading: TextView, items: List<FileStore.Item>, finished: Boolean = false) {
        if (sheetOverlay == null) return
        slot.removeAllViews()
        if (mtaFound.isEmpty()) {
            slot.addView(
                if (finished) Ui.text(act, "未发现互传联盟设备(对方需开启互传且与本机同网段蓝牙范围)", 12, Ui.TEXT_3)
                    .apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
                else loading,
            )
            return
        }
        for (d in mtaFound.values) {
            slot.addView(
                sheetRow("💠", Ui.TILE_DOC, d.name, "互传联盟 · 点击直发") {
                    val paths = items.map { it.path }
                    val started = MtaSenderService.start(act, d.device, d.supports5G, d.name, paths)
                    if (started) {
                        toast("正在发送到 ${d.name}(互传联盟)")
                        pushRow.visibility = View.VISIBLE
                        pushText.text = "正在发送到 ${d.name}(互传联盟直连)"
                        exitSelection()
                        dismissSheet()
                    } else {
                        toast("已有传输在进行,请稍候")
                    }
                },
            )
        }
    }

    /** 其他应用:系统 ACTION_SEND 目标,四列网格(参考厂商文件管理分享面板)。 */
    private fun fillThirdPartyApps(slot: LinearLayout, items: List<FileStore.Item>) {
        val mime = thirdPartyMime(items)
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (mime.isNotEmpty()) type = mime else type = "*/*"
        }
        val apps = act.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != act.packageName }
        if (apps.isEmpty()) {
            slot.addView(Ui.text(act, "没有可接收的应用", 12, Ui.TEXT_3).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) })
            return
        }
        slot.addView(
            Ui.text(act, "用其他应用打开", 11, Ui.TEXT_3, Ui.TITLE).apply { setPadding(dp(12), dp(8), dp(12), dp(2)) },
        )
        var row: LinearLayout? = null
        for ((idx, info) in apps.withIndex()) {
            if (idx % 4 == 0) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                slot.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = Ui.ripple(act, Ui.card(act, Color.TRANSPARENT, 12, strokeWidth = 0))
                setOnClickListener {
                    launchThirdParty(info, items, mime)
                    dismissSheet()
                }
            }
            val icon = android.widget.ImageView(act).apply {
                setImageDrawable(info.loadIcon(act.packageManager))
            }
            cell.addView(
                icon,
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
            cell.addView(
                Ui.text(act, info.loadLabel(act.packageManager).toString(), 11, Ui.TEXT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 1
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                },
            )
            row?.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun thirdPartyMime(items: List<FileStore.Item>): String {
        if (items.isEmpty()) return "*/*"
        if (items.size == 1 && !items[0].dir) {
            val ext = items[0].name.substringAfterLast('.', "")
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
        }
        return "*/*"
    }

    private fun launchThirdParty(info: android.content.pm.ResolveInfo, items: List<FileStore.Item>, mime: String) {
        try {
            val uris = items.map { ShareFileProvider.uriFor(it.path) }
            val send: Intent = if (items.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.setClassName(info.activityInfo.packageName, info.activityInfo.name)
            act.startActivity(send)
        } catch (e: Exception) {
            toast("无法打开该应用:${e.message}")
        }
    }

    /** 手机↔手机:请求桌面中继到目标手机(桌面 fs_read 源机 + fs_write 目标机)。 */
    private fun startRelay(items: List<FileStore.Item>, target: DesktopRpc.PhoneTarget) {
        val conn = ConnectionService.instance?.firstDesktopConn()
        if (conn == null) {
            toast("电脑连接已断开")
            return
        }
        toast("正在经电脑中继 ${items.size} 项到 ${target.name}…")
        Thread {
            try {
                val resp = DesktopRpc.relay(conn, target.id, items.map { it.path })
                act.runOnUiThread {
                    if (resp.optBoolean("ok", false)) {
                        toast("电脑已受理:${items.size} 项 → ${target.name}(完成后电脑会通知)")
                    } else {
                        toast("中继被拒绝:${resp.optString("reason", "未知")}")
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread { toast("中继失败:${e.message}") }
            }
        }.start()
    }

    /** 启动推送队列(指定目标桌面);含文件夹时打包 ZIP 临时文件。 */    private fun startPush(items: List<FileStore.Item>, fp: String?) {
        if (pushing) {
            toast("已有推送在进行,请稍候")
            return
        }
        pushTarget = fp
        val folders = items.filter { it.dir }
        if (folders.isEmpty()) {
            pushQueue.clear()
            pushQueue.addAll(items.map { File(it.path) })
            pushing = true
            pushNext()
            return
        }
        Thread {
            val tmp = File(act.cacheDir, "APartner-${System.currentTimeMillis()}.zip")
            val ok = FileStore.zipOf(items.map { File(it.path) }, tmp)
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
        val sent = ConnectionService.instance?.pushToDesktop(pushTarget, next, object : PushController.Listener {
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
                // 磁块:圆角裁剪容器,内含 缩略图(图片文件)或 类型 emoji(其他)
                val tileWrap = FrameLayout(act).apply {
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                        }
                    }
                }
                val tileImg = android.widget.ImageView(act).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                val tileText = TextView(act).apply {
                    gravity = Gravity.CENTER
                    textSize = 17f
                }
                tileWrap.addView(
                    tileImg,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                tileWrap.addView(
                    tileText,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                line.addView(tileWrap, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
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
                holder = RowHolder(tileWrap, tileImg, tileText, name, meta, check, line)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as RowHolder
            }
            val item = filtered[position]
            holder.boundPath = item.path
            holder.tileWrap.background = Ui.tile(act, item.tileColor)
            holder.name.text = item.name
            holder.meta.text = "${item.sizeText} · ${item.timeText}"
            // 图片文件 → 异步缩略图;其他 → 类型 emoji
            val isImg = !item.dir && FileStore.isImage(item.name)
            if (isImg) {
                holder.tileText.visibility = View.GONE
                holder.tileImg.visibility = View.VISIBLE
                val cached = FileStore.thumbFromCache(item.path)
                if (cached != null) {
                    holder.tileImg.setImageBitmap(cached)
                } else {
                    holder.tileImg.setImageBitmap(null)
                    FileStore.requestThumb(item.path, 128) { bmp ->
                        act.runOnUiThread {
                            // 行回收防串图:绑定路径仍是该行时才上屏
                            if (holder.boundPath == item.path && bmp != null) {
                                holder.tileImg.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } else {
                holder.tileImg.visibility = View.GONE
                holder.tileText.visibility = View.VISIBLE
                holder.tileText.text = item.icon
            }
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
        val tileWrap: FrameLayout,
        val tileImg: android.widget.ImageView,
        val tileText: TextView,
        val name: TextView,
        val meta: TextView,
        val check: TextView,
        val row: LinearLayout,
    ) {
        var boundPath: String? = null
    }
}
