package com.shiny.inspectionmcp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ui.InspectionResultsView
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.DataManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.shiny.inspectionmcp.core.filterProblems
import com.shiny.inspectionmcp.core.formatJsonManually
import com.shiny.inspectionmcp.core.normalizeProblemsScope
import com.shiny.inspectionmcp.core.paginateProblems
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.*
import org.jetbrains.ide.HttpRequestHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun normalizeOptionalFilter(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isBlank()) {
        return null
    }
    if (trimmed.equals("all", ignoreCase = true)) {
        return null
    }
    return trimmed
}

class InspectionHandler : HttpRequestHandler() {
    
    @Volatile
    private var lastInspectionTriggerTime: Long = 0
    @Volatile
    private var inspectionInProgress: Boolean = false

    private val resultsStore = InspectionResultsStore
    
    override fun isSupported(request: FullHttpRequest): Boolean {
        return request.uri().startsWith("/api/inspection") && request.method() == HttpMethod.GET
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        try {
            val path = urlDecoder.path()
            when (path) {
                "/api/inspection/problems" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val severity = urlDecoder.parameters()["severity"]?.firstOrNull() ?: "all"
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull() ?: "whole_project"
                    val problemTypeRaw = urlDecoder.parameters()["problem_type"]?.firstOrNull()
                    val filePatternRaw = urlDecoder.parameters()["file_pattern"]?.firstOrNull()
                    val problemType = normalizeOptionalFilter(problemTypeRaw)
                    val filePattern = normalizeOptionalFilter(filePatternRaw)
                    val limit = urlDecoder.parameters()["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
                    val offset = urlDecoder.parameters()["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                    val result = ReadAction.compute<String, Exception> {
                        getInspectionProblems(projectName, severity, scope, problemType, filePattern, limit, offset)
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/trigger" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val scope = urlDecoder.parameters()["scope"]?.firstOrNull()
                    // Accept either `dir`, `directory`, or `path` for directory scoping
                    val directory = urlDecoder.parameters()["dir"]?.firstOrNull()
                        ?: urlDecoder.parameters()["directory"]?.firstOrNull()
                        ?: urlDecoder.parameters()["path"]?.firstOrNull()
                    val filesList = mutableListOf<String>()
                    val repeatedFiles = urlDecoder.parameters()["file"] ?: emptyList()
                    if (repeatedFiles.isNotEmpty()) filesList.addAll(repeatedFiles)
                    val filesParam = urlDecoder.parameters()["files"]?.firstOrNull()
                    if (!filesParam.isNullOrBlank()) {
                        filesList.addAll(filesParam.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    val includeUnversioned = urlDecoder.parameters()["include_unversioned"]?.firstOrNull()?.equals("true", ignoreCase = true) ?: true
                    val changedFilesMode = urlDecoder.parameters()["changed_files_mode"]?.firstOrNull()?.lowercase()?.trim()
                    val maxFiles = urlDecoder.parameters()["max_files"]?.firstOrNull()?.toIntOrNull()
                    val profile = urlDecoder.parameters()["profile"]?.firstOrNull()
                    val project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
                    if (project == null) {
                        sendJsonResponse(context, """{"error": "No project found"}""", HttpResponseStatus.NOT_FOUND)
                        return true
                    }
                    lastInspectionTriggerTime = System.currentTimeMillis()
                    inspectionInProgress = true
                    ApplicationManager.getApplication().invokeLater {
                        triggerInspectionAsync(
                            project = project,
                            scopeParam = scope,
                            directoryParam = directory,
                            files = if (filesList.isEmpty()) null else filesList,
                            includeUnversioned = includeUnversioned,
                            changedFilesMode = changedFilesMode,
                            maxFiles = maxFiles,
                            profileName = profile
                        )
                    }
                    val details = mutableMapOf<String, Any>(
                        "status" to "triggered",
                        "message" to "Inspection triggered. Wait 10-15 seconds then check status"
                    )
                    if (!scope.isNullOrBlank()) details["scope"] = scope
                    if (!directory.isNullOrBlank()) details["directory"] = directory
                    if (filesList.isNotEmpty()) details["files_requested"] = filesList.size
                    details["include_unversioned"] = includeUnversioned
                    if (!changedFilesMode.isNullOrBlank()) details["changed_files_mode"] = changedFilesMode
                    if (maxFiles != null) details["max_files"] = maxFiles
                    if (!profile.isNullOrBlank()) details["profile"] = profile
                    sendJsonResponse(context, formatJsonManually(details))
                }
                "/api/inspection/status" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val result = ReadAction.compute<String, Exception> {
                        val project = getCurrentProject(projectName)
                        if (project != null) {
                            getInspectionStatus(project)
                        } else {
                            """{"error": "No project found"}"""
                        }
                    }
                    sendJsonResponse(context, result)
                }
                "/api/inspection/wait" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val timeoutMs = urlDecoder.parameters()["timeout_ms"]?.firstOrNull()?.toLongOrNull()
                    val pollMs = urlDecoder.parameters()["poll_ms"]?.firstOrNull()?.toLongOrNull()
                    val result = waitForInspection(projectName, timeoutMs, pollMs)
                    sendJsonResponse(context, result)
                }
                "/api/inspection/analyze" -> {
                    val projectName = urlDecoder.parameters()["project"]?.firstOrNull()
                    val files = urlDecoder.parameters()["file"] ?: emptyList()
                    val severity = urlDecoder.parameters()["severity"]?.firstOrNull() ?: "all"
                    val format = urlDecoder.parameters()["format"]?.firstOrNull()?.lowercase()?.trim() ?: "md"
                    val timeoutMs = (urlDecoder.parameters()["timeout_ms"]?.firstOrNull()?.toLongOrNull() ?: 30000L)
                        .coerceIn(1000L, 120000L)
                    val limit = urlDecoder.parameters()["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
                    val offset = urlDecoder.parameters()["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                    if (files.isEmpty()) {
                        sendJsonResponse(
                            context,
                            """{"error": "At least one 'file' query parameter is required"}""",
                            HttpResponseStatus.BAD_REQUEST
                        )
                    } else {
                        val project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
                        if (project == null) {
                            sendJsonResponse(context, """{"error": "No project found"}""", HttpResponseStatus.NOT_FOUND)
                        } else if (!files.any { resolveAbsolutePath(it, project.basePath).let { p -> File(p).exists() } }) {
                            sendJsonResponse(
                                context,
                                """{"error": "None of the specified files exist on disk"}""",
                                HttpResponseStatus.BAD_REQUEST
                            )
                        } else {
                            val analyzeResult = analyzeFiles(project, files, severity, timeoutMs)
                            if (format == "json") {
                                sendJsonResponse(context, formatAnalyzeResultAsJson(analyzeResult, limit, offset))
                            } else {
                                sendResponse(context, formatAnalyzeResultAsMarkdown(analyzeResult), "text/markdown")
                            }
                        }
                    }
                }
                else -> {
                    sendJsonResponse(context, """{"error": "Unknown endpoint"}""", HttpResponseStatus.NOT_FOUND)
                }
            }
            return true
        } catch (_: Exception) {
            sendJsonResponse(context, """{"error": "Internal server error"}""", HttpResponseStatus.INTERNAL_SERVER_ERROR)
            return true
        }
    }
    
    private fun getInspectionProblems(
        projectName: String? = null,
        severity: String = "all", 
        scope: String = "whole_project",
        problemType: String? = null,
        filePattern: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): String {
        val project = getCurrentProject(projectName)
            ?: return """{"error": "No project found"}"""

        return try {
            val normalizedScope = normalizeProblemsScope(scope)
            val cachedProblems = resultsStore.getProblems(project.name)
            val hasSnapshot = resultsStore.hasSnapshot(project.name)
            val problems = when {
                cachedProblems != null -> cachedProblems
                hasSnapshot -> emptyList()
                else -> {
                    val extractor = EnhancedTreeExtractor()
                    extractor.extractAllProblems(project)
                }
            }
            
            if (problems.isNotEmpty() || hasSnapshot) {
                val currentFilePath = if (normalizedScope == "current_file") {
                    try {
                        com.intellij.openapi.fileEditor.FileEditorManager
                            .getInstance(project)
                            .selectedFiles
                            .firstOrNull()
                            ?.path
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                val filteredProblems = filterProblems(
                    problems = problems,
                    severity = severity,
                    scope = normalizedScope,
                    currentFilePath = currentFilePath,
                    problemType = problemType,
                    filePattern = filePattern
                )

                val page = paginateProblems(filteredProblems, limit, offset)
                
                val response = mapOf(
                    "status" to "results_available",
                    "project" to project.name,
                    "timestamp" to (resultsStore.getTimestamp(project.name) ?: System.currentTimeMillis()),
                    "total_problems" to page.total,
                    "problems_shown" to page.shown,
                    "problems" to page.problems,
                    "pagination" to mapOf(
                        "limit" to limit,
                        "offset" to offset,
                        "has_more" to page.hasMore,
                        "next_offset" to page.nextOffset
                    ),
                    "filters" to mapOf(
                        "severity" to severity,
                        "scope" to normalizedScope,
                        "problem_type" to (problemType ?: "all"),
                        "file_pattern" to (filePattern ?: "all")
                    ),
                    "method" to if (hasSnapshot) "inspection_view" else "enhanced_tree"
                )
                
                formatJsonManually(response)
            } else {
                """{"status": "no_results", "message": "No inspection results found. Either run an inspection first, or the last inspection found no problems (100% pass)."}"""
            }
        } catch (_: Exception) {
            """{"error": "Failed to get inspection problems"}"""
        }
    }

    private fun getInspectionStatus(project: Project): String {
        return try {
            val status = buildInspectionStatus(project)
            formatJsonManually(status)
        } catch (_: Exception) {
            """{"error": "Failed to get status"}"""
        }
    }

    private fun buildInspectionStatus(project: Project): MutableMap<String, Any> {
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val problemsWindows = listOf("Problems View", "Problems", "Inspection Results")
            .mapNotNull { toolWindowManager.getToolWindow(it) }
        val problemsWindow = problemsWindows.firstOrNull()
        val inspectionResultsWindow = toolWindowManager.getToolWindow("Inspection Results")

        val status = mutableMapOf<String, Any>()
        status["project_name"] = project.name

        val currentTime = System.currentTimeMillis()
        val timeSinceLastTrigger = currentTime - lastInspectionTriggerTime

        val dumbService = com.intellij.openapi.project.DumbService.getInstance(project)
        val isIndexing = dumbService.isDumb

        // Use the same extractor as the /problems endpoint so status matches real availability
        val cachedProblems = resultsStore.getProblems(project.name)
        val cachedTimestamp = resultsStore.getTimestamp(project.name)
        val hasInspectionSnapshot = cachedTimestamp != null
        val extractor = EnhancedTreeExtractor()
        val problemsSnapshot = if (hasInspectionSnapshot) {
            cachedProblems ?: emptyList()
        } else {
            try {
                extractor.extractAllProblems(project)
            } catch (_: Exception) { emptyList() }
        }
        val problemsAvailable = problemsSnapshot.isNotEmpty()
        status["total_problems"] = problemsSnapshot.size
        status["results_source"] = if (hasInspectionSnapshot) "inspection_view" else "tool_window"

        // Window visibility hints (best-effort)
        val problemsVisible = problemsWindow?.isVisible ?: false
        val inspectionVisible = inspectionResultsWindow?.isVisible ?: false
        status["problems_window_visible"] = problemsVisible || inspectionVisible
        if (inspectionResultsWindow != null && inspectionResultsWindow.isVisible) {
            status["active_tool_window"] = "Inspection Results"
        } else if (problemsWindow != null && problemsWindow.isVisible) {
            status["active_tool_window"] = "Problems View"
        }

        val isLikelyStillRunning = inspectionInProgress && timeSinceLastTrigger < 300000

        if (problemsAvailable && inspectionInProgress && timeSinceLastTrigger > 5000) {
            inspectionInProgress = false
        }

        status["is_scanning"] = isIndexing || isLikelyStillRunning
        status["has_inspection_results"] = hasInspectionSnapshot || problemsAvailable
        status["inspection_in_progress"] = inspectionInProgress
        status["time_since_last_trigger_ms"] = timeSinceLastTrigger
        status["indexing"] = isIndexing

        // Clear indicator for a clean inspection (recent, finished, and no problems)
        val recentlyCompleted = timeSinceLastTrigger < 60000
        val cleanInspection = recentlyCompleted && !isLikelyStillRunning && !problemsAvailable && hasInspectionSnapshot
        status["clean_inspection"] = cleanInspection

        return status
    }

    private fun waitForInspection(projectName: String?, timeoutMsRaw: Long?, pollMsRaw: Long?): String {
        val timeoutMs = (timeoutMsRaw ?: 180000L).coerceIn(1000L, 300000L)
        val pollMs = (pollMsRaw ?: 1000L).coerceIn(200L, 5000L).coerceAtMost(timeoutMs)
        val start = System.currentTimeMillis()
        var project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
        while (project == null) {
            if (System.currentTimeMillis() - start >= timeoutMs) {
                return formatWaitError("No project found", start, timeoutMs, pollMs, "no_project")
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitError("Wait interrupted", start, timeoutMs, pollMs, "interrupted")
            }

            project = ReadAction.compute<Project?, Exception> { getCurrentProject(projectName) }
        }

        val activeProject = project
            ?: return formatWaitError("No project found", start, timeoutMs, pollMs, "no_project")
        var lastStableCount: Int? = null
        var stableCountHits = 0
        var stableSince: Long? = null
        var status = ReadAction.compute<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }

        while (true) {
        val hasResults = status["has_inspection_results"] as? Boolean ?: false
        val cleanInspection = status["clean_inspection"] as? Boolean ?: false
        val isScanning = status["is_scanning"] as? Boolean ?: false
        val inProgress = status["inspection_in_progress"] as? Boolean ?: false
        val totalProblems = (status["total_problems"] as? Number)?.toInt()
        val resultsSource = status["results_source"] as? String
        val minStableMs = 5000L
        val now = System.currentTimeMillis()

        if (cleanInspection && hasResults && !isScanning && !inProgress) {
            return formatWaitResponse(status, start, timeoutMs, pollMs, true, "clean")
        }

        val timeSinceTrigger = (status["time_since_last_trigger_ms"] as? Number)?.toLong()
        if (
            resultsSource == "tool_window" &&
            !hasResults &&
            !isScanning &&
            !inProgress &&
            timeSinceTrigger != null &&
            timeSinceTrigger < 60000
        ) {
            status["wait_note"] = "Inspection finished but no results were captured. This can happen for clean runs or when the Inspection Results view was unavailable. Re-run the inspection or open the Inspection Results tool window."
            return formatWaitResponse(status, start, timeoutMs, pollMs, true, "no_results")
        }

        if (hasResults && !isScanning && !inProgress) {
            if (resultsSource == "inspection_view") {
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
            }

            if (totalProblems != null) {
                if (totalProblems == lastStableCount) {
                    if (stableCountHits == 0) {
                        stableSince = now
                    }
                    stableCountHits += 1
                } else {
                    stableCountHits = 0
                    stableSince = null
                    lastStableCount = totalProblems
                }

                if (stableSince != null && now - stableSince >= minStableMs) {
                    return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
                }
            } else {
                return formatWaitResponse(status, start, timeoutMs, pollMs, true, "results")
            }
        }

            if (System.currentTimeMillis() - start >= timeoutMs) {
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "timeout")
            }

            try {
                TimeUnit.MILLISECONDS.sleep(pollMs)
            } catch (_: Exception) {
                return formatWaitResponse(status, start, timeoutMs, pollMs, false, "interrupted")
            }

            status = ReadAction.compute<MutableMap<String, Any>, Exception> { buildInspectionStatus(activeProject) }
        }
    }

    private fun formatWaitResponse(
        status: MutableMap<String, Any>,
        startMs: Long,
        timeoutMs: Long,
        pollMs: Long,
        completed: Boolean,
        reason: String
    ): String {
        val response = status.toMutableMap()
        val elapsed = System.currentTimeMillis() - startMs
        response["wait_completed"] = completed
        response["timed_out"] = !completed && reason == "timeout"
        response["completion_reason"] = reason
        response["wait_ms"] = elapsed
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
        return formatJsonManually(response)
    }

    private fun formatWaitError(
        message: String,
        startMs: Long,
        timeoutMs: Long,
        pollMs: Long,
        reason: String
    ): String {
        val response = mutableMapOf<String, Any>()
        response["error"] = message
        response["wait_completed"] = false
        response["timed_out"] = reason == "no_project" || reason == "timeout"
        response["completion_reason"] = reason
        response["wait_ms"] = System.currentTimeMillis() - startMs
        response["timeout_ms"] = timeoutMs
        response["poll_ms"] = pollMs
        return formatJsonManually(response)
    }
    
    private fun triggerInspectionAsync(
        project: Project,
        scopeParam: String? = null,
        directoryParam: String? = null,
        files: List<String>? = null,
        includeUnversioned: Boolean = true,
        changedFilesMode: String? = null,
        maxFiles: Int? = null,
        profileName: String? = null,
    ) {
        try {
            inspectionInProgress = true
            resultsStore.clear(project.name)
            
            val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            // Clear prior results from both known locations to avoid stale reads
            listOf("Problems View", "Problems", "Inspection Results").forEach { name ->
                val tw = toolWindowManager.getToolWindow(name)
                if (tw != null) {
                    for (i in 0 until tw.contentManager.contentCount) {
                        val content = tw.contentManager.getContent(i)
                        if (content != null && content.component.javaClass.name.contains("InspectionResultsView")) {
                            tw.contentManager.removeContent(content, true)
                            try { Thread.sleep(200) } catch (_: Exception) {}
                            break
                        }
                    }
                }
            }
            
            FileDocumentManager.getInstance().saveAllDocuments()

            val scope: AnalysisScope = buildAnalysisScope(
                project = project,
                scopeParam = scopeParam,
                directoryParam = directoryParam,
                files = files,
                includeUnversioned = includeUnversioned,
                changedFilesMode = changedFilesMode,
                maxFiles = maxFiles
            )
            
            @Suppress("USELESS_CAST")
            val inspectionManager = InspectionManager.getInstance(project) as com.intellij.codeInspection.ex.InspectionManagerEx
            val profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project)
            val profile = if (!profileName.isNullOrBlank()) {
                profileManager.getProfile(profileName) ?: profileManager.currentProfile
            } else profileManager.currentProfile
            
            @Suppress("UnstableApiUsage", "USELESS_CAST")
            val globalContext = inspectionManager.createNewGlobalContext() as com.intellij.codeInspection.ex.GlobalInspectionContextImpl
            globalContext.setExternalProfile(profile)
            globalContext.currentScope = scope
            
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    @Suppress("UnstableApiUsage")
                    globalContext.doInspections(scope)
                },
                "Running Code Inspection",
                true,
                project
            )

            try {
                @Suppress("UnstableApiUsage")
                val viewReady = globalContext.initializeViewIfNeeded()
                val initialView = try {
                    @Suppress("UnstableApiUsage")
                    globalContext.view
                } catch (_: Exception) {
                    null
                }

                val projectName = project.name
                ApplicationManager.getApplication().executeOnPooledThread {
                    val extractor = EnhancedTreeExtractor()
                    val extractedFromContext = try {
                        ReadAction.compute<List<Map<String, Any>>, Exception> {
                            extractProblemsFromContext(globalContext, project)
                        }
                    } catch (e: Exception) {
                        rethrowIfCanceled(e)
                        emptyList()
                    }

                    val deadlineMs = System.currentTimeMillis() + 60000
                    var bestResults: List<Map<String, Any>> = extractedFromContext
                    var lastSize = bestResults.size
                    var lastChangeMs = System.currentTimeMillis()

                    fun extractFromViewSafe(view: InspectionResultsView): List<Map<String, Any>> {
                        val app = ApplicationManager.getApplication()
                        if (app.isDispatchThread) {
                            return extractor.extractAllProblemsFromInspectionView(view, project)
                        }
                        val holder = AtomicReference<List<Map<String, Any>>>()
                        app.invokeAndWait {
                            holder.set(extractor.extractAllProblemsFromInspectionView(view, project))
                        }
                        return holder.get() ?: emptyList()
                    }

                    val viewReadyOk = try {
                        viewReady.waitFor(60000)
                    } catch (e: Exception) {
                        rethrowIfCanceled(e)
                        false
                    }

                    while (System.currentTimeMillis() < deadlineMs) {
                        val view = try {
                            @Suppress("UnstableApiUsage")
                            globalContext.view
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            null
                        } ?: initialView

                        if (viewReadyOk && view != null) {
                            val attempt = try {
                                extractFromViewSafe(view)
                            } catch (e: Exception) {
                                rethrowIfCanceled(e)
                                emptyList()
                            }
                            if (attempt.size > bestResults.size) {
                                bestResults = attempt
                            }
                        }

                        val toolResults = try {
                            ReadAction.compute<List<Map<String, Any>>, Exception> {
                                extractor.extractAllProblems(project)
                            }
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            emptyList()
                        }
                        if (toolResults.size > bestResults.size) {
                            bestResults = toolResults
                        }

                        if (bestResults.size != lastSize) {
                            lastSize = bestResults.size
                            lastChangeMs = System.currentTimeMillis()
                        }

                        if (viewReadyOk && System.currentTimeMillis() - lastChangeMs >= 5000) {
                            break
                        }

                        try {
                            Thread.sleep(1000)
                        } catch (e: Exception) {
                            rethrowIfCanceled(e)
                            break
                        }
                    }

                    val extracted = bestResults

                    resultsStore.setProblems(projectName, extracted)
                    inspectionInProgress = false
                }
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                resultsStore.setProblems(project.name, emptyList())
                inspectionInProgress = false
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            inspectionInProgress = false
        }
    }

