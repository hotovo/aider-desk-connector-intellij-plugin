package com.hotovo.plugins.aiderdesk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.isFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.util.messages.MessageBusConnection
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

data class FileMessage(
    val action: String,
    val path: String,
    val baseDir: String,
    val sourceType: String = "intellij"
)

class AiderDeskConnector : CoroutineScope {
    private val LOG = Logger.getInstance(AiderDeskConnector::class.java)
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    private val mapper = jacksonObjectMapper()
    private val projects = mutableSetOf<Project>()
    private val projectFileEditorListeners = ConcurrentHashMap<Project, FileEditorManagerListener>()
    private val projectFileListeners = ConcurrentHashMap<Project, BulkFileListener>()
    private val projectManagerListeners = ConcurrentHashMap<Project, ProjectManagerListener>()
    private val projectMessageBusConnections = ConcurrentHashMap<Project, MessageBusConnection>()
    private val projectSockets = ConcurrentHashMap<Project, Socket>()
    private val projectConnectionStatus = ConcurrentHashMap<Project, ConnectionStatus>()
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) {
            LOG.info("AiderDeskConnector already started.")
            return
        }
        LOG.info("Starting AiderDeskConnector.")
        isRunning = true
    }

    private fun updateProjectStatus(project: Project, status: ConnectionStatus) {
        projectConnectionStatus[project] = status
        // Ensure listener is called on the EDT if it updates UI, though status bar handles its own threading
        project.messageBus.syncPublisher(AiderDeskConnectionStatusListener.TOPIC).statusChanged(project, status)
        LOG.info("Project ${project.name} status updated to $status")
    }


    private fun connectProject(project: Project) {
        if (projectSockets.containsKey(project)) {
            LOG.warn("Project ${project.name} already has an active connection attempt.")
            return
        }

        updateProjectStatus(project, ConnectionStatus.CONNECTING)

        launch {
            try {
                LOG.info("Attempting to connect project ${project.name} to AiderDesk server on port 24337")
                val opts = IO.Options.builder()
                    .setForceNew(true) // Force a new connection
                    .setReconnection(true) // Enable auto-reconnection
                    .setReconnectionAttempts(Int.MAX_VALUE) // Keep trying indefinitely
                    .setReconnectionDelay(1000) // Initial delay
                    .setReconnectionDelayMax(5000) // Max delay between attempts
                    .setTimeout(10000) // Connection timeout
                    .build()
                val socket = IO.socket("http://localhost:24337", opts)

                socket.on(Socket.EVENT_CONNECT) {
                    LOG.info("Socket.IO connection established successfully for project ${project.name}")
                    updateProjectStatus(project, ConnectionStatus.CONNECTED)
                    sendInitMessage(project)
                }

                socket.on(Socket.EVENT_DISCONNECT) { reason ->
                    LOG.info("Socket.IO disconnected for project ${project.name}. Reason: $reason")
                    // Only set to disconnected if we are not shutting down
                    if (isRunning && projects.contains(project)) {
                        updateProjectStatus(project, ConnectionStatus.DISCONNECTED)

                        // Reconnect
                        socket.connect()
                    }
                }

                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val error = args.firstOrNull()
                    LOG.debug("Socket.IO connection error for project ${project.name}: $error")
                    // Update status only if not already connected (might be a transient error during reconnect)
                    if (projectConnectionStatus[project] != ConnectionStatus.DISCONNECTED && isRunning && projects.contains(project)) {
                        updateProjectStatus(project, ConnectionStatus.DISCONNECTED)
                    }
                }

                socket.on("message") { args ->
                    val text = args.firstOrNull()?.toString()
                    LOG.info("Received message for project ${project.name}: $text")
                }

                socket.connect()
                projectSockets[project] = socket

                // Keep the connection alive while the project is active
                while (isRunning && projects.contains(project)) {
                    delay(1000)
                }
                // If the loop exits because the project is no longer active or isRunning is false
                disconnectProjectInternal(project)
            } catch (e: Exception) {
                LOG.error("Error in Socket.IO connection coroutine for project ${project.name}", e)
                updateProjectStatus(project, ConnectionStatus.ERROR)
                disconnectProjectInternal(project) // Ensure cleanup on unexpected error
            }
        }
    }

    fun getConnectionStatus(project: Project): ConnectionStatus {
        return projectConnectionStatus[project] ?: ConnectionStatus.DISCONNECTED
    }

    private fun isIgnoredByGit(project: Project, file: VirtualFile): Boolean {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        // TODO: this does not work as intended, it returns false even if the file should be ignored
        return vcsManager.isIgnored(file)
    }

    // Internal disconnect logic without status update (status handled by caller or stop())
    private fun disconnectProjectInternal(project: Project) {
        projectSockets.remove(project)?.let { socket ->
            try {
                LOG.info("Disconnecting socket for project ${project.name}")
                socket.disconnect()
                socket.off() // Remove all listeners
                socket.close()
            } catch (e: Exception) {
                LOG.error("Error during socket disconnection for project ${project.name}", e)
            }
        }
    }

    // Public disconnect function, updates status
    fun disconnectProject(project: Project) {
        LOG.info("Explicitly disconnecting project ${project.name}")
        disconnectProjectInternal(project)
        updateProjectStatus(project, ConnectionStatus.DISCONNECTED) // Set status after disconnecting
    }


    fun stop() {
        if (!isRunning) {
            LOG.info("AiderDeskConnector already stopped.")
            return
        }
        try {
            LOG.info("Stopping AiderDeskConnector and all project connections.")
            isRunning = false // Signal coroutines and listeners to stop

            // Create a copy of projects to avoid ConcurrentModificationException
            val projectsToStop = projects.toList()
            projectsToStop.forEach { project ->
                removeProjectListeners(project) // Disconnect message bus connections first
                disconnectProjectInternal(project) // Disconnect socket
                updateProjectStatus(project, ConnectionStatus.DISCONNECTED) // Update status last
            }

            // Clear all tracking maps
            projects.clear()
            projectSockets.clear()
            projectConnectionStatus.clear()
            projectFileEditorListeners.clear()
            projectFileListeners.clear()
            projectManagerListeners.clear()
            projectMessageBusConnections.clear()

            job.cancel() // Cancel the coroutine scope
            LOG.info("AiderDeskConnector stopped successfully.")
        } catch (e: Exception) {
            LOG.error("Failed to stop AiderDesk Connector cleanly", e)
        } finally {
             // Ensure state is reset even if errors occurred
            isRunning = false
            if (job.isCancelled.not()) job.cancel()
        }
    }

    fun reconnect(project: Project) {
        LOG.info("Attempting to reconnect project ${project.name}")
        disconnectProjectInternal(project) // Ensure existing connection is closed
        updateProjectStatus(project, ConnectionStatus.CONNECTING) // Set status to connecting
        connectProject(project) // Re-initiate connection
    }

    fun startProjectConnector(project: Project) {
        if (!isRunning) {
            LOG.warn("Cannot start project connector for ${project.name}, AiderDeskConnector is not running.")
            return
        }
        if (projects.contains(project)) {
            LOG.info("Project connector for ${project.name} is already active.")
            return
        }

        LOG.info("Starting project connector for ${project.name}")

        val connection = project.messageBus.connect()
        projectMessageBusConnections[project] = connection

        // --- File Editor Listener ---
        val fileEditorListener = object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (!isRunning || !file.isFile || file.fileType.isBinary) {
                    return
                }

                val projectBasePath = project.basePath ?: return
                if (!file.path.startsWith(projectBasePath) || isIgnoredByGit(project, file)) {
                    return
                }

                val relativePath = Path.of(projectBasePath).relativize(Path.of(file.path)).toString().replace("\\", "/")
                sendMessage(FileMessage("add-file", relativePath, projectBasePath, "intellij"))
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                 if (!isRunning || !file.isFile || file.fileType.isBinary) {
                    return
                }
                LOG.debug("File closed: ${file.name}")

                val relativePath = project.basePath?.let { basePath ->
                    Path.of(basePath).relativize(Path.of(file.path)).toString().replace("\\", "/")
                } ?: return

                sendMessage(FileMessage("drop-file", relativePath, project.basePath!!, "intellij"))
            }
        }
        projectFileEditorListeners[project] = fileEditorListener
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, fileEditorListener)

        // --- VFS Listener ---
        val fileListener = object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                 if (!isRunning) return
                 events.forEach { event -> processVfsEvent(event, project) }
            }
        }
        projectFileListeners[project] = fileListener
        connection.subscribe(VirtualFileManager.VFS_CHANGES, fileListener)


        // --- Project Manager Listener ---
        val projectListener = object : ProjectManagerListener {
            override fun projectClosing(closingProject: Project) {
                if (project == closingProject) {
                    LOG.info("Project ${project.name} is closing. Disconnecting...")
                    removeProjectListeners(project) // Disconnect message bus first
                    disconnectProject(project) // Disconnect socket and update status
                    projects.remove(project) // Remove from active projects set
                    // Clear specific project maps
                    projectFileEditorListeners.remove(project)
                    projectFileListeners.remove(project)
                    projectManagerListeners.remove(project)
                    projectMessageBusConnections.remove(project) // Remove connection reference
                    projectConnectionStatus.remove(project)
                }
            }
        }
        projectManagerListeners[project] = projectListener
        // Subscribe to the application-level message bus for project closing events
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(ProjectManager.TOPIC, projectListener)


        projects.add(project)
        updateProjectStatus(project, ConnectionStatus.DISCONNECTED) // Initial status before connect attempt

        // Connect the project and send initial open files
        connectProject(project)
    }

    private fun sendInitMessage(project: Project) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        val projectBasePath = project.basePath ?: return
        val normalizedBasePath = if (isWindows) projectBasePath.replace("/", "\\") else projectBasePath
        val contextFiles = ArrayList<Map<String, Any>>()

        for (editor in FileEditorManager.getInstance(project).allEditors) {
            val file = editor.file ?: continue
            if (!file.path.startsWith(projectBasePath) || isIgnoredByGit(project, file)) {
                continue
            }

            val relativePath = Path.of(projectBasePath).relativize(Path.of(file.path)).toString().replace("\\", "/")
            val normalizedPath = if (isWindows) relativePath.replace("/", "\\") else relativePath
            contextFiles.add(
                mapOf(
                    "path" to normalizedPath,
                    "sourceType" to "intellij"
                )
            )
        }

        launch {
            val initMessage = mapOf(
                "action" to "init",
                "baseDir" to normalizedBasePath,
                "contextFiles" to contextFiles
            )
            // Only send if connected
            if (getConnectionStatus(project) == ConnectionStatus.CONNECTED) {
                 sendMessage(initMessage, project)
            } else {
                LOG.warn("Cannot send init message for project ${project.name}, not connected.")
            }
        }
    }

    // Send message for a specific project
    private fun sendMessage(message: Map<String, Any>, project: Project) {
        if (!isRunning) return
        val socket = projectSockets[project]
        if (socket == null || !socket.connected()) {
            LOG.warn("Cannot send message, socket not connected for project ${project.name}. Status: ${getConnectionStatus(project)}")
            return
        }
        try {
            val jsonMessage = mapper.writeValueAsString(message)
            LOG.debug("Sending message for project ${project.name}: $jsonMessage")
            socket.emit("message", JSONObject(jsonMessage))
        } catch (e: Exception) {
            LOG.error("Failed to serialize or send message for project ${project.name}", e)
        }
    }

    // Send message based on baseDir lookup (used by file events)
    fun sendMessage(message: FileMessage) {
        if (!isRunning) return
        val project = projects.find { it.basePath == message.baseDir }
        if (project == null) {
            LOG.warn("Cannot find project for baseDir ${message.baseDir} to send message: ${message.action}")
            return
        }

        val socket = projectSockets[project]
        if (socket == null || !socket.connected()) {
            LOG.warn("Cannot send message, socket not connected for project ${project.name}. Status: ${getConnectionStatus(project)}")
            return
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val normalizedPath = if (isWindows) message.path.replace("/", "\\") else message.path
        val normalizedBasePath = if (isWindows) message.baseDir.replace("/", "\\") else message.baseDir
        val normalizedMessage = FileMessage(message.action, normalizedPath, normalizedBasePath, message.sourceType)

        try {
            val jsonMessage = mapper.writeValueAsString(normalizedMessage)
            LOG.debug("Sending message for project ${project.name} (via baseDir): $jsonMessage")
            socket.emit("message", JSONObject(jsonMessage))
        } catch (e: Exception) {
            LOG.error("Failed to serialize or send message for project ${project.name}", e)
        }
    }

    private fun processVfsEvent(event: VFileEvent, project: Project) {
         if (!isRunning) return

         when (event) {
            is VFilePropertyChangeEvent -> {
                // Handle rename within the same directory
                if (event.propertyName == VirtualFile.PROP_NAME) {
                    val file = event.file
                    val oldName = event.oldValue as? String ?: return
                    val parentPath = file.parent?.path ?: return
                    val oldPath = "$parentPath/$oldName"
                    handleFilePathChange(project, file, oldPath, file.path)
                }
            }
            is VFileMoveEvent -> {
                // Handle move to a different directory (or rename)
                handleFilePathChange(project, event.file, event.oldPath, event.newPath)
            }
             // Potentially handle VFileDeleteEvent if needed, e.g., to send drop-file if the file was open.
             // Potentially handle VFileCopyEvent if needed.
             // Potentially handle VFileCreateEvent if needed.
        }
    }


    private fun handleFilePathChange(
        project: Project,
        file: VirtualFile,
        oldFilePath: String?,
        newFilePath: String?
    ) {
        if (!isRunning || !file.isFile || file.fileType.isBinary || oldFilePath == null || newFilePath == null) {
            return
        }

        val projectBasePath = project.basePath ?: return

        // Check if the file *was* or *is* within the project base path
        val oldPathInProject = oldFilePath.startsWith(projectBasePath)
        val newPathInProject = newFilePath.startsWith(projectBasePath)

        if (!oldPathInProject && !newPathInProject) {
             // Change happened entirely outside the project scope
            return
        }

        // Determine if the file is currently open in an editor *after* the move/rename
        val fileEditorManager = FileEditorManager.getInstance(project)
        val isFileOpen = fileEditorManager.isFileOpen(file) // Check if the *new* path is open

        if (!isFileOpen) {
            // If the file isn't open after the change, we don't need to send add/drop messages
            // based on the VFS event itself. FileOpen/FileClose listeners handle open files.
            return
        }

        // If the file *is* open after the change, we need to update AiderDesk
        try {
            val oldRelativePath = if (oldPathInProject) {
                Path.of(projectBasePath).relativize(Path.of(oldFilePath)).toString().replace("\\", "/")
            } else null

            val newRelativePath = if (newPathInProject) {
                Path.of(projectBasePath).relativize(Path.of(newFilePath)).toString().replace("\\", "/")
            } else null

            if (oldRelativePath != newRelativePath) {
                LOG.info("Open file path changed from $oldRelativePath to $newRelativePath")
                // Send drop for the old path only if it was valid and different
                if (oldRelativePath != null) {
                    sendMessage(FileMessage("drop-file", oldRelativePath, projectBasePath, "intellij"))
                }
                // Send add for the new path only if it's valid and different
                if (newRelativePath != null) {
                    sendMessage(FileMessage("add-file", newRelativePath, projectBasePath, "intellij"))
                }
            }
        } catch (e: Exception) {
            LOG.error("Error handling file path change from $oldFilePath to $newFilePath", e)
        }
    }

    private fun removeProjectListeners(project: Project) {
        // Disconnect the message bus connection, which unsubscribes all listeners associated with it
        projectMessageBusConnections.remove(project)?.disconnect()
        // Also disconnect the application-level listener for this project
//        ApplicationManager.getApplication().messageBus.disposeConnection(project) // Assuming connection was stored per project

        // Clear listener references just in case, though they should be inactive after disconnect
        projectFileEditorListeners.remove(project)
        projectFileListeners.remove(project)
        projectManagerListeners.remove(project) // Remove project manager listener reference
    }
}
