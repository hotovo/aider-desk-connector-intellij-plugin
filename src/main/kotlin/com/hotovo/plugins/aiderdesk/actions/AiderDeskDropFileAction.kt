package com.hotovo.plugins.aiderdesk.actions

import com.hotovo.plugins.aiderdesk.AiderDeskConnectorAppService
import com.hotovo.plugins.aiderdesk.FileMessage
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

class AiderDeskDropFileAction : AnAction() {

    private val LOG = Logger.getInstance(AiderDeskDropFileAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        var isActionAvailable = false

        if (project != null && project.basePath != null) {
            val filesArray = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            if (!filesArray.isNullOrEmpty()) { // If multiple files/dirs are selected
                isActionAvailable = true
            } else { // If no array or empty array, check for single selection
                val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
                if (selectedFile != null) { // Allow single file or directory
                    isActionAvailable = true
                }
            }
        }
        e.presentation.isEnabledAndVisible = isActionAvailable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val projectBasePath = project.basePath ?: run {
            LOG.warn("Project base path is null for project: ${project.name}")
            return
        }

        val filesArray = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val appService = ApplicationManager.getApplication().getService(AiderDeskConnectorAppService::class.java)

        val filesToProcess = mutableListOf<VirtualFile>()

        if (!filesArray.isNullOrEmpty()) {
            filesToProcess.addAll(filesArray)
        } else {
            e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { filesToProcess.add(it) }
        }

        if (filesToProcess.isEmpty()) {
            LOG.warn("AiderDeskDropFileAction performed but no valid files or directories were found. This might indicate an issue if the action was enabled.")
            return
        }

        for (virtualFile in filesToProcess) {
            // Ensure the file is within the project's base path before attempting to relativize
            if (!virtualFile.path.startsWith(projectBasePath)) {
                LOG.warn("Selected item ${virtualFile.path} is not within project base path $projectBasePath. Skipping.")
                continue
            }

            val relativePath: String = try {
                Path.of(projectBasePath).relativize(Path.of(virtualFile.path)).toString().replace("\\", "/")
            } catch (ex: IllegalArgumentException) {
                LOG.error("Failed to relativize path for item ${virtualFile.path} with base path $projectBasePath. Skipping.", ex)
                continue
            }

            val fileMessage = FileMessage(
                action = "drop-file",
                path = relativePath,
                baseDir = projectBasePath
            )

            appService.sendFileMessage(fileMessage)
            val entryType = if (virtualFile.isDirectory) "directory" else "file"
            LOG.info("AiderDesk: DropFile action performed for $entryType: ${virtualFile.path}")
        }
    }
}
