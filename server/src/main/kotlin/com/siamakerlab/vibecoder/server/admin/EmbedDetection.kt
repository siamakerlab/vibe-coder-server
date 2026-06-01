package com.siamakerlab.vibecoder.server.admin

import io.ktor.server.application.ApplicationCall

/**
 * v1.72.0 — 현재 요청이 iframe 임베드(설정/프로젝트 탭의 inner page)로 들어온 것인지 판정.
 *
 * 설정 통합 탭([SettingsTabsTemplate]) · 프로젝트 통합 탭([ProjectTabsTemplate]) 은
 * 각 카테고리를 `<iframe src="/settings" ...>` 처럼 기존 SSR 페이지를 그대로 로드한다.
 * 그 inner page 가 사이드바 nav + 설정 탭바(크롬)까지 렌더하면 부모 탭 UI 와 겹쳐
 * "페이지 in 페이지"(전체 레이아웃 이중 노출)가 된다.
 *
 * embed 로 판정되면 [AdminTemplates.shell] 의 `embed=true` 로 전달되어 크롬을
 * **처음부터 렌더하지 않는다**(JS 로 숨기는 방식이 아니라 서버 미렌더 → race·flash 불가능).
 *
 * 판정 기준 (둘 중 하나라도 만족):
 *  1. 표준 `Sec-Fetch-Dest: iframe` — 모던 브라우저가 iframe subresource 요청에 자동 부여.
 *     사용자가 같은 URL 을 새 탭/북마크로 **직접** 열면 `document` 라서 크롬이 정상 노출.
 *  2. `?_embed=1` 쿼리 — 탭 템플릿이 iframe src 에 명시하는 폴백(Sec-Fetch-Dest 미지원/누락,
 *     프록시 strip 대비). 직접 접근 URL 엔 이 쿼리가 없어 영향 없음.
 */
fun ApplicationCall.isEmbeddedRequest(): Boolean =
    request.headers["Sec-Fetch-Dest"] == "iframe" ||
        request.queryParameters["_embed"] == "1"
