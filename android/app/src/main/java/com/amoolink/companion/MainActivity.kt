package com.amoolink.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import com.amoolink.companion.mta.MtaReceiverService
import com.amoolink.companion.mta.MtaUiBridge
import com.amoolink.companion.mta.MtaState
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 伴侣 APK 主壳(暗夜链路设计系统):底部 dock 切换 首页 / 文件管理 两页,
 * 页面常驻 FrameLayout(切换不丢列表状态)。
 * 首页 = Hero 连接可视化卡(手机↔电脑链路动效,唯一记忆点)+ 配对(OTP+SAS)
 * + 权限列表 + 已配对电脑;文件页 = FilesPage。逻辑与 v1 一致。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusPill: TextView
    private lateinit var heroStatus: TextView
    private lateinit var connectionView: ConnectionView
    private lateinit var pairingCard: LinearLayout
    private lateinit var otpText: TextView
    private lateinit var ipText: TextView
    private lateinit var sasText: TextView
    private lateinit var sasLabel: TextView
    private lateinit var confirmBtn: Button
    private lateinit var pairBtn: Button
    private lateinit var permRights: MutableMap<String, TextView>
    private lateinit var pairedList: LinearLayout
    private lateinit var pairedEmpty: TextView
    private lateinit var mtaMacRight: TextView

    // 壳:dock + 双页
    private lateinit var homeRoot: View
    private lateinit var filesRoot: View
    private lateinit var dockHomeIcon: DockIconView
    private lateinit var dockHomeLabel: TextView
    private lateinit var dockFilesIcon: DockIconView
    private lateinit var dockFilesLabel: TextView
    private lateinit var filesPage: FilesPage
    private var currentTab = "home"

    private var renderedPaired: Set<String> = emptySet()

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        buildUi()
        // MTA 传输请求 → 应用内确认面板(前台时;后台回落通知)
        MtaUiBridge.panelEmitter = { req: MtaUiBridge.IncomingRequest ->
            showIncomingPanel(req)
        }
        // 开机自启默认开(引导页语义;关闭存 prefs)
        getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("autostart", true).apply()
        startServiceExplicit()
        // 恢复互传联盟接收开关
        if (getSharedPreferences(MtaState.PREFS, Context.MODE_PRIVATE).getBoolean("receive_enabled", false)) {
            MtaReceiverService.setEnabled(this, true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab == "files") {
            if (filesPage.onBackPressed()) return
            switchTab("home")
            return
        }
        super.onBackPressed()
    }

    private fun switchTab(t: String) {
        if (currentTab == t) return
        currentTab = t
        homeRoot.visibility = if (t == "home") View.VISIBLE else View.GONE
        filesRoot.visibility = if (t == "files") View.VISIBLE else View.GONE
        markDock(t)
        if (t == "files") filesPage.onResume()
    }

    private fun markDock(t: String) {
        dockHomeIcon.active = t == "home"
        dockFilesIcon.active = t == "files"
        dockHomeLabel.setTextColor(if (t == "home") Ui.ACCENT else Ui.TEXT_3)
        dockFilesLabel.setTextColor(if (t == "files") Ui.ACCENT else Ui.TEXT_3)
    }

    /** dock 项:自绘图标 + 标签;FrameLayout 承载 col(layout_gravity=center,绝对居中)。 */
    private fun buildDockItem(kind: DockIconView.Kind, label: String): Triple<FrameLayout, DockIconView, TextView> {
        val icon = DockIconView(this, kind)
        val lab = Ui.text(this, label, 11, Ui.TEXT_3).apply {
            gravity = Gravity.CENTER_HORIZONTAL // 文本在自身宽度内居中(终极兜底)
            setPadding(0, dp(3), 0, 0)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = Ui.ripple(this@MainActivity, Ui.card(this@MainActivity, Color.TRANSPARENT, 12, strokeWidth = 0))
            addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(lab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val wrap = FrameLayout(this)
        wrap.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ),
        )
        return Triple(wrap, icon, lab)
    }

    private fun buildDock(): LinearLayout {
        val (homeCol, homeIcon, homeLab) = buildDockItem(DockIconView.Kind.HOME, "首页")
        val (filesCol, filesIcon, filesLab) = buildDockItem(DockIconView.Kind.FILES, "文件管理")
        dockHomeIcon = homeIcon
        dockHomeLabel = homeLab
        dockFilesIcon = filesIcon
        dockFilesLabel = filesLab
        homeCol.setOnClickListener { switchTab("home") }
        filesCol.setOnClickListener { switchTab("files") }
        markDock("home")
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE_HIGH)
        }
        dock.addView(homeCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        dock.addView(filesCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return dock
    }

    override fun onResume() {
        super.onResume()
        MtaState.appInForeground = true
        handler.post(refresh)
        if (currentTab == "files") filesPage.onResume()
        // 焦点在手:推送当前剪贴板到已连接桌面(M5-6 手机→电脑方向)
        val clip = ConnectionService.instance?.readClipboard()
        if (!clip.isNullOrEmpty()) {
            ConnectionService.instance?.broadcastEvent(org.json.JSONObject().apply {
                put("id", org.json.JSONObject.NULL)
                put("type", "clipboard")
                put("text", clip)
                put("src", "phone")
                put("ts", System.currentTimeMillis())
            })
        }
    }

    override fun onPause() {
        super.onPause()
        MtaState.appInForeground = false
        handler.removeCallbacks(refresh)
    }

    private fun startServiceExplicit() {
        startForegroundService(Intent(this, ConnectionService::class.java))
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    // ── UI 构建 ─────────────────────────────────────────────────────

    private fun buildUi() {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(Ui.BG)
        }
        val root = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        root.addView(col)

        fun marginTop(top: Int): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(params).apply { topMargin = dp(top) }

        // 顶行:品牌 + 状态胶囊
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(this, "APartner", 22, Ui.TEXT, Ui.TITLE))
        val spacer = View(this)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        statusPill = Ui.text(this, "○ 未连接", 11, Ui.TEXT_2, Ui.TITLE).apply {
            background = Ui.pill(this@MainActivity, Ui.SURFACE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        top.addView(statusPill)
        col.addView(top, params)

        // Hero 连接卡(记忆点:手机↔电脑链路)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = Ui.card(this@MainActivity, Ui.SURFACE, 16, Ui.ACCENT, 1)
        }
        connectionView = ConnectionView(this)
        hero.addView(connectionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
        heroStatus = Ui.text(this, "服务启动中…", 13, Ui.TEXT_2).apply { gravity = Gravity.CENTER }
        hero.addView(heroStatus, params)
        col.addView(hero, marginTop(18))

        // 配对卡(配对模式时展开)
        pairingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = Ui.card(this@MainActivity, Ui.SURFACE, 16)
            visibility = View.GONE
        }
        pairingCard.addView(
            Ui.text(this, "在电脑端 AmooLink 输入此配对码", 12, Ui.TEXT_2),
            params,
        )
        otpText = Ui.text(this, "· · · · · · · ·", 38, Ui.ACCENT, Ui.DISPLAY).apply { gravity = Gravity.CENTER }
        pairingCard.addView(otpText, params)
        ipText = Ui.text(this, "", 11, Ui.TEXT_3).apply { gravity = Gravity.CENTER }
        pairingCard.addView(ipText, params)
        sasLabel = Ui.text(this, "与电脑端比对 6 位校验码", 12, Ui.TEXT_2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
        }
        pairingCard.addView(sasLabel, params)
        sasText = Ui.text(this, "— — — — — —", 30, Ui.TEXT, Ui.DISPLAY).apply { gravity = Gravity.CENTER }
        pairingCard.addView(sasText, params)
        val pairBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        confirmBtn = Ui.primaryButton(this@MainActivity, "数字一致,确认配对").apply { isEnabled = false }
        confirmBtn.setOnClickListener {
            // 确认会写 TLS socket,必须在后台线程(NetworkOnMainThreadException)
            Thread { ConnectionService.instance?.confirmPairing() }.start()
            toast("已确认配对")
        }
        pairBtns.addView(confirmBtn)
        pairBtns.addView(
            Ui.ghostButton(this@MainActivity, "取消").apply {
                setOnClickListener { ConnectionService.instance?.cancelPairing() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            },
        )
        pairingCard.addView(pairBtns, params)
        col.addView(pairingCard, marginTop(12))

        // 配对入口
        pairBtn = Ui.primaryButton(this@MainActivity, "连接新电脑").apply {
            setOnClickListener {
                if (ConnectionService.instance == null) startServiceExplicit()
                handler.postDelayed({
                    ConnectionService.Pairing.begin()
                    toast("配对模式已开启(5 分钟内有效)")
                    renderStatus()
                }, 300)
            }
        }
        col.addView(pairBtn, marginTop(12))

        // 权限
        col.addView(Ui.sectionHeader(this, "权限"), marginTop(22))
        permRights = mutableMapOf()
        val permCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity, Ui.SURFACE, 14)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        Ui.settingRow(this, "🔔", "通知使用权", "", { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            .let { (row, right) -> permCard.addView(row); permRights["notif"] = right }
        permCard.addView(dividerLine())
        Ui.settingRow(this, "📨", "短信 / 通讯录 / 电话状态", "", { requestRuntimePerms() })
            .let { (row, right) -> permCard.addView(row); permRights["comm"] = right }
        permCard.addView(dividerLine())
        Ui.settingRow(this, "🗂", "所有文件访问", "", { openFsPermission() })
            .let { (row, right) -> permCard.addView(row); permRights["fs"] = right }
        permCard.addView(dividerLine())
        Ui.settingRow(this, "🔋", "电池优化白名单", "", {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                toast("请到系统设置手动设置")
            }
        }).let { (row, right) -> permCard.addView(row); permRights["battery"] = right }
        col.addView(permCard, marginTop(8))

        // 互传联盟
        col.addView(Ui.sectionHeader(this, "互传联盟"), marginTop(22))
        val mtaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity, Ui.SURFACE, 14)
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val receiveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        receiveRow.addView(
            Ui.text(this, "互传联盟接收(原生小米/OPPO/vivo 可发送到本机)", 14, Ui.TEXT),
            LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f),
        )
        val mtaSwitch = Switch(this).apply {
            isChecked = getSharedPreferences(MtaState.PREFS, Context.MODE_PRIVATE)
                .getBoolean("receive_enabled", false)
        }
        mtaSwitch.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            getSharedPreferences(MtaState.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("receive_enabled", checked).apply()
            MtaReceiverService.setEnabled(this@MainActivity, checked)
        }
        receiveRow.addView(mtaSwitch)
        mtaCard.addView(receiveRow)
        mtaCard.addView(dividerLine())
        val macSetting = Ui.settingRow(this, "🔗", "p2p0 MAC(MTA 认证)", "", { fetchMtaMacFromDesktop() })
        mtaMacRight = macSetting.second
        val macSettingRow: android.view.View = macSetting.first
        mtaCard.addView(macSettingRow)
        mtaCard.addView(dividerLine())
        val (manualRow, _) = Ui.settingRow(this, "✎", "手动填 p2p0 MAC", "", { showManualMacDialog() })
        mtaCard.addView(manualRow)
        col.addView(mtaCard, marginTop(8))

        // 已配对电脑
        col.addView(Ui.sectionHeader(this, "已配对电脑"), marginTop(22))
        pairedList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity, Ui.SURFACE, 14)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        pairedEmpty = Ui.text(this, "尚未配对;点上方「连接新电脑」开始。", 13, Ui.TEXT_3)
            .apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        pairedList.addView(pairedEmpty)
        col.addView(pairedList, marginTop(8))

        // 常规
        col.addView(Ui.sectionHeader(this, "常规"), marginTop(22))
        val generalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@MainActivity, Ui.SURFACE, 14)
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        autoRow.addView(Ui.text(this, "开机自启(随设备启动常驻)", 14, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f))
        val autoSwitch = Switch(this).apply {
            isChecked = getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE)
                .getBoolean("autostart", true)
        }
        autoSwitch.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("autostart", checked).apply()
        }
        autoRow.addView(autoSwitch)
        generalCard.addView(autoRow)
        generalCard.addView(dividerLine())
        val (restartRow, _) = Ui.settingRow(this, "↻", "重启服务", "›") {
            startServiceExplicit()
            toast("服务已启动")
        }
        generalCard.addView(restartRow)
        col.addView(generalCard, marginTop(8))

        // 壳:FrameLayout 双页 + 底部 dock
        homeRoot = root
        filesPage = FilesPage(this)
        filesRoot = filesPage.build()
        filesRoot.visibility = View.GONE

        val container = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        container.addView(homeRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(filesRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG) }
        screen.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        screen.addView(
            View(this).apply { setBackgroundColor(Ui.STROKE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )
        screen.addView(buildDock(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(screen)
    }

    private fun dividerLine(): View =
        View(this).apply {
            setBackgroundColor(Ui.STROKE)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.setMargins(dp(10), 0, dp(10), 0)
            layoutParams = lp
        }

    // ── 状态渲染 ────────────────────────────────────────────────────

    private fun renderStatus() {
        if (::mtaMacRight.isInitialized) {
            val mac = MtaState.mac(this)
            mtaMacRight.text = if (MtaState.isMacInjected(this)) "$mac ›" else "${mac.take(8)}…(占位) ›"
        }
        val svc = ConnectionService.instance
        val conns = svc?.connectedDesktops() ?: emptyList()
        val online = conns.isNotEmpty()

        statusPill.text = if (online) "● 已连接" else "○ 未连接"
        statusPill.setTextColor(if (online) Ui.OK else Ui.TEXT_2)
        statusPill.background = Ui.pill(this, if (online) Color.parseColor("#14261F") else Ui.SURFACE)
        connectionView.connected = online
        heroStatus.text = when {
            svc == null -> "服务未运行"
            !online -> "等待电脑连接…\n在电脑端 AmooLink 添加此设备"
            else -> conns.joinToString(" · ") { svc.pinnedName(it) }
        }
        heroStatus.setTextColor(if (online) Ui.TEXT else Ui.TEXT_2)

        // 配对卡
        val pairing = ConnectionService.Pairing.active()
        pairingCard.visibility = if (pairing) View.VISIBLE else View.GONE
        pairBtn.visibility = if (pairing) View.GONE else View.VISIBLE
        if (pairing) {
            val otp = ConnectionService.Pairing.otp ?: ""
            val ip = ConnectionService.instance?.localIps()?.firstOrNull() ?: "?"
            otpText.text = otp.ifEmpty { "· · · · · · · ·" }
            ipText.text = "局域网地址 $ip:${Proto.PORT}"
            val sas = ConnectionService.Pairing.pendingSas
            sasText.text = sas ?: "— — — — — —"
            sasLabel.text = if (sas == null) "等待电脑连接后,双方显示 6 位校验码" else "两屏数字一致,再点下方确认"
            sasText.setTextColor(if (sas == null) Ui.TEXT_3 else Ui.TEXT)
            confirmBtn.isEnabled = sas != null
        }

        // 权限行右态
        permRights["notif"]?.text = notifGrantedText()
        permRights["comm"]?.text = commGrantedText()
        permRights["fs"]?.text = fsGrantedText()
        permRights["battery"]?.text = "›"

        // 已配对电脑列表(指纹变化时重建)
        if (svc != null) {
            val pinned = svc.pinnedFps()
            if (pinned != renderedPaired) {
                renderedPaired = pinned
                pairedList.removeAllViews()
                if (pinned.isEmpty()) pairedList.addView(pairedEmpty)
                for (fp in pinned.sorted()) {
                    val onlineFp = conns.contains(fp)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(8), dp(10))
                        background = Ui.ripple(this@MainActivity, Ui.card(this@MainActivity, Color.TRANSPARENT, 10, strokeWidth = 0))
                    }
                    row.addView(
                        Ui.text(this, if (onlineFp) "●" else "○", 13, if (onlineFp) Ui.OK else Ui.TEXT_3),
                        LinearLayout.LayoutParams(dp(20), ViewGroupHelpers.wrap()),
                    )
                    val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    nameCol.addView(Ui.text(this, svc.pinnedName(fp), 14, Ui.TEXT, Ui.TITLE))
                    nameCol.addView(Ui.text(this, if (onlineFp) "在线" else "离线", 11, Ui.TEXT_3))
                    row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroupHelpers.wrap(), 1f))
                    row.addView(
                        Ui.ghostButton(this@MainActivity, "忘记", danger = true).apply {
                            setOnClickListener {
                                svc.forget(fp)
                                toast("已忘记该电脑")
                                renderedPaired = emptySet()
                            }
                        },
                    )
                    pairedList.addView(row)
                }
            }
        }
    }

    private fun notifGrantedText(): String {
        val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return if (enabled.contains("$packageName/", ignoreCase = true)) "已开启 ›" else "去开启 ›"
    }

    private fun commGrantedText(): String {
        val need = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val granted = need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) "已授权 ›" else "去授权 ›"
    }

    private fun fsGrantedText(): String {
        val granted = if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return if (granted) "已授权 ›" else "去授权 ›"
    }

    /** 经桌面 adb 读取 p2p0 MAC 并注入(无桌面连接时引导手动)。 */
    private fun fetchMtaMacFromDesktop() {
        val svc = ConnectionService.instance
        val conn = svc?.firstDesktopConn()
        if (conn == null) {
            toast("未连接电脑——请在首页配对连接后重试")
            return
        }
        val ips = svc.localIps()
        toast("正在请电脑读取 p2p0 MAC…")
        Thread {
            try {
                val mac = DesktopRpc.mtaMacGet(conn, ips)
                runOnUiThread {
                    if (mac != null && MtaState.setMac(this, mac)) {
                        toast("已注入 p2p0 MAC:$mac")
                        renderStatus()
                    } else {
                        toast("电脑未能读取 MAC——可用「手动填 p2p0 MAC」兜底")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("读取失败:${e.message}(可手动填 MAC 兜底)") }
            }
        }.start()
    }

    private fun showManualMacDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "aa:bb:cc:dd:ee:ff(p2p0 网卡地址)"
            setText(MtaState.mac(applicationContext))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_3)
            background = Ui.card(this@MainActivity, Ui.SURFACE_HIGH, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("手动填 p2p0 MAC")
            .setMessage("发起一次互传时,在 ROM 的开发者选项/抓包中查看 p2p0 地址后填入")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                if (MtaState.setMac(this, input.text.toString())) {
                    toast("已保存")
                    renderStatus()
                } else {
                    toast("格式不合法")
                }
            }
            .setNegativeButton("清除") { _, _ ->
                getSharedPreferences(MtaState.PREFS, Context.MODE_PRIVATE).edit().remove("mac").apply()
                renderStatus()
            }
            .show()
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT < 30) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun openFsPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    toast("请到系统设置手动授予「所有文件访问」")
                }
            }
        } else {
            requestRuntimePerms()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── MTA 传输请求:应用内底部确认面板 ───────────────────────────

    private var incomingOverlay: FrameLayout? = null

    private fun showIncomingPanel(req: MtaUiBridge.IncomingRequest) {
        incomingOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val content = findViewById<ViewGroup>(android.R.id.content)

        val scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
        scrim.setOnClickListener { answerIncoming(false) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                val r = dp(22).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Ui.SURFACE)
            }
            setPadding(dp(20), dp(14), dp(20), dp(20))
        }
        panel.addView(
            View(this).apply { background = Ui.pill(this@MainActivity, Ui.TEXT_3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
        panel.addView(
            Ui.text(this, "收到互传请求", 18, Ui.TEXT, Ui.TITLE).apply {
                setPadding(0, dp(14), 0, dp(4))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        panel.addView(
            Ui.text(this, "${req.senderName} 要发送 ${req.fileCount} 个文件给你", 13, Ui.TEXT_2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.card(this@MainActivity, Ui.SURFACE_HIGH, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fileCard.addView(
            Ui.text(this, "📄", 20, Ui.TEXT).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        val fcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fcol.addView(Ui.text(this, req.fileName, 14, Ui.TEXT))
        fcol.addView(Ui.text(this, "${req.fileCount} 个文件 · ${req.totalSize / 1024} KB", 11, Ui.TEXT_3))
        fileCard.addView(fcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        panel.addView(
            fileCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) },
        )

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val reject = Ui.ghostButton(this, "拒绝", danger = true)
        val accept = Ui.primaryButton(this, "接受")
        reject.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        accept.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        reject.setOnClickListener { answerIncoming(false) }
        accept.setOnClickListener { answerIncoming(true) }
        btns.addView(reject)
        btns.addView(accept)
        panel.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val overlay = FrameLayout(this)
        overlay.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        content.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        incomingOverlay = overlay

        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        scrim.animate().alpha(1f).setDuration(180).start()
    }

    private fun answerIncoming(accept: Boolean) {
        val overlay = incomingOverlay ?: return
        incomingOverlay = null
        val panel = overlay.getChildAt(1)
        panel.animate().translationY(panel.height.toFloat()).setDuration(200)
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        overlay.getChildAt(0).animate().alpha(0f).setDuration(200).start()
        MtaReceiverService.MtaLinkBus.userAccept = accept
    }
}
