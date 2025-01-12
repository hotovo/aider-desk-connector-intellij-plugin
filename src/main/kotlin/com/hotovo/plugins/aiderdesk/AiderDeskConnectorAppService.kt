package com.hotovo.plugins.aiderdesk

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.APP)
class AiderDeskConnectorAppService : Disposable {
    private val LOG = Logger.getInstance(AiderDeskConnectorAppService::class.java)
    private var aiderDeskConnector: AiderDeskConnector? = null

    init {
        LOG.info("AiderDesk Connector instance created")
    }

    fun startServer() {
        try {
            LOG.info("Attempting to start AiderDesk Connector")
            if (aiderDeskConnector == null) {
                aiderDeskConnector = AiderDeskConnector()
                aiderDeskConnector?.start()
                LOG.info("AiderDesk Connector connector started successfully")
            } else {
                LOG.info("AiderDesk Connector connector is already running")
            }
        } catch (e: Exception) {
            LOG.error("Failed to start AiderDesk Connector connector", e)
        }
    }

    override fun dispose() {
        LOG.info("Disposing AiderDesk Connector")
        stopServer()
    }

    fun startProjectConnector(project: Project) {
        aiderDeskConnector?.startProjectConnector(project)
    }

    private fun stopServer() {
        try {
            LOG.info("Attempting to stop AiderDesk Connector connector")
            aiderDeskConnector?.stop()
            aiderDeskConnector = null
            LOG.info("AiderDesk Connector connector stopped successfully")
        } catch (e: Exception) {
            LOG.error("Failed to stop AiderDesk Connector connector", e)
        }
    }
}
