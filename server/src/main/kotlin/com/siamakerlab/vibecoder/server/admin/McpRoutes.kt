package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.McpCatalog
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.dto.McpFileUploadResponseDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream

private val log = KotlinLogging.logger {}

/**
 * MCP 카탈로그 페이지 라우트 — v0.8.0.
 *
 *   GET  /env-setup/mcp                    — 카탈로그 페이지 (체크박스 + 설정 폼)
 *   POST /env-setup/mcp/install            — 체크된 항목 일괄 설치 (configValues 같이)
 *   POST /env-setup/mcp/unregister         — 체크된 항목 .mcp.json 에서 제거
 */
fun Routing.mcpRoutes(
    authDeps: AdminRoutesDeps,
    mcp: McpService,
) {
    get("/env-setup/mcp") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val states = mcp.detectAll().associateBy { it.id }
        val flash = call.request.queryParameters["flash"]
        call.respondText(
            McpTemplates.catalogPage(sess.username, states, flash),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/mcp/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = call.receiveParameters()
        // 체크박스: name="select" value="<id>" (multiple)
        val selectedIds = form.getAll("select").orEmpty().distinct()
        if (selectedIds.isEmpty()) {
            call.respondRedirect("/env-setup/mcp?flash=no-selection")
            return@post
        }
        // config 값: name="cfg.<id>.<key>" value=<user input>
        val selections: Map<String, Map<String, String>> = selectedIds.associateWith { id ->
            val entry = McpCatalog.get(id) ?: return@associateWith emptyMap()
            entry.configFields.mapNotNull { f ->
                val v = form["cfg.$id.${f.key}"].orEmpty().trim()
                if (v.isNotEmpty()) f.key to v else null
            }.toMap()
        }
        val taskId = try {
            mcp.spawnBatch(selections)
        } catch (e: ApiException) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: "설치 거부됨"),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        }
        log.info { "MCP install batch by ${sess.username}: ${selectedIds.joinToString(",")}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    post("/env-setup/mcp/unregister") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = call.receiveParameters()
        val ids = form.getAll("select").orEmpty().distinct()
        if (ids.isEmpty()) {
            call.respondRedirect("/env-setup/mcp?flash=no-selection")
            return@post
        }
        mcp.unregister(ids)
        log.info { "MCP unregister by ${sess.username}: ${ids.joinToString(",")}" }
        call.respondRedirect("/env-setup/mcp?flash=unregistered")
    }

    /**
     * v0.11.0 — Secret 파일 업로드 (Service Account JSON / .p8 등).
     * UI 의 file input onChange 가 즉시 ajax 로 호출 → 응답의 path 를
     * hidden input 에 채워서 일반 install POST 에 포함.
     */
    post("/env-setup/mcp/{mcpId}/file/{fieldKey}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val mcpId = call.parameters["mcpId"]!!
        val fieldKey = call.parameters["fieldKey"]!!
        val multipart = call.receiveMultipart()
        var bytes: ByteArray? = null
        var fileName: String? = null
        try {
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem && bytes == null) {
                        fileName = part.originalFileName
                        bytes = part.provider().toInputStream().use { it.readBytes() }
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (e: Throwable) {
            throw ApiException(400, "multipart", "multipart 파싱 실패: ${e.message}")
        }
        val data = bytes ?: throw ApiException(400, "empty",
            "파일 part 가 비어 있습니다 (input name 무엇이든 허용).")
        val path = mcp.uploadConfigFile(mcpId, fieldKey, data, fileName)
        log.info { "MCP secret file by ${sess.username}: $mcpId/$fieldKey → $path" }
        // ajax 호출이므로 JSON 응답.
        call.respond(McpFileUploadResponseDto(path))
    }
}
