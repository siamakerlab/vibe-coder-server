package com.siamakerlab.vibecoder.shared.dto

/**
 * 프로젝트/콘솔 turn 상태의 **단일 진실(SSOT)**.
 *
 * v1.114.0 — 이전엔 "ready"/"responding"/"waiting"/"stopped"/"error"/"idle" 문자열이
 * `ClaudeSessionManager`, `WsFrame`, `WebProjectRoutes`, `WebProjectTemplates`(인라인 JS)
 * 등 6개+ 파일에 리터럴로 흩어져 있었다(단일 진실 부재). 한 곳만 오타·누락돼도 상태 뱃지가
 * 조용히 깨졌다. 이 enum 이 상태 집합·직렬화 문자열·busy 파생·i18n 키를 한곳에 모은다.
 *
 * - [wire] : WebSocket 프레임([com.siamakerlab.vibecoder.shared.ws.WsFrame.ConsoleBusyState] /
 *   [com.siamakerlab.vibecoder.shared.ws.WsFrame.ProjectBusyChanged])·SSR `data-state`·i18n
 *   키(`projects.status.<wire>`)에 쓰이는 직렬화 문자열. **Android 클라이언트와의 호환을 위해
 *   wire 값(소문자)은 절대 변경 금지.**
 * - [busy] : idle 가드/뱃지의 boolean 진위. RESPONDING/WAITING 만 "작업 중". 이 파생으로
 *   (busy, state) 쌍을 따로 넘기다 어긋나던 footgun 을 제거한다.
 */
enum class ProjectState(val wire: String, val busy: Boolean) {
    /** 유휴 — 프롬프트 대기. 정상 완료 후 / fresh. (그레이) */
    READY("ready", false),

    /** 응답 중 — assistant/tool 프레임 스트리밍 중. (초록, pulse) */
    RESPONDING("responding", true),

    /** 백그라운드 작업(run_in_background / Task) 완료 대기 — turn 재개 보류. (노랑) */
    WAITING("waiting", true),

    /** 중단됨 — cancel / crash / idle reap / rate-limit 소진 / interrupt. (보라) */
    STOPPED("stopped", false),

    /** API / turn 에러(rate-limit 아님). (빨강) */
    ERROR("error", false),
    ;

    /** i18n 키 접미사 — `projects.status.<key>` / `console.busy.<key>`. */
    val i18nKey: String get() = wire

    companion object {
        /** wire 문자열 → enum. 알 수 없거나 null 이면 null(구버전/오염 프레임 방어). */
        fun fromWire(wire: String?): ProjectState? =
            wire?.let { w -> entries.firstOrNull { it.wire == w } }

        /** busy boolean 만 아는 구버전 폴백 — true→RESPONDING, false→READY. */
        fun fromBusy(busy: Boolean): ProjectState = if (busy) RESPONDING else READY
    }
}