    private fun rethrowIfCanceled(e: Exception) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        }
    }

    private object InspectionResultsStore {
        private val problemsByProject = java.util.concurrent.ConcurrentHashMap<String, List<Map<String, Any>>>()
        private val timestampByProject = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun getProblems(projectName: String): List<Map<String, Any>>? {
            return problemsByProject[projectName]
        }

        fun getTimestamp(projectName: String): Long? {
            return timestampByProject[projectName]
        }

        fun hasSnapshot(projectName: String): Boolean {
            return timestampByProject.containsKey(projectName)
        }

        fun setProblems(projectName: String, problems: List<Map<String, Any>>) {
            problemsByProject[projectName] = problems
            timestampByProject[projectName] = System.currentTimeMillis()
        }

        fun clear(projectName: String) {
            problemsByProject.remove(projectName)
            timestampByProject.remove(projectName)
        }
    }

    private fun buildAnalysisScope(
        project: Project,
        scopeParam: String?,
        directoryParam: String?,
        files: List<String>?,
        includeUnversioned: Boolean,
        changedFilesMode: String?,
        maxFiles: Int?
    ): AnalysisScope {
        return try {
            val scopeLower = scopeParam?.lowercase()?.trim()

            if (scopeLower == "files" && !files.isNullOrEmpty()) {
                val base = project.basePath
                val resolved = files.mapNotNull { p ->
                    val absolute = try {
                        val path = Paths.get(p)
                        if (path.isAbsolute) p else if (!base.isNullOrBlank()) Paths.get(base, p).normalize().toString() else p
                    } catch (_: Exception) { p }
                    LocalFileSystem.getInstance().findFileByPath(absolute)
                }.toSet()
                return if (resolved.isEmpty()) AnalysisScope(project) else AnalysisScope(project, resolved)
            }

            if (scopeLower == "changed_files") {
                val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                val baseChanges = clm.allChanges

                val changeFiles = baseChanges.mapNotNull { ch ->
                    ch.virtualFile ?: ch.afterRevision?.file?.virtualFile ?: ch.beforeRevision?.file?.virtualFile
                }.toMutableList()

                // Best-effort Git staging filter when requested
                val mode = changedFilesMode?.lowercase()?.trim()
                if (!mode.isNullOrBlank() && mode != "all") {
                    val gitSets = computeGitStagingSets(project)
                    if (gitSets != null) {
                        val (stagedSet, unstagedSet) = gitSets
                        val basePath = project.basePath
                        if (!basePath.isNullOrBlank()) {
                            fun rel(p: String): String {
                                val rel = try {
                                    Paths.get(basePath).relativize(Paths.get(p)).toString()
                                } catch (_: Exception) { p }
                                return rel.replace('\\', '/')
                            }
                            changeFiles.retainAll { vf ->
                                val rp = rel(vf.path)
                                when (mode) {
                                    "staged" -> stagedSet.contains(rp)
                                    "unstaged" -> unstagedSet.contains(rp)
                                    else -> true
                                }
                            }
                        }
                    }
                }
                if (includeUnversioned) {
                    try {
                        val method = clm.javaClass.getMethod("getUnversionedFiles")
                        @Suppress("UNCHECKED_CAST")
                        val unversioned = method.invoke(clm) as? Collection<com.intellij.openapi.vfs.VirtualFile>
                        if (unversioned != null) changeFiles.addAll(unversioned)
                    } catch (_: Exception) {
                    }
                }
                val unique = changeFiles.distinct()
                val limited = if (maxFiles != null && maxFiles > 0) unique.take(maxFiles) else unique
                return if (limited.isEmpty()) AnalysisScope(project) else AnalysisScope(project, limited.toSet())
            }

            // 1) Explicit current file
            if (scopeLower == "current_file") {
                val vf = resolveActiveEditorFile(project)
                if (vf != null) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) return AnalysisScope(psiFile)
                    return AnalysisScope(project, setOf(vf))
                }
                // Fallback: no valid active editor file (e.g., TabPreviewDiffVirtualFile) → whole project
            }

            // 2) Directory scoping: `scope=directory` with `dir=...` or any non-empty directoryParam
            val dirPath = directoryParam?.trim()
            if ((scopeLower == "directory" || !dirPath.isNullOrBlank())) {
                val base = project.basePath
                val absolute = when {
                    dirPath.isNullOrBlank() -> null
                    Paths.get(dirPath).isAbsolute -> dirPath
                    !base.isNullOrBlank() -> Paths.get(base, dirPath).normalize().toString()
                    else -> dirPath
                }
                if (!absolute.isNullOrBlank()) {
                    val vfs = LocalFileSystem.getInstance().findFileByPath(absolute)
                    if (vfs != null && vfs.isDirectory) {
                        val psiDir = PsiManager.getInstance(project).findDirectory(vfs)
                        if (psiDir != null) return AnalysisScope(psiDir)
                        return AnalysisScope(project, setOf(vfs))
                    }
                }
                return AnalysisScope(project)
            }

            AnalysisScope(project)
        } catch (_: Exception) {
            AnalysisScope(project)
        }
    }

    private fun computeGitStagingSets(project: Project): Pair<Set<String>, Set<String>>? {
        val basePath = project.basePath ?: return null
        val gitDir = Paths.get(basePath, ".git").toFile()
        if (!gitDir.exists()) return null
        return try {
            val pb = ProcessBuilder("git", "status", "--porcelain", "-z")
            pb.directory(java.io.File(basePath))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val bytes = proc.inputStream.readAllBytes()
            proc.waitFor(2, TimeUnit.SECONDS)
            val out = bytes.toString(Charsets.UTF_8)
            val staged = mutableSetOf<String>()
            val unstaged = mutableSetOf<String>()
            var i = 0
            while (i < out.length) {
                val zero = out.indexOf('\u0000', i)
                if (zero == -1) break
                val entry = out.substring(i, zero)
                if (entry.length >= 3) {
                    val x = entry[0]
                    val y = entry[1]
                    // Entry format: XY<space>path or for renames: R<score><space>old<null>new
                    val spaceIdx = entry.indexOf(' ', 2)
                    if (spaceIdx >= 2) {
                        // Path may start after XY and one separating space; trim leading spaces
                        val pathPart = entry.substring(spaceIdx + 1).trimStart()
                        val normalized = pathPart.replace('\\', '/')
                        if (x != ' ') staged.add(normalized)
                        if (y != ' ') unstaged.add(normalized)
                    }
                }
                i = zero + 1
            }
            Pair(staged, unstaged)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveActiveEditorFile(project: Project): com.intellij.openapi.vfs.VirtualFile? {
        return try {
            val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val candidates = buildList {
                addAll(runCatching { fem.selectedFiles.asList() }.getOrNull() ?: emptyList())
                addAll(runCatching { fem.openFiles.asList() }.getOrNull() ?: emptyList())
            }
            val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
            candidates.firstOrNull { vf ->
                try {
                    vf.isValid && vf.isInLocalFileSystem && index.isInContent(vf)
                } catch (_: Exception) { false }
            }
        } catch (_: Exception) {
            null
        }
    }
    
    private fun getCurrentProject(projectName: String? = null): Project? {
        return try {
            if (projectName != null) {
                val projectByName = getProjectByName(projectName)
                if (projectByName != null) {
                    return projectByName
                }
            }
            
            val lastFocusedFrame = IdeFocusManager.getGlobalInstance().lastFocusedFrame
            val projectFromFrame = lastFocusedFrame?.project
            if (projectFromFrame != null && !projectFromFrame.isDefault && !projectFromFrame.isDisposed && projectFromFrame.isInitialized) {
                return projectFromFrame
            }
            
            val dataContextFuture = DataManager.getInstance().dataContextFromFocusAsync
            val dataContext = try {
                dataContextFuture.blockingGet(1000, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            
            val projectFromDataContext = dataContext?.let { CommonDataKeys.PROJECT.getData(it) }
            if (projectFromDataContext != null && !projectFromDataContext.isDefault && !projectFromDataContext.isDisposed && projectFromDataContext.isInitialized) {
                return projectFromDataContext
            }
            
            val projectManager = ProjectManager.getInstance()
            val openProjects = projectManager.openProjects
            
            val validProjects = openProjects.filter { project ->
                !project.isDefault && !project.isDisposed && project.isInitialized
            }
            
            if (validProjects.isEmpty()) {
                return null
            }
            
            for (project in validProjects) {
                val window = WindowManager.getInstance().suggestParentWindow(project)
                if (window != null && window.isActive) {
                    return project
                }
            }
            
            validProjects.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
    
    private fun getProjectByName(projectName: String): Project? {
        val projectManager = ProjectManager.getInstance()
        val openProjects = projectManager.openProjects
        val trimmed = projectName.trim()
        val pathHint = normalizeProjectPath(trimmed)

        return openProjects.firstOrNull { project ->
            !project.isDefault &&
            !project.isDisposed &&
            project.isInitialized &&
            projectMatches(project, trimmed, pathHint)
        }
    }

    private fun projectMatches(project: Project, projectName: String, pathHint: String?): Boolean {
        if (project.name == projectName) return true
        if (pathHint == null) return false

        val basePath = normalizeProjectPath(project.basePath)
        if (basePath != null && basePath == pathHint) return true

        val projectFilePath = normalizeProjectPath(project.projectFilePath)
        return projectFilePath == pathHint
    }

    private fun normalizeProjectPath(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!looksLikePath(raw)) return null
        val expanded = if (raw.startsWith("~")) {
            System.getProperty("user.home") + raw.removePrefix("~")
        } else {
            raw
        }
        return try {
            Paths.get(expanded).normalize().toAbsolutePath().toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikePath(value: String): Boolean {
        return value.contains('/') || value.contains('\\') || value.startsWith("~") || value.startsWith(".")
    }

    @Suppress("UnstableApiUsage")
    private fun extractProblemsFromContext(
        globalContext: com.intellij.codeInspection.ex.GlobalInspectionContextImpl,
        project: Project,
    ): List<Map<String, Any>> {
        val problems = mutableListOf<Map<String, Any>>()
        val seen = LinkedHashSet<String>()
        val tools = globalContext.tools.values

        for (toolGroup in tools) {
            val scopeStates = try {
                toolGroup.tools
            } catch (_: Exception) {
                emptyList()
            }
            for (state in scopeStates) {
                if (!state.isEnabled) continue
                val wrapper = try {
                    state.tool
                } catch (_: Exception) {
                    null
                } ?: continue

                val presentation = try {
                    globalContext.getPresentation(wrapper)
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    presentation.updateContent()
                } catch (_: Exception) {
                }

                val descriptors = try {
                    val elements = presentation.problemElements
                    val values = elements.getValues()
                    if (!values.isNullOrEmpty()) values else presentation.problemDescriptors
                } catch (_: Exception) {
                    emptyList()
                }

                for (descriptor in descriptors) {
                    val map = buildProblemMap(descriptor, wrapper, project) ?: continue
                    val key = listOf(
                        map["severity"],
                        map["inspectionType"],
                        map["file"],
                        map["line"],
                        map["column"],
                        map["description"],
                    ).joinToString("|")
                    if (seen.add(key)) {
                        problems.add(map)
                    }
                }
            }
        }

        return problems
    }

    private fun buildProblemMap(
        descriptor: com.intellij.codeInspection.CommonProblemDescriptor,
        wrapper: com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>,
        project: Project,
    ): Map<String, Any>? {
        val description = descriptor.descriptionTemplate?.takeIf { it.isNotBlank() } ?: return null
        val inspectionType = wrapper.shortName ?: wrapper.id
        val category = wrapper.groupDisplayName ?: wrapper.displayName

        var filePath = "unknown"
        var line = 0
        var column = 0
        var severity = "warning"

        if (descriptor is com.intellij.codeInspection.ProblemDescriptor) {
            val location = resolveProblemLocation(descriptor, project)
            if (location != null) {
                filePath = location.filePath
                line = location.line
                column = location.column
            }
            severity = severityFromHighlightType(descriptor.highlightType)
        }

        val typeLower = inspectionType.lowercase()
        severity = when {
            typeLower.contains("grazie") -> "grammar"
            typeLower.contains("spell") || typeLower.contains("aia") -> "typo"
            else -> severity
        }

        return mapOf(
            "description" to description,
            "file" to filePath,
            "line" to line,
            "column" to column,
            "severity" to severity,
            "category" to category,
            "inspectionType" to inspectionType,
            "source" to "inspection_context",
        )
    }
    
    private data class AnalyzeResult(
        val projectName: String,
        val fileResults: List<FileEntry>,
        val severity: String,
    ) {
        data class FileEntry(
            val rawPath: String,
            val status: String,
            val problems: List<Map<String, Any>>,
        )
    }

    private fun resolveAbsolutePath(rawPath: String, basePath: String?): String {
        return try {
            val p = Paths.get(rawPath)
            if (p.isAbsolute) rawPath
            else if (!basePath.isNullOrBlank()) Paths.get(basePath, rawPath).normalize().toString()
            else rawPath
        } catch (_: Exception) { rawPath }
    }

    private fun analyzeFiles(
        project: Project,
        filePaths: List<String>,
        severity: String,
        timeoutMs: Long,
    ): AnalyzeResult {
        val basePath = project.basePath
        val normalizedSeverity = normalizeOptionalFilter(severity)
        val fileEntries = LinkedHashMap<String, AnalyzeResult.FileEntry>()

        // Phase 1: Triage each file
        data class ResolvedFile(val rawPath: String, val absolutePath: String)
        val vfsResolvable = mutableListOf<Pair<ResolvedFile, com.intellij.openapi.vfs.VirtualFile>>()
        val cliFiles = mutableListOf<ResolvedFile>()

        for (rawPath in filePaths) {
            val absolutePath = resolveAbsolutePath(rawPath, basePath)
            val file = File(absolutePath)

            if (!file.exists()) {
                fileEntries[rawPath] = AnalyzeResult.FileEntry(rawPath, "not_found", emptyList())
                continue
            }

            // Check CLI result cache
            val cached = cliResultsCache[absolutePath]
            if (cached != null && file.lastModified() == cached.lastModified && file.length() == cached.fileSize) {
                fileEntries[rawPath] = AnalyzeResult.FileEntry(
                    rawPath, cached.result.status, filterAndDedup(cached.result.problems, normalizedSeverity)
                )
                continue
            }

            // Try to resolve VirtualFile for editor-open check
            val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath.replace('\\', '/'))
            if (vf != null) {
                vfsResolvable.add(ResolvedFile(rawPath, absolutePath) to vf)
            } else {
                cliFiles.add(ResolvedFile(rawPath, absolutePath))
            }
        }

        // Phase 2: Check which VFS-resolved files are open in the editor (one EDT call)
        val openFiles = mutableListOf<Pair<ResolvedFile, com.intellij.openapi.vfs.VirtualFile>>()
        if (vfsResolvable.isNotEmpty()) {
            val openStatus = mutableMapOf<String, Boolean>()
            ApplicationManager.getApplication().invokeAndWait {
                val fem = FileEditorManager.getInstance(project)
                for ((resolved, vf) in vfsResolvable) {
                    openStatus[resolved.absolutePath] = fem.isFileOpen(vf)
                }
            }
            for ((resolved, vf) in vfsResolvable) {
                if (openStatus[resolved.absolutePath] == true) {
                    openFiles.add(resolved to vf)
                } else {
                    cliFiles.add(resolved)
                }
            }
        }

        // Phase 3: Analyze open files individually (fast — reads from MarkupModel)
        for ((resolved, vf) in openFiles) {
            try {
                val result = analyzeOpenFile(project, vf, resolved.absolutePath, timeoutMs)
                fileEntries[resolved.rawPath] = AnalyzeResult.FileEntry(
                    resolved.rawPath, result.status, filterAndDedup(result.problems, normalizedSeverity)
                )
            } catch (e: Exception) {
                fileEntries[resolved.rawPath] = AnalyzeResult.FileEntry(
                    resolved.rawPath, "error: ${e.message ?: "unknown"}", emptyList()
                )
            }
        }

        // Phase 4: Batch all CLI files into one jb inspectcode invocation
        if (cliFiles.isNotEmpty()) {
            try {
                val batchResults = batchAnalyzeViaInspectCode(project, cliFiles.map { it.absolutePath }, timeoutMs)
                for (resolved in cliFiles) {
                    val result = batchResults[resolved.absolutePath] ?: FileAnalysisResult("ok", emptyList())
                    fileEntries[resolved.rawPath] = AnalyzeResult.FileEntry(
                        resolved.rawPath, result.status, filterAndDedup(result.problems, normalizedSeverity)
                    )
                }
            } catch (e: Exception) {
                for (resolved in cliFiles) {
                    fileEntries[resolved.rawPath] = AnalyzeResult.FileEntry(
                        resolved.rawPath, "error: ${e.message ?: "unknown"}", emptyList()
                    )
                }
            }
        }

        // Maintain original request order
        val ordered = filePaths.mapNotNull { fileEntries[it] }
        return AnalyzeResult(project.name, ordered, severity)
    }

    private fun filterAndDedup(
        problems: List<Map<String, Any>>,
        normalizedSeverity: String?,
    ): List<Map<String, Any>> {
        val seen = LinkedHashSet<String>()
        val deduped = problems.filter { problem ->
            val key = listOf(
                problem["severity"],
                problem["inspectionType"],
                problem["file"],
                problem["line"],
                problem["column"],
                problem["description"],
            ).joinToString("|")
            seen.add(key)
        }
        return if (normalizedSeverity != null) {
            deduped.filter { p ->
                (p["severity"] as? String)?.equals(normalizedSeverity, ignoreCase = true) == true
            }
        } else {
            deduped
        }
    }

    private fun formatAnalyzeResultAsJson(
        result: AnalyzeResult,
        limit: Int,
        offset: Int,
    ): String {
        val allProblems = result.fileResults.flatMap { it.problems }
        val total = allProblems.size
        val safeOffset = offset.coerceAtLeast(0).coerceAtMost(total)
        val safeLimit = limit.coerceAtLeast(1)
        val page = allProblems.drop(safeOffset).take(safeLimit)
        val hasMore = safeOffset + page.size < total

        val fileStatuses = mutableMapOf<String, String>()
        for (entry in result.fileResults) {
            fileStatuses[entry.rawPath] = entry.status
        }

        val response = mapOf(
            "status" to "results_available",
            "project" to result.projectName,
            "timestamp" to System.currentTimeMillis(),
            "method" to "highlight_analysis",
            "total_problems" to total,
            "problems_shown" to page.size,
            "problems" to page,
            "pagination" to mapOf(
                "limit" to safeLimit,
                "offset" to safeOffset,
                "has_more" to hasMore,
            ),
            "filters" to mapOf(
                "severity" to result.severity,
            ),
            "files" to fileStatuses,
        )
        return formatJsonManually(response)
    }

    private fun formatAnalyzeResultAsMarkdown(result: AnalyzeResult): String {
        val sb = StringBuilder()
        for (entry in result.fileResults) {
            sb.append("## ").append(entry.rawPath).append('\n')
            if (entry.status == "not_found") {
                sb.append("file not found\n")
            } else if (entry.status.startsWith("error:")) {
                sb.append(entry.status).append('\n')
            } else if (entry.problems.isEmpty()) {
                sb.append("no problems found\n")
            } else {
                for (problem in entry.problems) {
                    val sev = problem["severity"] ?: "info"
                    val desc = problem["description"] ?: ""
                    val line = problem["line"]
                    sb.append("- ").append(sev).append(": ").append(desc)
                    if (line != null && line != 0) {
                        sb.append(" (line ").append(line).append(')')
                    }
                    sb.append('\n')
                }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private data class FileAnalysisResult(
        val status: String,
        val problems: List<Map<String, Any>>,
    )

    private data class CliCacheEntry(
        val lastModified: Long,
        val fileSize: Long,
        val result: FileAnalysisResult,
    )

    private val cliResultsCache = ConcurrentHashMap<String, CliCacheEntry>()

    /**
     * Fast path: the file is already open in the editor, so highlights are populated.
     * Read the MarkupModel directly -- no tab manipulation needed.
     */
    private fun analyzeOpenFile(
        project: Project,
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        absolutePath: String,
        timeoutMs: Long,
    ): FileAnalysisResult {
        val docRef = AtomicReference<Document?>(null)
        val psiRef = AtomicReference<PsiFile?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
            docRef.set(doc)
            if (doc != null) {
                val psi = ReadAction.compute<PsiFile?, Exception> {
                    PsiManager.getInstance(project).findFile(virtualFile)
                }
                psiRef.set(psi)
                if (psi != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psi)
                }
            }
        }

        val document = docRef.get()
            ?: return FileAnalysisResult("error: could not load document", emptyList())
        val psiFile = psiRef.get()
            ?: return FileAnalysisResult("error: could not resolve PSI", emptyList())

        val analysisOk = waitForAnalysisComplete(project, psiFile, document, timeoutMs)
        val status = if (analysisOk) "ok" else "timeout"

        val problems = ReadAction.compute<List<Map<String, Any>>, Exception> {
            extractHighlightsFromDocument(document, project, absolutePath)
        }

        return FileAnalysisResult(status, problems)
    }

    /**
     * CLI path: run a single `jb inspectcode` for multiple files not open in the editor.
     * Uses semicolon-separated --include patterns so all files are analyzed in one invocation.
     * Parses the SARIF output and splits results back per file.
     */
    private fun batchAnalyzeViaInspectCode(
        project: Project,
        absolutePaths: List<String>,
        timeoutMs: Long,
    ): Map<String, FileAnalysisResult> {
        val jbExe = findJbExecutable()
            ?: return absolutePaths.associateWith {
                FileAnalysisResult("error: jb CLI not found (install via dotnet tool install -g JetBrains.ReSharper.GlobalTools)", emptyList())
            }

        val slnFile = findSolutionFile(project)
            ?: return absolutePaths.associateWith {
                FileAnalysisResult("error: no .sln file found for project", emptyList())
            }

        val slnDir = slnFile.parentFile

        // Build semicolon-separated relative paths for --include
        val includeArg = absolutePaths.joinToString(";") { absPath ->
            try {
                slnDir.toPath().relativize(Paths.get(absPath)).toString().replace('\\', '/')
            } catch (_: Exception) {
                absPath
            }
        }

        val scratchDir = File(System.getProperty("java.io.tmpdir"), "claude-inspectcode")
        scratchDir.mkdirs()
        val hash = absolutePaths.joinToString("|").hashCode().toUInt().toString(16)
        val outputFile = File(scratchDir, "inspectcode-$hash.json")
        val cachesHome = File(System.getProperty("user.home"), ".jb-inspectcode-cache")

        try {
            val command = listOf(
                jbExe,
                "inspectcode",
                slnFile.absolutePath,
                "--output=${outputFile.absolutePath}",
                "--format=Sarif",
                "--include=$includeArg",
                "--no-build",
                "--no-swea",
                "--caches-home=${cachesHome.absolutePath}",
                "--severity=INFO",
                "--verbosity=WARN",
            )

            val pb = ProcessBuilder(command)
            pb.directory(slnDir)
            pb.redirectErrorStream(true)
            val process = pb.start()

            // Drain stdout/stderr to avoid blocking
            val processOutput = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return absolutePaths.associateWith { FileAnalysisResult("timeout", emptyList()) }
            }

            if (!outputFile.exists()) {
                val errorMsg = "error: inspectcode produced no output (exit code ${process.exitValue()})"
                return absolutePaths.associateWith { FileAnalysisResult(errorMsg, emptyList()) }
            }

            val sarifContent = outputFile.readText(Charsets.UTF_8)
            val problemsByFile = parseSarifResultsByFile(sarifContent, slnDir)

            // Build per-file results and update cache
            val results = mutableMapOf<String, FileAnalysisResult>()
            for (absPath in absolutePaths) {
                val problems = problemsByFile[absPath] ?: emptyList()
                val result = FileAnalysisResult("ok", problems)
                results[absPath] = result

                val file = File(absPath)
                if (file.exists()) {
                    cliResultsCache[absPath] = CliCacheEntry(
                        lastModified = file.lastModified(),
                        fileSize = file.length(),
                        result = result,
                    )
                }
            }
            return results
        } catch (e: Exception) {
            return absolutePaths.associateWith {
                FileAnalysisResult("error: ${e.message ?: "inspectcode failed"}", emptyList())
            }
        } finally {
            try { outputFile.delete() } catch (_: Exception) {}
        }
    }

    // Cache .sln per project (won't change during a session)
    private val solutionFileCache = ConcurrentHashMap<String, File?>()

    private fun findSolutionFile(project: Project): File? {
        val key = project.name + "|" + (project.basePath ?: "")
        return solutionFileCache.getOrPut(key) { searchForSolutionFile(project) }
    }

    private fun searchForSolutionFile(project: Project): File? {
        val basePath = project.basePath ?: return null
        var dir: File? = File(basePath)
        var levels = 0
        val projectName = project.name

        while (dir != null && levels <= 3) {
            val slnFiles = dir.listFiles { f -> f.isFile && f.extension.equals("sln", ignoreCase = true) }
            if (slnFiles != null && slnFiles.isNotEmpty()) {
                if (slnFiles.size == 1) return slnFiles[0]
                // Prefer the one matching the project name
                val preferred = slnFiles.firstOrNull { it.nameWithoutExtension.equals(projectName, ignoreCase = true) }
                return preferred ?: slnFiles[0]
            }
            dir = dir.parentFile
            levels++
        }
        return null
    }

    private fun findJbExecutable(): String? {
        // Check if `jb` is on PATH
        val jbNames = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("jb.exe", "jb.cmd", "jb.bat", "jb")
        } else {
            listOf("jb")
        }

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            for (name in jbNames) {
                val candidate = File(dir, name)
                if (candidate.exists() && candidate.canExecute()) {
                    return candidate.absolutePath
                }
            }
        }

        // Well-known locations
        val home = System.getProperty("user.home") ?: return null
        val wellKnown = listOf(
            Paths.get(home, ".dotnet", "tools", "jb").toString(),
            Paths.get(home, ".dotnet", "tools", "jb.exe").toString(),
        )
        for (path in wellKnown) {
            val f = File(path)
            if (f.exists()) return f.absolutePath
        }

        return null
    }

    private val sarifParser = Json { ignoreUnknownKeys = true }

    /**
     * Parses SARIF JSON and groups results by resolved absolute file path.
     * Each result's file is extracted from physicalLocation.artifactLocation.uri
     * and resolved against [slnDir].
     */
    private fun parseSarifResultsByFile(
        sarifJson: String,
        slnDir: File,
    ): Map<String, List<Map<String, Any>>> {
        val problemsByFile = mutableMapOf<String, MutableList<Map<String, Any>>>()
        try {
            val root = sarifParser.parseToJsonElement(sarifJson).jsonObject
            val runs = root["runs"]?.jsonArray ?: return emptyMap()
            if (runs.isEmpty()) return emptyMap()

            val run = runs[0].jsonObject
            val results = run["results"]?.jsonArray ?: return emptyMap()

            // Build ruleId -> rule info lookup from tool.driver.rules
            val ruleMap = mutableMapOf<String, kotlinx.serialization.json.JsonObject>()
            try {
                val driver = run["tool"]?.jsonObject?.get("driver")?.jsonObject
                val rules = driver?.get("rules")?.jsonArray
                if (rules != null) {
                    for (rule in rules) {
                        val ruleObj = rule.jsonObject
                        val id = ruleObj["id"]?.jsonPrimitive?.contentOrNull
                        if (id != null) ruleMap[id] = ruleObj
                    }
                }
            } catch (_: Exception) {}

            for (resultElement in results) {
                val result = resultElement.jsonObject

                val ruleId = result["ruleId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val level = result["level"]?.jsonPrimitive?.contentOrNull ?: "warning"
                val messageText = result["message"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""

                var line = 0
                var column = 0
                var fileUri: String? = null
                try {
                    val locations = result["locations"]?.jsonArray
                    if (locations != null && locations.isNotEmpty()) {
                        val physLoc = locations[0].jsonObject["physicalLocation"]?.jsonObject
                        fileUri = physLoc?.get("artifactLocation")?.jsonObject
                            ?.get("uri")?.jsonPrimitive?.contentOrNull
                        val region = physLoc?.get("region")?.jsonObject
                        if (region != null) {
                            line = region["startLine"]?.jsonPrimitive?.intOrNull ?: 0
                            column = region["startColumn"]?.jsonPrimitive?.intOrNull ?: 0
                        }
                    }
                } catch (_: Exception) {}

                // Resolve SARIF URI to absolute path
                val absolutePath = if (fileUri != null) {
                    try {
                        slnDir.toPath().resolve(fileUri).normalize().toAbsolutePath().toString()
                    } catch (_: Exception) { fileUri }
                } else {
                    "unknown"
                }

                // Map severity: check properties.ideaSeverity first, then SARIF level
                val properties = try { result["properties"]?.jsonObject } catch (_: Exception) { null }
                val ideaSeverity = properties?.get("ideaSeverity")?.jsonPrimitive?.contentOrNull
                val severity = mapSarifSeverity(level, ideaSeverity)

                // Category from rule lookup or ruleId
                val category = try {
                    val ruleInfo = ruleMap[ruleId]
                    val relationships = ruleInfo?.get("relationships")?.jsonArray
                    if (relationships != null && relationships.isNotEmpty()) {
                        val target = relationships[0].jsonObject["target"]?.jsonObject
                        val toolComponent = target?.get("toolComponent")?.jsonObject
                        toolComponent?.get("name")?.jsonPrimitive?.contentOrNull ?: ruleId
                    } else {
                        ruleId
                    }
                } catch (_: Exception) { ruleId }

                problemsByFile.getOrPut(absolutePath) { mutableListOf() }.add(
                    mapOf(
                        "description" to messageText,
                        "file" to absolutePath,
                        "line" to line,
                        "column" to column,
                        "severity" to severity,
                        "category" to category,
                        "inspectionType" to ruleId,
                        "source" to "inspectcode_cli",
                        "locationKnown" to true,
                    )
                )
            }
        } catch (_: Exception) {
            // If SARIF parsing fails completely, return empty
        }
        return problemsByFile
    }

    private fun mapSarifSeverity(level: String, ideaSeverity: String?): String {
        // Prefer IDEA severity if available
        if (ideaSeverity != null) {
            return when (ideaSeverity.uppercase()) {
                "ERROR" -> "error"
                "WARNING" -> "warning"
                "WEAK WARNING" -> "weak_warning"
                "SUGGESTION" -> "weak_warning"
                "HINT" -> "info"
                "INFORMATION" -> "info"
                else -> mapSarifLevel(level)
            }
        }
        return mapSarifLevel(level)
    }

    private fun mapSarifLevel(level: String): String {
        return when (level.lowercase()) {
            "error" -> "error"
            "warning" -> "warning"
            "note" -> "weak_warning"
            else -> "info"
        }
    }

    private fun waitForAnalysisComplete(
        project: Project,
        psiFile: PsiFile,
        document: Document,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val stabilizationMs = 2000L
        var lastCount = -1
        var stableSince = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val finished = ReadAction.compute<Boolean, Exception> {
                try {
                    DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psiFile)
                } catch (_: Exception) {
                    false
                }
            }

            if (finished) {
                // Check highlight count stabilization
                val currentCount = ReadAction.compute<Int, Exception> {
                    try {
                        val markup = DocumentMarkupModel.forDocument(document, project, false)
                        markup?.allHighlighters?.size ?: 0
                    } catch (_: Exception) { 0 }
                }

                if (currentCount != lastCount) {
                    lastCount = currentCount
                    stableSince = System.currentTimeMillis()
                }

                if (System.currentTimeMillis() - stableSince >= stabilizationMs) {
                    return true
                }
            }

            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun extractHighlightsFromDocument(
        document: Document,
        project: Project,
        filePath: String,
    ): List<Map<String, Any>> {
        val markup = DocumentMarkupModel.forDocument(document, project, false)
            ?: return emptyList()

        val problems = mutableListOf<Map<String, Any>>()

        for (highlighter in markup.allHighlighters) {
            val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
            if (!isInspectionHighlight(info)) continue

            val startOffset = highlighter.startOffset
            val line = document.getLineNumber(startOffset) + 1
            val column = startOffset - document.getLineStartOffset(line - 1) + 1

            val description = info.toolTip?.let { stripHtml(it) }
                ?: info.description
                ?: continue

            val severityStr = mapHighlightSeverity(info.severity)
            val inspectionId = info.inspectionToolId ?: info.type.toString()
            val category = info.severity.displayName ?: info.severity.myName

            problems.add(
                mapOf(
                    "description" to description,
                    "file" to filePath,
                    "line" to line,
                    "column" to column,
                    "severity" to severityStr,
                    "category" to category,
                    "inspectionType" to inspectionId,
                    "source" to "highlight_analysis",
                    "locationKnown" to true,
                )
            )
        }

        return problems
    }

    private fun isInspectionHighlight(info: HighlightInfo): Boolean {
        // Accept if it has an inspection tool ID (IntelliJ-native inspections)
        if (info.inspectionToolId != null) return true

        // ReSharper diagnostics often lack inspectionToolId but have a tooltip/description.
        // Accept any highlight with meaningful diagnostic text.
        val hasContent = !info.toolTip.isNullOrBlank() || !info.description.isNullOrBlank()
        if (!hasContent) return false

        // Skip TODO highlights
        if (info.type.toString().contains("TODO", ignoreCase = true)) return false

        return true
    }

    private fun mapHighlightSeverity(severity: HighlightSeverity): String {
        return when {
            severity >= HighlightSeverity.ERROR -> "error"
            severity >= HighlightSeverity.WARNING -> "warning"
            severity >= HighlightSeverity.WEAK_WARNING -> "weak_warning"
            else -> "info"
        }
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"")
            .trim()
    }

    private fun sendJsonResponse(
        context: ChannelHandlerContext,
        jsonContent: String,
        status: HttpResponseStatus = HttpResponseStatus.OK,
    ) {
        sendResponse(context, jsonContent, "application/json", status)
    }

    private fun sendResponse(
        context: ChannelHandlerContext,
        body: String,
        contentType: String,
        status: HttpResponseStatus = HttpResponseStatus.OK,
    ) {
        val content = Unpooled.copiedBuffer(body, Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content)
        response.headers()[HttpHeaderNames.CONTENT_TYPE] = contentType
        response.headers()[HttpHeaderNames.CONTENT_LENGTH] = content.readableBytes()
        response.headers()[HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN] = "*"
        context.writeAndFlush(response)
    }
}
