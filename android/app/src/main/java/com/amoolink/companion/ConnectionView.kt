package com.amoolink.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.provider.Settings
import android.view.View

/**
 * 连接可视化:两个设备节点之间的虚线链路。
 * 已连接 = 信号青虚线流动(唯一常驻动效;传输在即「活」),未连接 = 静止弱色。
 * 尊重系统「动画时长缩放=0」(无障碍减弱动效)。
 */
class ConnectionView(context: Context) : View(context) {

    private val dens = context.resources.displayMetrics.density

    private fun dp(v: Float): Int = (v * dens).toInt()
    private fun dpf(v: Float): Float = v * dens

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
    }

    private val dashOn = DashPathEffect(floatArrayOf(dpf(6f), dpf(7f)), 0f)
    private val dashOff = DashPathEffect(floatArrayOf(dpf(3f), dpf(9f)), 0f)

    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, Ui.dp(context, 13).toFloat()).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    @Volatile
    var connected: Boolean = false
        set(value) {
            field = value
            syncAnimator()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun motionOk(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun syncAnimator() {
        if (connected && motionOk() && isAttachedToWindow) {
            if (animator.isPaused || !animator.isRunning) animator.start()
        } else {
            animator.cancel()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val cy = h * 0.5f
        val pad = dpf(30f)
        val r = dpf(7f)
        val gap = dpf(9f)
        val x1 = pad + r
        val x2 = w - pad - r
        if (x2 <= x1 + gap * 2) return

        val on = connected
        linePaint.color = if (on) Ui.ACCENT else Ui.TEXT_3
        linePaint.pathEffect = if (on) DashPathEffect(floatArrayOf(dpf(6f), dpf(7f)), -phase) else dashOff
        canvas.drawLine(x1 + gap, cy, x2 - gap, cy, linePaint)

        for ((cx, mirror) in listOf(x1 to -1f, x2 to 1f)) {
            val direction = if (on) mirror else 0f
            // 内点
            nodePaint.color = if (on) Ui.ACCENT else Ui.TEXT_3
            canvas.drawCircle(cx, cy, r * 0.55f, nodePaint)
            // 呼吸环:连接时描边环略外扩
            ringPaint.color = if (on) Ui.ACCENT else Ui.STROKE
            canvas.drawCircle(cx + direction * dp(2f), cy, r + dp(3f) * (if (on) 1f else 0.6f), ringPaint)
        }
    }
}
