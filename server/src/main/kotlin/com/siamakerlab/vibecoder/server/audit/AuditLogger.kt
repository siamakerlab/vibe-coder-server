package com.siamakerlab.vibecoder.server.audit

import com.siamakerlab.vibecoder.server.repo.AuditLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/**
 * v0.15.0 — Audit 이벤트 기록 헬퍼.
 *
 * 각 route 가 직접 [AuditLogRepository.insert] 를 호출하는 대신, 본 facade 의
 * action-별 메서드를 호출. 일관된 action 이름 + 필수 필드 안전.
 *
 * Failure 정책: audit log 자체의 쓰기 실패는 **요청 흐름을 망가뜨리지 않는다**.
 * 모든 호출은 try/catch 로 감싸고 실패 시 server log 만 남김. audit 누락은
 * 보안 분석 단계의 손실이지 사용자 행위의 차단 사유가 아님.
 */
class AuditLogger(
    private val repo: AuditLogRepository,
) {

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log.warn(e) { "audit log write failed: ${e.message}" }
        }
    }

    /** kotlinx.serialization 기반 JSON 직렬화 — 인용부 / 백슬래시 안전. */
    private fun jsonDetail(builder: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject(builder))

    // ── Auth ─────────────────────────────────────────────────────────

    fun loginSuccess(username: String, userId: String, deviceId: String, ip: String?, channel: String) = safe {
        repo.insert(
            action = Actions.AUTH_LOGIN, result = Results.OK,
            userId = userId, deviceId = deviceId, ip = ip,
            resourceType = "user", resourceId = username,
            detail = jsonDetail { put("channel", channel) },
        )
    }

    fun loginFailure(username: String, ip: String?, reason: String) = safe {
        repo.insert(
            action = Actions.AUTH_LOGIN, result = Results.FAIL,
            ip = ip,
            resourceType = "user", resourceId = username,
            detail = jsonDetail { put("reason", reason) },
        )
    }

    fun setupAdmin(username: String, userId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.AUTH_SETUP, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "user", resourceId = username,
        )
    }

    fun logout(userId: String?, deviceId: String?, ip: String?) = safe {
        repo.insert(
            action = Actions.AUTH_LOGOUT, result = Results.OK,
            userId = userId, deviceId = deviceId, ip = ip,
        )
    }

    fun passwordChange(userId: String, ip: String?, ok: Boolean) = safe {
        repo.insert(
            action = Actions.AUTH_PASSWORD_CHANGE,
            result = if (ok) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "user", resourceId = userId,
        )
    }

    fun deviceRevoke(actorUserId: String, targetDeviceId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.DEVICE_REVOKE, result = Results.OK,
            userId = actorUserId, ip = ip,
            resourceType = "device", resourceId = targetDeviceId,
        )
    }

    // ── WebAuthn passkey (v0.48.0+) ──────────────────────────────────

    fun passkeyRegister(userId: String, credentialRowId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.PASSKEY_REGISTER, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "passkey", resourceId = credentialRowId,
        )
    }

    fun passkeyLogin(userId: String, credentialId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.PASSKEY_LOGIN, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "passkey", resourceId = credentialId.take(64),
        )
    }

    fun passkeyDelete(userId: String, credentialRowId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.PASSKEY_DELETE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "passkey", resourceId = credentialRowId,
        )
    }

    // ── Project ──────────────────────────────────────────────────────

    fun projectCreate(userId: String?, projectId: String, sourceType: String, ip: String?) = safe {
        repo.insert(
            action = Actions.PROJECT_CREATE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("sourceType", sourceType) },
        )
    }

    fun projectDelete(userId: String?, projectId: String, ip: String?, removed: Boolean) = safe {
        repo.insert(
            action = Actions.PROJECT_DELETE,
            result = if (removed) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
        )
    }

    // ── Build ────────────────────────────────────────────────────────

    fun buildEnqueue(userId: String?, projectId: String, buildId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.BUILD_ENQUEUE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
        )
    }

    fun buildCancel(userId: String?, projectId: String, buildId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.BUILD_CANCEL, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
        )
    }

    // ── Claude console ───────────────────────────────────────────────

    fun consoleNew(userId: String?, projectId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.CONSOLE_NEW, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
        )
    }

    fun consoleCancel(userId: String?, projectId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.CONSOLE_CANCEL, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
        )
    }

    // ── MCP / Git / Settings ─────────────────────────────────────────

    fun mcpInstall(userId: String?, taskId: String, selectedIds: List<String>, ip: String?) = safe {
        repo.insert(
            action = Actions.MCP_INSTALL, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "mcp.task", resourceId = taskId,
            detail = jsonDetail { put("selected", selectedIds.joinToString(",")) },
        )
    }

    fun mcpUnregister(userId: String?, ids: List<String>, ip: String?) = safe {
        repo.insert(
            action = Actions.MCP_UNREGISTER, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "mcp", resourceId = ids.joinToString(","),
        )
    }

    fun settingsUpdate(userId: String?, ip: String?, ok: Boolean, error: String? = null) = safe {
        repo.insert(
            action = Actions.SETTINGS_UPDATE,
            result = if (ok) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            detail = error?.let { e -> jsonDetail { put("error", e.take(500)) } },
        )
    }

    fun gitTokenRegister(userId: String?, host: String, ip: String?) = safe {
        repo.insert(
            action = Actions.GIT_TOKEN_REGISTER, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "git.host", resourceId = host,
        )
    }

    fun gitTokenDelete(userId: String?, host: String, removed: Boolean, ip: String?) = safe {
        repo.insert(
            action = Actions.GIT_TOKEN_DELETE,
            result = if (removed) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "git.host", resourceId = host,
        )
    }

    fun gitCommit(userId: String?, projectId: String, ok: Boolean, push: Boolean, ip: String?) = safe {
        repo.insert(
            action = Actions.GIT_COMMIT,
            result = if (ok) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("push", push) },
        )
    }

    // ── Publish (v0.22.0+) ───────────────────────────────────────────

    fun playUploadTriggered(userId: String?, projectId: String, buildId: String, ip: String?, track: String) = safe {
        repo.insert(
            action = Actions.PLAY_UPLOAD,
            result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
            detail = jsonDetail { put("track", track) },
        )
    }

    fun playUploadFailed(userId: String?, projectId: String, buildId: String, ip: String?, message: String?) = safe {
        repo.insert(
            action = Actions.PLAY_UPLOAD,
            result = Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
            detail = jsonDetail { put("error", message ?: "unknown") },
        )
    }

    fun testFlightUploadTriggered(userId: String?, projectId: String, buildId: String, ip: String?, groups: String?) = safe {
        repo.insert(
            action = Actions.TESTFLIGHT_UPLOAD,
            result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
            detail = jsonDetail { put("groups", groups ?: "") },
        )
    }

    fun testFlightUploadFailed(userId: String?, projectId: String, buildId: String, ip: String?, message: String?) = safe {
        repo.insert(
            action = Actions.TESTFLIGHT_UPLOAD,
            result = Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "build", resourceId = "$projectId/$buildId",
            detail = jsonDetail { put("error", message ?: "unknown") },
        )
    }

    // ── 2FA (v0.26.0+) ───────────────────────────────────────────────

    fun twoFactorEnabled(userId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.AUTH_2FA_ENABLE, result = Results.OK,
            userId = userId, ip = ip,
        )
    }

    fun twoFactorDisabled(userId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.AUTH_2FA_DISABLE, result = Results.OK,
            userId = userId, ip = ip,
        )
    }

    fun sessionTimeout(userId: String?, deviceId: String, ip: String?) = safe {
        repo.insert(
            action = Actions.AUTH_SESSION_TIMEOUT, result = Results.OK,
            userId = userId, deviceId = deviceId, ip = ip,
            resourceType = "device", resourceId = deviceId,
        )
    }

    // ── Agents (v0.31.0+) ────────────────────────────────────────────

    fun agentSave(userId: String?, ip: String?, name: String) = safe {
        repo.insert(
            action = Actions.AGENT_SAVE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "agent", resourceId = name,
        )
    }

    fun agentDelete(userId: String?, ip: String?, name: String, ok: Boolean) = safe {
        repo.insert(
            action = Actions.AGENT_DELETE,
            result = if (ok) Results.OK else Results.FAIL,
            userId = userId, ip = ip,
            resourceType = "agent", resourceId = name,
        )
    }

    // ── Automation (v0.33.0+) ────────────────────────────────────────

    fun scheduleCreate(userId: String?, ip: String?, projectId: String, cronExpr: String) = safe {
        repo.insert(
            action = Actions.SCHEDULE_CREATE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("cron", cronExpr) },
        )
    }

    fun scheduleDelete(userId: String?, ip: String?, scheduleId: String) = safe {
        repo.insert(
            action = Actions.SCHEDULE_DELETE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "schedule", resourceId = scheduleId,
        )
    }

    fun webhookSecretCreate(userId: String?, ip: String?, projectId: String, name: String) = safe {
        repo.insert(
            action = Actions.WEBHOOK_SECRET_CREATE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("name", name) },
        )
    }

    fun webhookSecretDelete(userId: String?, ip: String?, secretId: String) = safe {
        repo.insert(
            action = Actions.WEBHOOK_SECRET_DELETE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "webhook_secret", resourceId = secretId,
        )
    }

    fun webhookBuildTriggered(userId: String?, ip: String?, projectId: String, name: String) = safe {
        repo.insert(
            action = Actions.WEBHOOK_BUILD_TRIGGER, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("secretName", name) },
        )
    }

    // ── Build wrapper (v0.35.0+) ─────────────────────────────────────

    fun wrapperUpdate(userId: String?, ip: String?, projectId: String, version: String) = safe {
        repo.insert(
            action = Actions.WRAPPER_UPDATE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "project", resourceId = projectId,
            detail = jsonDetail { put("version", version) },
        )
    }

    // ── Users (v0.37.0+) ─────────────────────────────────────────────

    fun userCreate(userId: String?, ip: String?, newUsername: String, role: String) = safe {
        repo.insert(
            action = Actions.USER_CREATE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "user", resourceId = newUsername,
            detail = jsonDetail { put("role", role) },
        )
    }

    fun userRoleChange(userId: String?, ip: String?, targetUsername: String, newRole: String) = safe {
        repo.insert(
            action = Actions.USER_ROLE_CHANGE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "user", resourceId = targetUsername,
            detail = jsonDetail { put("role", newRole) },
        )
    }

    fun userDelete(userId: String?, ip: String?, targetUsername: String) = safe {
        repo.insert(
            action = Actions.USER_DELETE, result = Results.OK,
            userId = userId, ip = ip,
            resourceType = "user", resourceId = targetUsername,
        )
    }

    object Actions {
        const val AUTH_LOGIN = "auth.login"
        const val AUTH_LOGOUT = "auth.logout"
        const val AUTH_SETUP = "auth.setup"
        const val AUTH_PASSWORD_CHANGE = "auth.password.change"
        const val DEVICE_REVOKE = "device.revoke"
        const val PROJECT_CREATE = "project.create"
        const val PROJECT_DELETE = "project.delete"
        const val BUILD_ENQUEUE = "build.enqueue"
        const val BUILD_CANCEL = "build.cancel"
        const val CONSOLE_NEW = "console.new"
        const val CONSOLE_CANCEL = "console.cancel"
        const val MCP_INSTALL = "mcp.install"
        const val MCP_UNREGISTER = "mcp.unregister"
        const val SETTINGS_UPDATE = "settings.update"
        const val GIT_TOKEN_REGISTER = "git.token.register"
        const val GIT_TOKEN_DELETE = "git.token.delete"
        const val GIT_COMMIT = "git.commit"
        const val PLAY_UPLOAD = "publish.play.upload"
        const val TESTFLIGHT_UPLOAD = "publish.testflight.upload"
        const val AUTH_2FA_ENABLE = "auth.2fa.enable"
        const val AUTH_2FA_DISABLE = "auth.2fa.disable"
        const val AUTH_SESSION_TIMEOUT = "auth.session.timeout"
        const val AGENT_SAVE = "agent.save"
        const val AGENT_DELETE = "agent.delete"
        const val SCHEDULE_CREATE = "schedule.create"
        const val SCHEDULE_DELETE = "schedule.delete"
        const val WEBHOOK_SECRET_CREATE = "webhook.secret.create"
        const val WEBHOOK_SECRET_DELETE = "webhook.secret.delete"
        const val WEBHOOK_BUILD_TRIGGER = "webhook.build.trigger"
        const val WRAPPER_UPDATE = "wrapper.update"
        const val USER_CREATE = "user.create"
        const val USER_ROLE_CHANGE = "user.role.change"
        const val USER_DELETE = "user.delete"
        const val PASSKEY_REGISTER = "auth.passkey.register"
        const val PASSKEY_LOGIN = "auth.passkey.login"
        const val PASSKEY_DELETE = "auth.passkey.delete"
    }

    object Results {
        const val OK = "OK"
        const val FAIL = "FAIL"
        const val DENIED = "DENIED"
    }
}
