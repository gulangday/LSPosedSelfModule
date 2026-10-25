package com.example.selfxposed

import android.content.Context

/** “后台常驻”开关的存储，供界面、服务与开机广播共用。 */
object KeepAlive {
    private const val KEY = "keep_alive"

    fun isEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(ConfigClient.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    } catch (_: Throwable) {
        false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(ConfigClient.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
