package com.car.dockbar.ui

import android.graphics.drawable.Drawable
import android.view.View
import com.car.dockbar.util.Logx

/**
 * 车机系统的跨窗口模糊。启动器里 Flyme 就是这么做的：
 * `View.getViewRootImpl().createBackgroundBlurDrawable()`，再 `setBlurRadius`。
 * 模糊的是 Dock 窗口后面的真实内容，不读壁纸，也不调用截屏。
 */
object BehindBlur {

    private var loggedMissing = false

    fun apply(view: View, radiusPx: Int, cornerPx: Float, tint: Int) {
        view.post { applyWhenAttached(view, radiusPx, cornerPx, tint, 0) }
    }

    fun clear(view: View) {
        view.background = null
    }

    private fun applyWhenAttached(
        view: View, radiusPx: Int, cornerPx: Float, tint: Int, attempt: Int
    ) {
        val drawable = create(view)
        if (drawable == null) {
            if (attempt < 8) {
                view.postDelayed({
                    applyWhenAttached(view, radiusPx, cornerPx, tint, attempt + 1)
                }, 40L)
            }
            return
        }
        invoke(drawable, "setBlurRadius", Int::class.javaPrimitiveType!!, radiusPx.coerceIn(0, 150))
        invoke(drawable, "setColor", Int::class.javaPrimitiveType!!, tint)
        invoke(drawable, "setAlpha", Int::class.javaPrimitiveType!!, 255)
        invoke(drawable, "setCornerRadius", Float::class.javaPrimitiveType!!, cornerPx)
        view.background = drawable
        Logx.d("模糊：已启用系统背景模糊 半径=$radiusPx")
    }

    private fun create(view: View): Drawable? {
        return try {
            val getRoot = view.javaClass.getMethod("getViewRootImpl")
            val root = getRoot.invoke(view) ?: return null
            val create = root.javaClass.methods.firstOrNull {
                it.name == "createBackgroundBlurDrawable" && it.parameterTypes.isEmpty()
            } ?: run {
                if (!loggedMissing) {
                    loggedMissing = true
                    Logx.w("模糊：系统没有 createBackgroundBlurDrawable，无法模糊窗口后面的内容")
                }
                return null
            }
            create.invoke(root) as? Drawable
        } catch (t: Throwable) {
            if (!loggedMissing) {
                loggedMissing = true
                Logx.e("模糊：创建系统背景模糊失败", t)
            }
            null
        }
    }

    private fun invoke(target: Drawable, name: String, type: Class<*>, arg: Any) {
        runCatching {
            target.javaClass.getMethod(name, type).invoke(target, arg)
        }
    }
}
