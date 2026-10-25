package com.example.selfxposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后，如果用户开过“后台常驻”，自动把服务拉起来。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (KeepAlive.isEnabled(context)) {
            KeepAliveService.start(context)
        }
    }
}
