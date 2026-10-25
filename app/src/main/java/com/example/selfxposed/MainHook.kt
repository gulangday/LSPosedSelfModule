package com.example.selfxposed

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == MODULE_PACKAGE || pkg.startsWith("android.")) return
        if (pkg != TARGET_PACKAGE) return
        XposedBridge.log("自用模块: handleLoadPackage 已进入，目标=$pkg")
        try {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java, "callActivityOnResume", Activity::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (activity.packageName != pkg || activity.isFinishing || activity.isDestroyed) return
                        try {
                            activity.contentResolver.call(CONFIG_URI, "record_hook", pkg, null)
                            if (firstCallbackLogged.add(pkg)) {
                                logFramework("自用模块: 已进入目标应用 Activity 回调，包名=$pkg")
                                writeLog(activity, pkg, "已检测到 Hook 回调；作用域运行时握手成功")
                            }
                        } catch (t: Throwable) {
                            logFramework("自用模块: 记录 Hook 状态失败，包名=$pkg，原因=${t.javaClass.simpleName}: ${t.message}")
                        }

                        val cfg = try {
                            val result = activity.contentResolver.call(CONFIG_URI, "get_config", pkg, null)
                            JSONObject(result?.getString("config") ?: "{}")
                        } catch (t: Throwable) {
                            val detail = "读取配置失败：${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
                            logFramework("自用模块: $detail，目标=$pkg")
                            writeLog(activity, pkg, detail)
                            return
                        }
                        if (!cfg.optBoolean("enabled", false)) {
                            if (firstDisabledLogged.add(pkg)) writeLog(activity, pkg, "Hook 已触发，但目标应用开关处于关闭状态")
                            return
                        }
                        if (!cfg.optBoolean("popupEnabled", true)) {
                            if (firstPopupDisabledLogged.add(pkg)) writeLog(activity, pkg, "目标应用开关已开启，但 Toast 通知功能处于关闭状态")
                            return
                        }

                        val now = System.currentTimeMillis()
                        val lastShown = lastToastAt[pkg] ?: 0L
                        // 避免 Activity 连续恢复时重复弹出 Toast。
                        if (now - lastShown < TOAST_COOLDOWN_MS) return
                        lastToastAt[pkg] = now

                        val message = cfg.optString("popupText", "你好")
                        try {
                            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                            writeLog(activity, pkg, "Toast 已调用：$message")
                        } catch (t: Throwable) {
                            val detail = "Toast 显示失败：${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
                            logFramework("自用模块: $detail，目标=$pkg")
                            writeLog(activity, pkg, detail)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            val detail = "Hook 安装失败：${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
            logFramework("自用模块: $detail，目标=$pkg")
        }
    }

    private fun logFramework(message: String) {
        try { XposedBridge.log(message) } catch (_: Throwable) {}
    }

    private fun writeLog(activity: Activity, pkg: String, message: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val extras = Bundle().apply { putString("line", "$time  $message") }
            activity.contentResolver.call(CONFIG_URI, "append_log", pkg, extras)
        } catch (_: Throwable) {}
    }

    companion object {
        private const val MODULE_PACKAGE = "com.example.selfxposed"
        private const val TARGET_PACKAGE = "com.kugou.android.lite"
        private val firstCallbackLogged = ConcurrentHashMap.newKeySet<String>()
        private val firstDisabledLogged = ConcurrentHashMap.newKeySet<String>()
        private val firstPopupDisabledLogged = ConcurrentHashMap.newKeySet<String>()
        private val CONFIG_URI = Uri.parse("content://com.example.selfxposed.config")
        private const val TOAST_COOLDOWN_MS = 3000L
        private val lastToastAt = ConcurrentHashMap<String, Long>()
    }
}
