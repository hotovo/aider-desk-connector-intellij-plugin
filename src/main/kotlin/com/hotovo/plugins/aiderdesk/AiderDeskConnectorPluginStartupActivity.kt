package com.hotovo.plugins.aiderdesk;

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class AiderDeskConnectorPluginStartupActivity : StartupActivity {
    private val LOG = Logger.getInstance(AiderDeskConnectorPluginStartupActivity::class.java)

    override fun runActivity(project: Project) {
        try {
            LOG.info("AiderDeskConnectorPluginStartupActivity: Initializing AiderDesk Connector")

            val application = ApplicationManager.getApplication().getService(AiderDeskConnectorAppService::class.java)
            application.startServer()

            // Add the project listener
            application.startProjectConnector(project)

            LOG.info("AiderDeskConnectorPluginStartupActivity: AiderDesk Connector initialized successfully")
        } catch (e: Exception) {
            LOG.error("AiderDeskConnectorPluginStartupActivity: Failed to initialize AiderDesk Connector", e)
        }
    }
}
