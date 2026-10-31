package com.amoolink.companion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 伴侣 APK 主界面:服务状态 / 添加电脑(OTP+SAS 配对)/ 权限引导 / 已配对桌面管理。
 * 程序化视图(零资源文件,与工程「零外部依赖」策略一致)。
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var pairingPanel: LinearLayout
    private lateinit var otpText: TextView
    private lateinit var sasText: TextView
    private lateinit var confirmBtn: Button
    private lateinit var pairedList: LinearLayout
    private var renderedPaired: Set<String> = emptySet()
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // ── UI 构建 ─────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        col.addView(TextView(this).apply {
            text = "AmooLink 伴侣"
            textSize = 24f
            gravity = Gravity.CENTER
        }, params)

        statusText = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(12)) }
        col.addView(statusText, params)

        col.addView(Button(this).apply {
            text = "启动 / 重启服务"
            setOnClickListener { startServiceExplicit(); toast("服务已启动") }
        }, params)

        col.addView(Button(this).apply {
            text = "添加电脑(开始配对)"
            setOnClickListener {
                if (ConnectionService.instance == null) startServiceExplicit()
                handler.postDelayed({
                    ConnectionService.Pairing.begin()
                    toast("配对模式已开启(5 分钟内有效)")
                    renderStatus()
                }, 300)
            }
        }, params)

        pairingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        pairingPanel.addView(TextView(this).apply {
            text = "在电脑端 AmooLink「设置 → 伴侣设备」中输入以下信息:"
            textSize = 13f
        }, params)
        otpText = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD) }
        pairingPanel.addView(otpText, params)
        pairingPanel.addView(TextView(this).apply {
            text = "连接后双方各显示 6 位比对码,数字一致再点下方确认。"
            textSize = 12f
        }, params)
        sasText = TextView(this).apply { textSize = 32f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD) }
        pairingPanel.addView(sasText, params)
        confirmBtn = Button(this).apply {
            text = "数字一致,确认配对"
            setOnClickListener {
                // 确认会写 TLS socket,必须在后台线程(NetworkOnMainThreadException)
                Thread { ConnectionService.instance?.confirmPairing() }.start()
                toast("已确认配对")
            }
        }
        pairingPanel.addView(confirmBtn, params)
        pairingPanel.addView(Button(this).apply {
            text = "取消配对"
            setOnClickListener { ConnectionService.instance?.cancelPairing() }
        }, params)
        col.addView(pairingPanel, params)

        col.addView(TextView(this).apply {
            text = "权限与能力"
            textSize = 16f; setPadding(0, dp(24), 0, dp(4))
        }, params)
        col.addView(guideRow("通知使用权(通知同步与快捷回复)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(guideRow("短信 / 通讯录 / 电话状态") {
            requestRuntimePerms()
        })
        col.addView(guideRow("电池优化白名单(保活)") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                toast("请到系统设置手动设置")
            }
        })

        val autoSwitch = Switch(this).apply { text = "开机自启" }
        autoSwitch.isChecked = getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("autostart", true)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(ConnectionService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("autostart", checked).apply()
        }
        col.addView(autoSwitch, params)

        col.addView(TextView(this).apply {
            text = "已配对电脑"
            textSize = 16f; setPadding(0, dp(24), 0, dp(4))
        }, params)
        pairedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pairedList, params)

        root.addView(col)
        setContentView(root)
    }

    private fun guideRow(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    // ── 状态渲染 ────────────────────────────────────────────────────

    private fun renderStatus() {
        val svc = ConnectionService.instance
        val sb = StringBuilder()
        if (svc == null) {
            sb.append("服务未运行")
        } else {
            sb.append("服务运行中 · 端口 ${Proto.PORT}\n")
            sb.append("本机身份指纹:${svc.phoneFp().take(16)}…\n")
            val ips = svc.localIps()
            sb.append(if (ips.isEmpty()) "无网络地址" else "地址:${ips.joinToString(" / ")}\n")
            val conns = svc.connectedDesktops()
            sb.append(if (conns.isEmpty()) "无已连接电脑" else "在线电脑:\n" + conns.joinToString("\n") { " · ${svc.pinnedName(it)}(${it.take(12)}…)" })
        }
        statusText.text = sb.toString()

        val pairing = ConnectionService.Pairing.active()
        pairingPanel.visibility = if (pairing) View.VISIBLE else View.GONE
        if (pairing) {
            val otp = ConnectionService.Pairing.otp ?: ""
            val ip = ConnectionService.instance?.localIps()?.firstOrNull() ?: "?"
            otpText.text = "地址 $ip:${Proto.PORT}\n配对码 $otp"
            val sas = ConnectionService.Pairing.pendingSas
            sasText.text = sas ?: "等待电脑连接…"
            confirmBtn.isEnabled = sas != null
        }

        // 已配对电脑列表(指纹变化时重建;含忘记按钮)
        val svc2 = ConnectionService.instance
        if (svc2 != null) {
            val pinned = svc2.pinnedFps()
            if (pinned != renderedPaired) {
                renderedPaired = pinned
                pairedList.removeAllViews()
                if (pinned.isEmpty()) {
                    pairedList.addView(TextView(this).apply { text = "尚未配对;点上方「添加电脑」开始。"; textSize = 13f })
                }
                for (fp in pinned.sorted()) {
                    val online = svc2.connectedDesktops().contains(fp)
                    pairedList.addView(Button(this).apply {
                        text = "${svc2.pinnedName(fp)}${if (online) " · 在线" else ""}(${fp.take(10)}…)— 忘记"
                        setOnClickListener {
                            svc2.forget(fp)
                            toast("已忘记该电脑")
                            renderedPaired = emptySet()
                        }
                    })
                }
            }
        }
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

private fun Context.checkGranted(perm: String): Boolean =
    checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
