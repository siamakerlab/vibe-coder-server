package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.68.0 — Phase 47. Polling-based notification system (Android Group C 의 FCM 대체).
 *
 * Why polling instead of FCM:
 *  - FCM 은 Firebase 프로젝트 + google-services.json + Firebase Admin SDK 서버 키가 필요.
 *  - 본 프로젝트는 단일 사용자 LAN 도구 — 외부 push gateway 의존 부적합.
 *  - Polling 은 WorkManager 의 15분 periodic 한도 + Android Doze 모드 친화 (battery OK).
 *
 * 알림 종류:
 *  - `build.success` / `build.failed` — 빌드 완료 알림.
 *  - `claude.turn_done`             — Claude 응답 turn 완료 (선택 — 기본 disabled).
 *  - `usage.threshold`              — 사용량 임계치 (예: 80% 이상).
 *  - `system`                       — 기타 server-originated notice (디스크 부족, MCP 설치 완료 등).
 *
 * Fan-out 모델: 이벤트 발생 시 모든 admin/member 토큰에 fan-out. viewer 에게도 정보용으로
 * fan-out (read-only — ack 만 가능, mutation 없음).
 *
 * Polling 흐름:
 *  1. Android WorkManager 가 15분 마다 `GET /api/notifications` 호출.
 *  2. 응답 events 중 새 것을 system notification 으로 표시.
 *  3. 사용자가 tap 시 deep-link (`projects/{id}/builds/{buildId}` 등) 로 navigate.
 *  4. 표시한 event id 들을 `POST /api/notifications/ack` 로 read 처리 → 다음 polling 에서 제외.
 *
 * 서버 측 retention: 30일 + 사용자별 최대 500개 (오래된 것 부터 prune).
 */
@Serializable
data class NotificationEventDto(
    val id: String,
    val ts: String,
    /** [NotificationKind] 의 알려진 값 또는 forward-compat unknown. */
    val kind: String,
    /** UI 표시용 한 줄 요약. 비어있으면 kind 만으로 표시. */
    val title: String,
    val body: String = "",
    /** Tap 시 deep-link 경로 (Android 의 nav 라우트 — 예: `projects/foo/builds/bar`). null = 표시만. */
    val deepLink: String? = null,
    /** 관련 project id (있다면). UI 가 같은 프로젝트 알림 묶음용. */
    val projectId: String? = null,
    /** 사용자가 ack 했는지 — list 응답에 이미 ack 된 항목은 제외되므로 클라이언트는 항상 false 받음. */
    val read: Boolean = false,
)

@Serializable
data class NotificationsResponseDto(
    val events: List<NotificationEventDto> = emptyList(),
    /** 서버에 남은 총 unread 수 (UI badge 용). */
    val unreadTotal: Int = 0,
)

@Serializable
data class NotificationAckRequestDto(
    /** Ack 처리할 event id 목록. 알 수 없는 id 는 서버가 무음 skip. */
    val ids: List<String> = emptyList(),
)

/**
 * v0.68.0 — Optional FCM stub. Firebase 프로젝트가 설정되면 활성화 (서버 env var
 * `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT_JSON_PATH` 확인). 미설정이면 endpoint
 * 는 200 OK 만 반환하고 실제 발송 없음.
 *
 * 시나리오: 사용자가 본인 Firebase 프로젝트 + Android 앱 google-services.json
 * 추가 시 즉시 push 활성. 미설정 시 polling-only 동작.
 */
@Serializable
data class FcmTokenRegisterRequestDto(
    val token: String,
    /** Android device 식별자 — DataStore session deviceId. */
    val deviceId: String,
)

/** [NotificationEventDto.kind] 의 알려진 값. */
object NotificationKind {
    const val BUILD_SUCCESS = "build.success"
    const val BUILD_FAILED = "build.failed"
    const val CLAUDE_TURN_DONE = "claude.turn_done"
    /** v1.88.0 — Claude 콘솔 turn 이 사용자 취소(중지)로 끝남. */
    const val CLAUDE_STOPPED = "claude.stopped"
    /** v1.88.0 — Claude 프로세스 비정상 종료(크래시 등) — 오류 알림. */
    const val CLAUDE_ERROR = "claude.error"
    const val USAGE_THRESHOLD = "usage.threshold"
    const val SYSTEM = "system"
}
