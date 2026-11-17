package com.flyd.bgrec

import android.os.Handler
import android.os.Looper

/** 进程内的录制状态（Service 与界面共用一个进程，直接回调即可） */
object RecState {
    @Volatile
    var recording: Boolean = false

    @Volatile
    var codec: String = "H.265"

    @Volatile
    var lastFile: String? = null

    @Volatile
    var status: String = "空闲 · 点下面开始"

    private val main = Handler(Looper.getMainLooper())
    private var listener: ((String) -> Unit)? = null

    fun bind(cb: ((String) -> Unit)?) {
        listener = cb
    }

    fun publish(text: String) {
        status = text
        main.post { listener?.invoke(text) }
    }
}
