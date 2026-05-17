package com.siamakerlab.vibecoder.server.tasks

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.TaskRepository
import com.siamakerlab.vibecoder.server.repo.TaskRow
import com.siamakerlab.vibecoder.shared.dto.TaskDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.taskRoutes(
    taskRepo: TaskRepository,
    queue: TaskQueue,
) {
    authenticate(AUTH_BEARER) {
        get("/api/projects/{projectId}/claude/tasks") {
            val projectId = call.parameters["projectId"] ?: throw ApiException(400, "bad_request", "projectId")
            call.respond(taskRepo.listForProjectAndType(projectId, com.siamakerlab.vibecoder.shared.dto.TaskType.CLAUDE_PROMPT)
                .map { it.toDto() })
        }
        get("/api/projects/{projectId}/claude/tasks/{taskId}") {
            val projectId = call.parameters["projectId"] ?: throw ApiException(400, "bad_request", "projectId")
            val taskId = call.parameters["taskId"] ?: throw ApiException(400, "bad_request", "taskId")
            val row = taskRepo.get(taskId) ?: throw ApiException(404, "task_not_found", taskId)
            if (row.projectId != projectId) throw ApiException(404, "task_not_found", taskId)
            call.respond(row.toDto())
        }
        post("/api/projects/{projectId}/claude/tasks/{taskId}/cancel") {
            val taskId = call.parameters["taskId"] ?: throw ApiException(400, "bad_request", "taskId")
            queue.cancel(taskId)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

fun TaskRow.toDto() = TaskDto(
    id = id, projectId = projectId, type = type, status = status, title = title,
    startedAt = startedAt, finishedAt = finishedAt, errorMessage = errorMessage,
)
