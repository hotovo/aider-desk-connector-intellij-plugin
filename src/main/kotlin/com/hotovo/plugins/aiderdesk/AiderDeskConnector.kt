package com.hotovo.plugins.aiderdesk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
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
    private val projectFileEditorListeners = mutableMapOf<Project, FileEditorManagerListener>()
    private val projectFileListeners = mutableMapOf<Project, BulkFileListener>()
    private val projectSockets = mutableMapOf<Project, Socket>()
    private var isRunning = false

    fun start() {
        isRunning = true
    }

    private fun connectProject(project: Project) {
        launch {
            try {
                LOG.info("Attempting to connect project ${project.name} to AiderDesk server on port 24337")
                val opts = IO.Options.builder()
                    .setReconnection(true)
                    .setReconnectionDelayMax(2000)
                    .build()
                val socket = IO.socket("http://localhost:24337", opts)

                socket.on(Socket.EVENT_CONNECT) {
                    LOG.info("Socket.IO connection established successfully for project ${project.name}")
                    sendInitMessage(project)
                }

                socket.on(Socket.EVENT_DISCONNECT) {
                    LOG.info("Socket.IO disconnected for project ${project.name}")
                }

                socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    LOG.debug("Socket.IO connection error for project ${project.name}: ${args.firstOrNull()}")
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
                disconnectProject(project)
            } catch (e: Exception) {
                LOG.error("Error in Socket.IO connection for project ${project.name}", e)
                projectSockets.remove(project)
            }
        }
    }

    private fun isIgnoredByGit(project: Project, file: VirtualFile): Boolean {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        // TODO: this does not work as intended, it returns false even if the file should be ignored
        return vcsManager.isIgnored(file)
    }

    private fun disconnectProject(project: Project) {
        projectSockets[project]?.let { socket ->
            try {
                socket.disconnect()
            } catch (e: Exception) {
                LOG.error("Error disconnecting project ${project.name}", e)
            }
        }
        projectSockets.remove(project)
    }

    fun stop() {
        try {
            LOG.info("Attempting to stop all Socket.IO connections")
            isRunning = false
            removeAllProjectListeners()
            job.cancel()
            LOG.info("All Socket.IO connections closed successfully")
        } catch (e: Exception) {
            LOG.error("Failed to stop Socket.IO connections", e)
        }
    }

    fun startProjectConnector(project: Project) {
        val fileEditorListener = object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (!file.isFile || file.fileType.isBinary) {
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
                if (!file.isFile || file.fileType.isBinary) {
                    return
                }
                LOG.info("File closed: ${file.fileType}, ${file.name}, ${file.path}")

                val relativePath = project.basePath?.let { basePath ->
                    Path.of(basePath).relativize(Path.of(file.path)).toString().replace("\\", "/")
                } ?: return

                sendMessage(FileMessage("drop-file", relativePath, project.basePath!!, "intellij"))
            }
        }

        val fileListener = object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    when (event) {
                        is VFilePropertyChangeEvent -> {
                            if (event.propertyName == "name") {
                                handleFilePathChange(
                                    project,
                                    event.file,
                                    event.file.parent?.path + "/" + event.oldValue,
                                    event.file.path
                                )
                            }
                        }

                        is VFileMoveEvent -> {
                            handleFilePathChange(
                                project,
                                event.file,
                                event.oldPath,
                                event.file.path
                            )
                        }
                    }
                }
            }
        }

        val projectListener = object : ProjectManagerListener {
            override fun projectClosing(project: Project) {
                disconnectProject(project)
                projects.remove(project)
                projectFileEditorListeners.remove(project)
                projectFileListeners.remove(project)
            }
        }

        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, fileEditorListener)
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, fileListener)
        project.messageBus.connect().subscribe(ProjectManager.TOPIC, projectListener)

        projects.add(project)
        projectFileEditorListeners[project] = fileEditorListener
        projectFileListeners[project] = fileListener

        // Connect the project and send initial open files
        connectProject(project)
    }

    private fun sendInitMessage(project: Project) {
        val projectBasePath = project.basePath ?: return
        val contextFiles = ArrayList<Map<String, Any>>()

        for (editor in FileEditorManager.getInstance(project).allEditors) {
            val file = editor.file ?: continue
            if (!file.path.startsWith(projectBasePath) || isIgnoredByGit(project, file)) {
                continue
            }

            val relativePath = Path.of(projectBasePath).relativize(Path.of(file.path)).toString().replace("\\", "/")
            contextFiles.add(
                mapOf(
                    "path" to relativePath,
                    "sourceType" to "intellij"
                )
            )
        }

        launch {
            val initMessage = mapOf(
                "action" to "init",
                "baseDir" to projectBasePath,
                "contextFiles" to contextFiles
            )
            sendMessage(initMessage, project)
        }
    }

    private fun sendMessage(message: Map<String, Any>, project: Project) {
        try {
            val jsonMessage = mapper.writeValueAsString(message)
            projectSockets[project]?.emit("message", JSONObject(jsonMessage))
        } catch (e: Exception) {
            LOG.error("Failed to send message for project ${project.name}", e)
        }
    }

    private fun sendMessage(message: FileMessage) {
        try {
            val jsonMessage = mapper.writeValueAsString(message)
            val project = projects.find { it.basePath == message.baseDir } ?: return
            projectSockets[project]?.emit("message", JSONObject(jsonMessage))
        } catch (e: Exception) {
            LOG.error("Failed to send message", e)
        }
    }

    private fun handleFilePathChange(
        project: Project,
        file: VirtualFile?,
        oldFilePath: String?,
        newFilePath: String?
    ) {
        if (file == null || oldFilePath == null || newFilePath == null) {
            return
        }

        if (!file.isFile || file.fileType.isBinary) {
            return
        }

        val projectBasePath = project.basePath ?: return
        if (!oldFilePath.startsWith(projectBasePath) || !newFilePath.startsWith(projectBasePath)) {
            return
        }

        val fileEditorManager = FileEditorManager.getInstance(project)
        val isFileOpen = fileEditorManager.allEditors.any { it.file?.path == file.path }

        if (!isFileOpen) {
            return
        }

        try {
            val oldRelativePath = Path.of(projectBasePath)
                .relativize(Path.of(oldFilePath))
                .toString()
                .replace("\\", "/")

            val newRelativePath = Path.of(projectBasePath)
                .relativize(Path.of(newFilePath))
                .toString()
                .replace("\\", "/")

            if (oldRelativePath != newRelativePath) {
                LOG.info("File path changed from $oldRelativePath to $newRelativePath")
                sendMessage(FileMessage("drop-file", oldRelativePath, projectBasePath, "intellij"))
                sendMessage(FileMessage("add-file", newRelativePath, projectBasePath, "intellij"))
            }
        } catch (e: Exception) {
            LOG.error("Error handling file path change", e)
        }
    }

    private fun removeAllProjectListeners() {
        projects.forEach { project ->
            disconnectProject(project)
        }
        projects.clear()
        projectFileEditorListeners.clear()
        projectFileListeners.clear()
    }
}
