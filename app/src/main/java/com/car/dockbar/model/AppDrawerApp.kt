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

    /** Compact list for counts and the batch-selection UI only. Never use it for slot layout. */
    fun load(ctx: Context): List<AppDrawerApp> = loadSlots(ctx).filterNotNull()

    /** Fixed six slots. Legacy compact JSON still loads into its original leading positions. */
    fun loadSlots(ctx: Context): List<AppDrawerApp?> {
        val raw = Prefs.appDrawerAppsJson(ctx)
        if (raw.isBlank()) return List(MAX_APPS) { null }
        return runCatching {
            val array = JSONArray(raw)
            val seen = HashSet<String>()
            List(MAX_APPS) { i ->
                val item = array.optJSONObject(i)
                val pkg = item?.optString("package")?.trim().orEmpty()
                if (item == null || pkg.isBlank() || !seen.add(pkg)) null
                else AppDrawerApp(
                    label = item.optString("label", pkg).ifBlank { pkg },
                    packageName = pkg,
                    activityName = item.optString("activity"))
            }
        }.getOrElse { List(MAX_APPS) { null } }
    }

    /** Batch selection removes unchecked apps in place and fills holes with newly checked apps. */
    fun save(ctx: Context, apps: List<AppDrawerApp>) {
        saveSlots(ctx, reconcileSlots(loadSlots(ctx), apps))
    }

    internal fun reconcileSlots(current: List<AppDrawerApp?>, selected: List<AppDrawerApp>): List<AppDrawerApp?> {
        val wanted = selected.distinctBy { it.packageName }.take(MAX_APPS).associateBy { it.packageName }
        val slots = MutableList(MAX_APPS) { i -> current.getOrNull(i)?.let { wanted[it.packageName] } }
        val kept = slots.filterNotNull().mapTo(HashSet()) { it.packageName }
        wanted.values.filter { it.packageName !in kept }.forEach { app ->
            val empty = slots.indexOfFirst { it == null }
            if (empty >= 0) slots[empty] = app
        }
        return slots
    }

    fun saveSlots(ctx: Context, slots: List<AppDrawerApp?>) {
        val array = JSONArray()
        val seen = HashSet<String>()
        repeat(MAX_APPS) { i ->
            val app = slots.getOrNull(i)
            array.put(if (app != null && seen.add(app.packageName)) app.toJson() else JSONObject.NULL)
        }
        Prefs.setAppDrawerAppsJson(ctx, array.toString())
    }
}
