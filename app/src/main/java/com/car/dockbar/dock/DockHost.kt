package com.car.dockbar.dock

import android.view.View

interface DockHost {
    fun onGesture()
    fun showPopup(popup: String, anchor: View)
    fun toggleHvacWindow()
    fun toggleAcFromDock()
    fun toggleDualSyncFromDock()
}

object SilentDockHost : DockHost {
    override fun onGesture() = Unit
    override fun showPopup(popup: String, anchor: View) = Unit
    override fun toggleHvacWindow() = Unit
    override fun toggleAcFromDock() = Unit
    override fun toggleDualSyncFromDock() = Unit
}
