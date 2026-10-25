package com.example.selfxposed

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

object ConfigClient {
    private val uri = Uri.parse("content://com.example.selfxposed.config")
    fun call(resolver: ContentResolver, method: String, pkg: String, extras: Bundle? = null): Bundle =
        resolver.call(uri, method, pkg, extras) ?: Bundle()
    fun config(resolver: ContentResolver, pkg: String): JSONObject {
        val raw = call(resolver, "get_config", pkg).getString("config") ?: "{}"
        return JSONObject(raw)
    }
    fun save(resolver: ContentResolver, pkg: String, config: JSONObject) {
        val b = Bundle().apply { putString("config", config.toString()) }
        call(resolver, "set_config", pkg, b)
    }
    fun log(resolver: ContentResolver, pkg: String, message: String) {
        val b = Bundle().apply { putString("line", "${System.currentTimeMillis()}|$message") }
        call(resolver, "append_log", pkg, b)
    }
}
