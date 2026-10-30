package com.car.dockbar.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.car.dockbar.R

/** 系统 Dock（oneOS_Dock）里的 Lynkco Display。缺字时系统会补上中文。 */
object AppFont {
    private var regularFace: Typeface? = null
    private var boldFace: Typeface? = null

    fun regular(ctx: Context): Typeface {
        regularFace?.let { return it }
        val face = ResourcesCompat.getFont(ctx, R.font.lynkco_display_regular) ?: Typeface.DEFAULT
        regularFace = face
        return face
    }

    fun bold(ctx: Context): Typeface {
        boldFace?.let { return it }
        val face = ResourcesCompat.getFont(ctx, R.font.lynkco_display_bold) ?: Typeface.DEFAULT_BOLD
        boldFace = face
        return face
    }
}
