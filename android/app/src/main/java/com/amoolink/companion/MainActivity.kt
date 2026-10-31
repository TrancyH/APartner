package com.amoolink.companion

import android.Manifest
import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 伴侣 APK 主界面(暗夜链路设计系统):
 * Hero 连接可视化卡(手机↔电脑链路动效,唯一记忆点)→ 配对(OTP 大字 + SAS 比对)
 * → 文件入口 → 权限列表 → 已配对电脑 → 常规设置。
 * 逻辑与 v1 完全一致(Pairing.begin/confirm/cancel、renderStatus 数据源、forget、自启)。
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
        // 开机自启默认开(引导页语义;关闭存 prefs)
        getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("autostart", true).apply()
        startServiceExplicit()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
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

        // 文件
        col.addView(Ui.sectionHeader(this, "文件"), marginTop(22))
        val filesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.ripple(this@MainActivity, Ui.card(this@MainActivity, Ui.SURFACE, 14))
            setOnClickListener { startActivity(Intent(this@MainActivity, FilesActivity::class.java)) }
            setPadding(dp(4), 0, dp(4), 0)
        }
        val (filesRow, _) = Ui.settingRow(this, "🗂", "文件管理", "浏览 · 分类 · 分享到电脑 › ") {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        filesCard.addView(filesRow)
        col.addView(filesCard, marginTop(8))

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

        setContentView(root)
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
}
