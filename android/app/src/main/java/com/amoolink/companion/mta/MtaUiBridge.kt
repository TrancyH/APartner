package com.amoolink.companion.mta

import android.os.Handler
import android.os.Looper

/**
 * MTA 请求 → UI 桥(App 内确认面板用):
 * MtaReceiverService 在传输请求到达时 emit,MainActivity 注册 emitter 弹底部确认面板。
 * Service 侧不碰 View;emitter 由 MainActivity 在主线程注册/注销。
 */
object MtaUiBridge {

    data class IncomingRequest(
        val senderName: String,
        val fileName: String,
        val fileCount: Int,
        val totalSize: Long,
        val answer: (accept: Boolean) -> Unit,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var panelEmitter: ((IncomingRequest) -> Unit)? = null

    /** 在主线程把请求推给前台 Activity;无监听者返回 false(调用方回落通知)。 */
    fun emit(request: IncomingRequest): Boolean {
        val sink = panelEmitter ?: return false
        mainHandler.post { sink.invoke(request) }
        return true
    }
}
