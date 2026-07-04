package com.siamakerlab.vibecoder.shared

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * v1.145.18 — `pathSeg` 기반 ApiPath fun 의 **라우트 템플릿 ↔ 실제 URL 이중 계약** 잠금.
 *
 * 배경(회귀): `pathSeg` 가 `URLEncoder` 로 `{`→`%7B`, `}`→`%7D` 까지 인코딩하던 탓에
 * 서버가 `promptAutomationStart("{projectId}")` 로 라우트를 등록하면 경로가
 * `/api/projects/%7BprojectId%7D/...` 가 되고, Ktor `RoutingPath.parse` 는 리터럴
 * `{`/`}` 가 없는 세그먼트를 path parameter 가 아니라 상수 `{projectId}` 로 등록한다.
 * 결과적으로 실제 projectId(`caldo`) 요청이 `route_not_found` 404 로 떨어졌다
 * (automation/schedule/deps/stats/backup-file/build-webhook/agent-console 전부 무력화).
 *
 * 이 테스트는 두 계약을 동시에 보호한다:
 *  - 템플릿 인자(`{projectId}` 등)는 **리터럴 중괄호 그대로** 남아 Ktor 가 Parameter 로 인식.
 *  - 실제 식별자는 그대로 들어가고, 진짜 인코딩 대상(공백 등)은 여전히 `%20` 으로 인코딩.
 */
class ApiPathRouteTemplateTest {

    @Test
    fun `라우트 템플릿 인자의 중괄호는 보존된다 (Ktor path parameter)`() {
        ApiPath.promptAutomationStart("{projectId}") shouldBe
            "/api/projects/{projectId}/claude/automation/start"
        ApiPath.promptAutomationStop("{projectId}") shouldBe
            "/api/projects/{projectId}/claude/automation/stop"
        ApiPath.promptAutomationStatus("{projectId}") shouldBe
            "/api/projects/{projectId}/claude/automation/status"
        ApiPath.promptAutomationPreset("{presetId}") shouldBe
            "/api/prompt-automations/{presetId}"
        ApiPath.scheduledPrompt("{projectId}", "{scheduleId}") shouldBe
            "/api/projects/{projectId}/claude/schedule/{scheduleId}"
        ApiPath.automationScheduleToggle("{projectId}", "{scheduleId}") shouldBe
            "/api/projects/{projectId}/automation/schedules/{scheduleId}/toggle"
        ApiPath.buildWebhook("{projectId}") shouldBe "/api/webhooks/build/{projectId}"
        ApiPath.projectStats("{projectId}") shouldBe "/api/projects/{projectId}/stats"
    }

    @Test
    fun `인코딩된 중괄호(퍼센트)가 경로에 새어 나오지 않는다`() {
        ApiPath.promptAutomationStart("{projectId}") shouldNotContain "%7B"
        ApiPath.promptAutomationStart("{projectId}") shouldNotContain "%7D"
    }

    @Test
    fun `실제 식별자는 그대로 들어가고 진짜 인코딩 대상은 인코딩된다`() {
        // 정상 projectId — 변형 없음.
        ApiPath.promptAutomationStart("caldo") shouldBe
            "/api/projects/caldo/claude/automation/start"
        // 공백 등은 여전히 path 인코딩(%20) — pathSeg 본래 목적 보존.
        ApiPath.backupAutoFile("auto backup.tar.gz") shouldBe
            "/api/backup/auto/auto%20backup.tar.gz"
    }
}
