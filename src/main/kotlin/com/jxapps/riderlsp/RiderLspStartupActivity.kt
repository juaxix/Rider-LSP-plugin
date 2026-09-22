// Copyright JxApps, Inc. All Rights Reserved.
package com.jxapps.riderlsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the LSP server for every opened project (when enabled in settings).
 * The server itself lives in [LspServerManager], a project-level service that is
 * disposed together with the project, so nothing outlives the project.
 */
class RiderLspStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!LspSettings.getInstance().enabled) return
        LspServerManager.getInstance(project).start()
    }
}
