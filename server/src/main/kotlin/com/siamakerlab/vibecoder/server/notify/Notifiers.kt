package com.siamakerlab.vibecoder.server.notify

/**
 * v0.27.0 — Email + Webhook 통합 facade.
 *
 * 호출처 (BuildService, ClaudeUsageMonitor, DiskMonitor 등) 가 두 채널을 따로
 * 알 필요 없도록 모음. EmailNotifier / WebhookNotifier 각각은 자기 enabled
 * 플래그 안 보면 silent skip — facade 는 단순히 둘 다 trigger.
 *
 * 어느 한 쪽이 null 일 수 있어 builder 위주 wrapping.
 */
class Notifiers(
    val email: EmailNotifier? = null,
    val webhook: WebhookNotifier? = null,
    /** v0.46.0 — Phase 25 Web Push (browser PushManager). */
    val webPush: WebPushNotifier? = null,
    /** v0.68.0 — Phase 47 polling-based notification (Android Group C). */
    val notifications: NotificationService? = null,
    /** v0.68.0 — fan-out 대상 사용자 목록 provider. null 이면 BUCKET_LEGACY. */
    val userIdsProvider: (() -> List<String?>)? = null,
) {
    /**
     * v0.55.0 — Phase 34 Prometheus counters get bumped alongside delivery.
     * `var` so `ServerMain` can wire this after `MetricsRegistry` is constructed
     * (Notifiers itself has no MetricsRegistry dependency, so it's built earlier).
     */
    var metrics: com.siamakerlab.vibecoder.server.metrics.MetricsRegistry? = null
    fun buildResult(projectId: String, buildId: String, status: String, errorMessage: String?) {
        email?.buildResult(projectId, buildId, status, errorMessage)
        webhook?.buildResult(projectId, buildId, status, errorMessage)
        webPush?.broadcast(
            title = "빌드 $status",
            body = "프로젝트 $projectId / 빌드 $buildId — ${errorMessage ?: "성공"}",
            url = "/projects/$projectId/builds/$buildId",
        )
        // v0.68.0 — Phase 47 Android polling.
        notifications?.let { svc ->
            val uids = userIdsProvider?.invoke() ?: emptyList()
            if (status == "SUCCESS") svc.emitBuildSuccess(projectId, buildId, uids)
            else svc.emitBuildFailed(projectId, buildId, errorMessage, uids)
        }
        metrics?.inc(
            "vibe_build_total",
            "Build outcomes by status",
            labels = mapOf("status" to status.lowercase()),
        )
    }

    fun claudeUsageWarn(remainingPercent: Int, resetAt: String?) {
        email?.claudeUsageWarn(remainingPercent, resetAt)
        webhook?.claudeUsageWarn(remainingPercent, resetAt)
        webPush?.broadcast(
            title = "Claude 사용량 임계치",
            body = "남은 ${remainingPercent}% (리셋 ${resetAt ?: "예정 미상"})",
            url = "/usage",
        )
        metrics?.inc("vibe_claude_usage_warn_total", "Claude usage threshold alerts")
    }

    fun diskUsageWarn(usedPercent: Int, freeGb: Double) {
        email?.diskUsageWarn(usedPercent, freeGb)
        webhook?.diskUsageWarn(usedPercent, freeGb)
        webPush?.broadcast(
            title = "디스크 사용량 경고",
            body = "${usedPercent}% 사용중 — 여유 ${"%.1f".format(freeGb)} GB",
            url = "/",
        )
        metrics?.inc("vibe_disk_usage_warn_total", "Disk usage threshold alerts")
    }

    fun shutdown() {
        email?.shutdown()
        webhook?.shutdown()
    }
}
