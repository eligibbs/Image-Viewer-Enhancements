package com.eligibbs.ive

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
class IVEStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        IVEOverlayController(project)
    }
}
