package com.hotovo.plugins.aiderdesk

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

class AiderDeskStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.IconPresentation {

    private var currentStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED

    companion object {
        const val ID = "AiderDeskStatusBarWidget"
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)

        // Subscribe to connection status changes
        project.messageBus.connect(this)
            .subscribe(AiderDeskConnectionStatusListener.TOPIC, object : AiderDeskConnectionStatusListener {
                override fun statusChanged(project: Project, status: ConnectionStatus) {
                    if (project == this@AiderDeskStatusBarWidget.project) {
                        updateStatus(status)
                    }
                }
            })

        // Get initial status
        val appService = ApplicationManager.getApplication().getService(AiderDeskConnectorAppService::class.java)
        updateStatus(appService.getConnectionStatus(project) ?: ConnectionStatus.DISCONNECTED)
    }

    override fun getIcon(): Icon = IconLoader.getIcon("/META-INF/widgetIcon.svg", AiderDeskStatusBarWidget::class.java)

    override fun getTooltipText(): String = "AiderDesk: ${currentStatus.displayName}\n"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { mouseEvent ->
        val component = mouseEvent.component

        // Create a disabled action showing the current status
        val statusAction = object : AnAction("Status: ${currentStatus.displayName}") {
            override fun actionPerformed(e: AnActionEvent) {
                // Action is disabled, nothing to do here
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = false // Disable the action
                e.presentation.text = "Status: ${currentStatus.displayName}" // Update text dynamically if needed
            }
        }

        // Create an action group containing the status action
        val actionGroup = DefaultActionGroup()
        actionGroup.add(statusAction)

        val connectAction = object : AnAction("Connect") {
            override fun actionPerformed(e: AnActionEvent) {
                val appService = ApplicationManager.getApplication().getService(AiderDeskConnectorAppService::class.java)
                project.let { appService.reconnect(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = currentStatus == ConnectionStatus.DISCONNECTED || currentStatus == ConnectionStatus.ERROR
            }
        }
        actionGroup.addSeparator()
        actionGroup.add(connectAction)

        val disconnectAction = object : AnAction("Disconnect") {
            override fun actionPerformed(e: AnActionEvent) {
                val appService = ApplicationManager.getApplication().getService(AiderDeskConnectorAppService::class.java)
                project.let { appService.disconnectProject(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = currentStatus == ConnectionStatus.CONNECTED
            }
        }
        actionGroup.add(disconnectAction)

        // Create and show the popup
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "AiderDesk", // Popup title
                actionGroup, // Action group with the status
                DataContext.EMPTY_CONTEXT,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true // Show separators
            )
        popup.show(RelativePoint(component, mouseEvent.point))
    }

    private fun updateStatus(newStatus: ConnectionStatus) {
        currentStatus = newStatus
        statusBar?.updateWidget(ID())
    }
}
