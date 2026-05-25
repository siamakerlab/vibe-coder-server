package com.siamakerlab.vibecoder.server.i18n

/**
 * v0.77.0 — Phase 64 i18n. Korean bundle.
 *
 * [MessagesEn] 의 모든 key 가 본 파일에도 있어야 함 (linter 없으므로 수동 동기).
 * 누락 시 [Messages.t] 가 영문 fallback → 운영 중 즉시 발견.
 */
internal object MessagesKo {
    val MAP: Map<String, String> = mapOf(
        // ─────────────────────────────────────────────── common
        "common.save" to "저장",
        "common.cancel" to "취소",
        "common.delete" to "삭제",
        "common.back" to "뒤로",
        "common.edit" to "편집",
        "common.create" to "생성",
        "common.update" to "수정",
        "common.confirm" to "확인",
        "common.close" to "닫기",
        "common.next" to "다음",
        "common.previous" to "이전",
        "common.search" to "검색",
        "common.refresh" to "새로고침",
        "common.download" to "다운로드",
        "common.upload" to "업로드",
        "common.copy" to "복사",
        "common.copied" to "복사됨",
        "common.loading" to "로드 중…",
        "common.empty" to "비어 있음",
        "common.error" to "오류",
        "common.success" to "성공",
        "common.warning" to "경고",
        "common.info" to "정보",
        "common.yes" to "예",
        "common.no" to "아니오",
        "common.ok" to "확인",
        "common.disabled" to "비활성",
        "common.enabled" to "활성",
        "common.required" to "필수",
        "common.optional" to "선택",
        "common.unknown" to "알 수 없음",
        "common.never" to "기록 없음",
        "common.now" to "지금",
        "common.signOut" to "로그아웃",
        "common.signIn" to "로그인",
        "common.username" to "사용자명",
        "common.password" to "비밀번호",
        "common.email" to "이메일",
        "common.name" to "이름",
        "common.id" to "ID",
        "common.status" to "상태",
        "common.actions" to "동작",
        "common.type" to "유형",
        "common.date" to "날짜",
        "common.time" to "시각",
        "common.size" to "크기",
        "common.count" to "수",
        "common.role" to "역할",
        "common.you" to "본인",

        // ─────────────────────────────────────────────── nav (top-level)
        "nav.home" to "홈",
        "nav.projects" to "프로젝트",
        "nav.tools" to "도구",
        "nav.builds" to "빌드",
        "nav.devices" to "디바이스",
        "nav.settings" to "설정",
        "nav.logout" to "로그아웃",

        // ─────────────────────────────────────────────── settings tabs
        "settings.tab.general" to "일반",
        "settings.tab.account" to "계정",
        "settings.tab.security" to "보안",
        "settings.tab.network" to "네트워크",
        "settings.tab.notifications" to "알림",
        "settings.tab.backup" to "백업",
        "settings.tab.users" to "사용자",
        "settings.tab.audit" to "감사 로그",
        "settings.tab.buildEnv" to "빌드 환경",
        "settings.tab.prompts" to "프롬프트 & 에이전트",
        "settings.tab.monitoring" to "모니터링",

        // ─────────────────────────────────────────────── settings page
        "settings.title" to "설정",
        "settings.general.language.title" to "언어",
        "settings.general.language.body" to "SSR 세션의 표시 언어. 미설정 시 서버 기본값 적용.",
        "settings.general.language.option.system" to "서버 기본값 사용 (%s)",
        "settings.general.language.option.en" to "English (영문)",
        "settings.general.language.option.ko" to "한국어",
        "settings.general.language.save" to "언어 저장",
        "settings.general.language.saved" to "언어가 저장되었습니다 — 페이지를 새로고침해 주세요.",

        // ─────────────────────────────────────────────── home / dashboard
        "home.greeting" to "%s 에 오신 것을 환영합니다",
        "home.metric.projects" to "프로젝트",
        "home.metric.runningTasks" to "실행 중",
        "home.metric.diskFree" to "남은 디스크",
        "home.quickActions" to "빠른 동작",

        // ─────────────────────────────────────────────── projects
        "projects.title" to "프로젝트",
        "projects.register" to "프로젝트 등록",
        "projects.empty.title" to "등록된 프로젝트 없음",
        "projects.empty.body" to "첫 Android 프로젝트를 등록하여 시작하세요.",
        "projects.delete.confirm" to "프로젝트 %s 를 삭제할까요?",
        "projects.lastBuild" to "마지막 빌드",

        // ─────────────────────────────────────────────── env setup
        "env.title" to "환경 설정",
        "env.installAll" to "모두 설치",
        "env.installOne" to "%s 설치",
        "env.refresh" to "새로고침",
        "env.status.installed" to "설치됨",
        "env.status.missing" to "없음",
        "env.status.installing" to "설치 중…",

        // ─────────────────────────────────────────────── claude
        "claude.title" to "Claude 인증",
        "claude.option.oauth" to "OAuth (권장)",
        "claude.option.file" to "인증 파일",
        "claude.option.apiKey" to "API 키",

        // ─────────────────────────────────────────────── mcp
        "mcp.title" to "MCP 카탈로그",
        "mcp.install" to "선택 항목 설치",

        // ─────────────────────────────────────────────── git integrations
        "gitint.title" to "Git 통합",
        "gitint.token.register" to "토큰 등록",
        "gitint.ssh.keygen" to "SSH 키 생성",

        // ─────────────────────────────────────────────── error / form
        "error.required" to "%s 은(는) 필수입니다",
        "error.invalid" to "%s 값이 잘못되었습니다",
        "error.notFound" to "%s 을(를) 찾을 수 없습니다",
        "error.forbidden" to "권한이 없습니다",
        "error.unauthorized" to "인증이 필요합니다",
        "error.serverError" to "서버 오류",
        "error.csrf" to "CSRF 검증 실패",
    )
}
