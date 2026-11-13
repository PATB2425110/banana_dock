package com.car.dockbar.util

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** 测试用内存存储，仅实现黑名单存储和迁移实际使用的 SharedPreferences 操作。 */
internal class MemoryPreferences(initial: Map<String, Any> = emptyMap()) {
    val values = initial.toMutableMap()
    val writes = mutableListOf<Map<String, Any>>()
    val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0] as String)
            "getString", "getBoolean" -> values[args!![0] as String] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val pending = linkedMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString", "putBoolean" -> {
                    pending[args!![0] as String] = args[1]
                    editor
                }
                "apply", "commit" -> {
                    values.putAll(pending)
                    writes.add(pending.toMap())
                    if (method.name == "commit") true else null
                }
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
