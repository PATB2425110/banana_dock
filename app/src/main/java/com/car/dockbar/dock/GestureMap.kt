package com.car.dockbar.dock

import org.json.JSONObject

object HitTargets {
    const val ROOT = "root"
    const val MINUS = "minus"
    const val VALUE = "value"
    const val PLUS = "plus"
    const val PLAY = "play"

    val all = setOf(ROOT, MINUS, VALUE, PLUS, PLAY)
}

enum class Gesture { TAP, LONG_PRESS, DOUBLE_TAP }

data class GestureBinding(
    val tap: DockAction? = null,
    val longPress: DockAction? = null,
    val doubleTap: DockAction? = null,
) {
    val doubleWindowMs: Long get() = if (doubleTap != null) 300L else 0L
}

sealed class ActionSlot {
    data class Set(val action: DockAction) : ActionSlot()
    data object Clear : ActionSlot()
}

data class GesturePatch(val byTarget: Map<String, Map<Gesture, ActionSlot>>)

object GestureMaps {
    private val gestureNames = mapOf(
        "tap" to Gesture.TAP,
        "longPress" to Gesture.LONG_PRESS,
        "doubleTap" to Gesture.DOUBLE_TAP,
    )

    fun merge(base: Map<String, GestureBinding>, patch: GesturePatch?): Map<String, GestureBinding> {
        if (patch == null) return base
        val out = base.toMutableMap()
        for ((target, slots) in patch.byTarget) {
            if (target !in HitTargets.all) continue
            val current = out[target] ?: GestureBinding()
            var tap = current.tap
            var longPress = current.longPress
            var doubleTap = current.doubleTap
            for ((gesture, slot) in slots) {
                val action = when (slot) {
                    is ActionSlot.Set -> slot.action
                    ActionSlot.Clear -> null
                }
                when (gesture) {
                    Gesture.TAP -> tap = action
                    Gesture.LONG_PRESS -> longPress = action
                    Gesture.DOUBLE_TAP -> doubleTap = action
                }
            }
            out[target] = current.copy(tap = tap, longPress = longPress, doubleTap = doubleTap)
        }
        return out
    }

    fun parsePatch(obj: JSONObject?): GesturePatch? {
        if (obj == null) return null
        val byTarget = linkedMapOf<String, Map<Gesture, ActionSlot>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val target = keys.next()
            if (target !in HitTargets.all) continue
            val targetObj = obj.optJSONObject(target) ?: continue
            val slots = linkedMapOf<Gesture, ActionSlot>()
            for ((name, gesture) in gestureNames) {
                if (!targetObj.has(name)) continue
                if (targetObj.isNull(name)) {
                    slots[gesture] = ActionSlot.Clear
                    continue
                }
                val actionObj = targetObj.optJSONObject(name) ?: continue
                val action = DockActionCodec.fromJson(actionObj) ?: continue
                slots[gesture] = ActionSlot.Set(action)
            }
            if (slots.isNotEmpty()) byTarget[target] = slots
        }
        return GesturePatch(byTarget)
    }

    fun toJson(patch: GesturePatch): JSONObject {
        val root = JSONObject()
        for ((target, slots) in patch.byTarget) {
            val targetObj = JSONObject()
            for ((gesture, slot) in slots) {
                val name = when (gesture) {
                    Gesture.TAP -> "tap"
                    Gesture.LONG_PRESS -> "longPress"
                    Gesture.DOUBLE_TAP -> "doubleTap"
                }
                when (slot) {
                    is ActionSlot.Clear -> targetObj.put(name, JSONObject.NULL)
                    is ActionSlot.Set -> targetObj.put(name, DockActionCodec.toJson(slot.action))
                }
            }
            root.put(target, targetObj)
        }
        return root
    }
}
