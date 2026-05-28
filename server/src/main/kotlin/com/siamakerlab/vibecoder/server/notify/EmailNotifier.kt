package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.config.EmailSection
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val log = KotlinLogging.logger {}

// v1.28.0 (Q-2) — SMTP passwordFile 최대 크기. 비번은 짧으므로 4KB 면 충분 + OOM 방어.
private const val MAX_PASSWORD_FILE_BYTES = 4096L

/**
 * v0.17.0 — SMTP 알림 발송.
 *
 * 비활성 (enabled=false) 시 모든 호출은 no-op. 활성 시 별도 dispatcher 에서
 * 비동기 발송 (요청 흐름 블락 안 함).
 *
 * 발송 실패는 server log 로 남기고 swallow — 알림 미발송이 본 작업의 차단
 * 사유가 되어선 안 됨.
 */
class EmailNotifier(
    private val configProvider: () -> EmailSection,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 동기 발송 (테스트/진단용). 활성 안 됐으면 false 반환, 활성·발송 성공 시 true. */
    suspend fun sendNow(subject: String, body: String): Boolean {
        val cfg = configProvider()
        if (!cfg.enabled) return false
        return try {
            withContext(Dispatchers.IO) { rawSend(cfg, subject, body) }
            true
        } catch (e: Throwable) {
            log.warn(e) { "email send failed: ${e.message}" }
            false
        }
    }

    /** Fire-and-forget. 비활성 시 즉시 return, 활성 시 background 발송. */
    fun send(subject: String, body: String) {
        val cfg = configProvider()
        if (!cfg.enabled) return
        scope.launch {
            try {
                rawSend(cfg, subject, body)
            } catch (e: Throwable) {
                log.warn(e) { "email send failed: ${e.message}" }
            }
        }
    }

    private fun resolvePassword(cfg: EmailSection): String? {
        if (cfg.passwordFile.isNotBlank()) {
            val p = Path.of(cfg.passwordFile)
            // v1.28.0 (Q-2 회수) — admin-only 신뢰 config 경로지만 방어적 가드:
            // 정규 파일 + 크기 제한. 디렉토리/특수파일·거대 파일(OOM) 거부. SMTP 비번은
            // 짧음. isRegularFile 은 심볼릭을 따라가므로 Docker secret(/run/secrets/*)
            // 호환 유지 — NOFOLLOW 는 일부러 안 씀.
            if (Files.isRegularFile(p) && Files.size(p) <= MAX_PASSWORD_FILE_BYTES) {
                val v = Files.readString(p).trim()
                if (v.isNotEmpty()) return v
            } else if (Files.exists(p)) {
                log.warn { "email passwordFile rejected (not a regular file or too large): ${cfg.passwordFile}" }
            }
        }
        return cfg.password.takeIf { it.isNotBlank() }
    }

    private fun rawSend(cfg: EmailSection, subject: String, body: String) {
        if (cfg.from.isBlank() || cfg.to.isBlank()) {
            log.warn { "email send skipped: from/to not configured" }
            return
        }
        val pwd = resolvePassword(cfg)
        val props = Properties().apply {
            put("mail.smtp.host", cfg.host)
            put("mail.smtp.port", cfg.port.toString())
            put("mail.smtp.auth", (pwd != null).toString())
            if (cfg.tls) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            put("mail.smtp.connectiontimeout", "10000")  // 10s
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
        }
        val session: Session = if (pwd != null) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(cfg.user, pwd)
            })
        } else {
            Session.getInstance(props)
        }
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.from))
            val recipients = cfg.to.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .map { InternetAddress(it) }.toTypedArray()
            setRecipients(Message.RecipientType.TO, recipients)
            setSubject("[vibe-coder] $subject", "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(msg)
        log.info { "email sent: '$subject' → ${cfg.to}" }
    }

    // ── 도메인 별 헬퍼 ──────────────────────────────────────────────

    fun buildResult(projectId: String, buildId: String, status: String, errorMessage: String?) {
        val subject = "Build $status — $projectId/$buildId"
        val body = buildString {
            appendLine("Project: $projectId")
            appendLine("Build:   $buildId")
            appendLine("Status:  $status")
            if (errorMessage != null) {
                appendLine()
                appendLine("Error:")
                appendLine(errorMessage.take(2000))
            }
            appendLine()
            appendLine("View: /projects/$projectId/builds/$buildId")
        }
        send(subject, body)
    }

    fun claudeUsageWarn(remainingPercent: Int, resetAt: String?) {
        val subject = "Claude usage warning: ${remainingPercent}% remaining"
        val body = buildString {
            appendLine("Claude usage is at ${100 - remainingPercent}% (${remainingPercent}% remaining).")
            if (resetAt != null) appendLine("Reset at: $resetAt")
            appendLine()
            appendLine("Check: /  (dashboard)")
        }
        send(subject, body)
    }

    fun diskUsageWarn(usedPercent: Int, freeGb: Double) {
        val subject = "Disk usage warning: ${usedPercent}% used"
        val body = "Disk is at ${usedPercent}% (free=${"%.1f".format(freeGb)} GB)."
        send(subject, body)
    }

    fun shutdown() {
        scope.cancel()
    }
}
