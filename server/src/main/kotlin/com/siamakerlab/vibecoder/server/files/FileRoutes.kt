package com.siamakerlab.vibecoder.server.files

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import com.siamakerlab.vibecoder.shared.dto.FileListDto
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Path

fun Routing.fileRoutes(service: UploadService) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/files/upload") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]!!
            val multipart = call.receiveMultipart()
            var saved: FileEntryDto? = null

            // We iterate `readPart()` directly instead of `forEachPart` extension —
            // Ktor 3.x ships its own `forEachPart` and defining a same-shape
            // extension risks an ambiguous-call when both are in scope.
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val name = part.originalFileName ?: "upload"
                        val mime = part.contentType?.toString()
                        val row = part.provider().toInputStream().use { stream ->
                            service.upload(projectId, name, mime, stream, sizeHint = null)
                        }
                        saved = FileEntryDto(
                            id = row.id, originalName = row.originalName, mimeType = row.mimeType,
                            sizeBytes = row.sizeBytes, createdAt = row.createdAt,
                        )
                    }
                } finally {
                    part.dispose()
                }
            }
            val result = saved ?: throw ApiException(400, "no_file_part", "no FileItem in multipart")
            call.respond(HttpStatusCode.Created, result)
        }
        get("/api/projects/{projectId}/files") {
            val projectId = call.parameters["projectId"]!!
            call.respond(FileListDto(entries = service.list(projectId)))
        }
        get("/api/projects/{projectId}/files/{fileId}/download") {
            val projectId = call.parameters["projectId"]!!
            val fileId = call.parameters["fileId"]!!
            val row = service.resolveForDownload(projectId, fileId)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, row.originalName,
                ).toString(),
            )
            call.respondFile(Path.of(row.filePath).toFile())
        }
        delete("/api/projects/{projectId}/files/{fileId}") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]!!
            val fileId = call.parameters["fileId"]!!
            service.delete(projectId, fileId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
