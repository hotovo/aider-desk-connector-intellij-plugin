package com.hotovo.plugins.aiderdesk

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.*

interface AiderDeskConnectionStatusListener : EventListener {
    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AiderDeskConnectionStatusListener> =
            Topic.create("AiderDesk Connection Status", AiderDeskConnectionStatusListener::class.java)
    }

    fun statusChanged(project: Project, status: ConnectionStatus)
}
