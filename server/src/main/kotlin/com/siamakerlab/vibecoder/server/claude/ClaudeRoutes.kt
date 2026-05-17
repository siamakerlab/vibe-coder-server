package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.TaskRepository
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.tasks.toDto
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.ClaudeTaskRequestDto
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import com.siamakerlab.vibecoder.shared.dto.TaskType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post

fun Routing.claudeRoutes(
    config: ServerConfig,
    workspace: WorkspacePath,
    projects: ProjectService,
    taskRepo: TaskRepository,
    queue: TaskQueue,
    hub: LogHub,
    clock: Clock,
    claudeRunner: ClaudeRunner,
    buildService: BuildService,
) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/claude/tasks") {
            val projectId = call.parameters["projectId"] ?: throw ApiException(400, "bad_request", "projectId")
            val row = projects.rowOrThrow(projectId)
            val body = call.receive<ClaudeTaskRequestDto>()
            if (body.prompt.isBlank()) throw ApiException(400, "bad_request", "prompt is required")

            val taskId = Ids.taskId()
            val logFile = workspace.taskLogFile(projectId, taskId)
            val task = taskRepo.create(taskId, projectId, TaskType.CLAUDE_PROMPT,
                title = body.prompt.take(80), prompt = body.prompt, logPath = logFile.toString())

            queue.submit(
                projectId = projectId, taskId = taskId,
                onStart = { taskRepo.setStatus(taskId, TaskStatus.RUNNING) },
                executor = { cancel ->
                    val logger = TaskLogger(taskId, logFile, hub, clock)
                    val exit = try {
                        claudeRunner.execute(
                            projectId = projectId, taskId = taskId,
                            sourcePath = java.nio.file.Path.of(row.sourcePath),
                            userPrompt = body.prompt, cancellation = cancel, logger = logger
                        )
                    } finally {
                        logger.close()
                    }
                    if (exit != 0) throw RuntimeException("claude exit code $exit")
                    if (body.autoBuild) buildService.runDebug(projectId, hub)
                },
                onSuccess = {
                    taskRepo.setStatus(taskId, TaskStatus.SUCCESS)
                    hub.publisher(taskId).emit(
                        com.siamakerlab.vibecoder.shared.ws.WsFrame.Done(taskId, TaskStatus.SUCCESS.name)
                    )
                },
                onFailure = { e ->
                    taskRepo.setStatus(taskId, TaskStatus.FAILED, e.message)
                    hub.publisher(taskId).emit(
                        com.siamakerlab.vibecoder.shared.ws.WsFrame.Done(taskId, TaskStatus.FAILED.name, e.message)
                    )
                },
                onCancel = {
                    taskRepo.setStatus(taskId, TaskStatus.CANCELED)
                    hub.publisher(taskId).emit(
                        com.siamakerlab.vibecoder.shared.ws.WsFrame.Done(taskId, TaskStatus.CANCELED.name)
                    )
                },
            )
            call.respond(HttpStatusCode.Accepted, task.toDto())
        }
    }
}
