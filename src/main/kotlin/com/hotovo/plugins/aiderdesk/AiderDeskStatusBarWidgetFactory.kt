package com.hotovo.plugins.aiderdesk

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class AiderDeskStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AiderDeskStatusBarWidget.ID

    override fun getDisplayName(): String = "AiderDesk Connection Status"

    override fun isAvailable(project: Project): Boolean = true // Available for all projects

    override fun createWidget(project: Project): StatusBarWidget = AiderDeskStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
