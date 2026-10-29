package com.car.dockbar.model

import android.content.Context
import com.car.dockbar.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** A launchable app selected for the small Dock application drawer. */
data class AppDrawerApp(
    val label: String,
    val packageName: String,
    val activityName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("package", packageName)
        put("activity", activityName)
    }
}

object AppDrawerApps {
    const val MAX_APPS = 6

    fun load(ctx: Context): List<AppDrawerApp> {
        val raw = Prefs.appDrawerAppsJson(ctx)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("package").trim()
                    if (pkg.isBlank()) continue
                    add(
                        AppDrawerApp(
                            label = item.optString("label", pkg).ifBlank { pkg },
                            packageName = pkg,
                            activityName = item.optString("activity")
                        )
                    )
                    if (size >= MAX_APPS) break
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, apps: List<AppDrawerApp>) {
        val array = JSONArray()
        apps.distinctBy { it.packageName }.take(MAX_APPS).forEach { array.put(it.toJson()) }
        Prefs.setAppDrawerAppsJson(ctx, array.toString())
    }
}
