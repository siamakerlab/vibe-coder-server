package com.siamakerlab.vibecoder.server.i18n

/**
 * v0.77.0 — Phase 64 SSR 다국어 지원.
 *
 * Map 기반 단순 lookup. key 는 dot notation (`nav.home`, `settings.title`,
 * `projects.empty.body` 등). [t] 가 모든 SSR 렌더링의 i18n 진입점.
 *
 * 언어 결정 fallback:
 *   1. 사용자별 `admin_users.language` (Settings → General → Language)
 *   2. `i18n.defaultLanguage` (server.yml / `VIBECODER_DEFAULT_LANGUAGE` env)
 *   3. "en"
 *
 * 새 언어 추가 절차:
 *   1. 본 파일의 `BUNDLES` 에 신규 Map 추가
 *   2. [SUPPORTED] / [resolve] 검증 분기
 *   3. server.yml 의 `i18n.defaultLanguage` 주석에 추가
 *   4. Settings dropdown 의 옵션에 추가
 *
 * 미정의 key 는 fallback chain 끝에서 key 자체를 반환 (디버깅 용이 + 회귀 안전).
 */
object Messages {

    /** v0.77.0 — 지원 언어 코드. 다른 값은 모두 [DEFAULT] 로 fallback. */
    val SUPPORTED = setOf("en", "ko")

    const val DEFAULT = "en"

    /**
     * 사용자 선택 / 서버 default / 잘못된 코드 모두 안전하게 정규화.
     * 빈 문자열 / null / 미지원 코드 → DEFAULT.
     */
    fun resolve(userLang: String?, serverDefault: String?): String {
        userLang?.trim()?.lowercase()?.takeIf { it in SUPPORTED }?.let { return it }
        serverDefault?.trim()?.lowercase()?.takeIf { it in SUPPORTED }?.let { return it }
        return DEFAULT
    }

    /**
     * key lookup + 인자 치환 (`%s` 가 있으면 `String.format`).
     * key 가 현재 언어에 없으면 [DEFAULT] 번들로 fallback.
     * 그래도 없으면 key 자체 (개발자가 누락 발견 용이).
     */
    fun t(lang: String, key: String, vararg args: Any?): String {
        val bundle = BUNDLES[lang] ?: BUNDLES.getValue(DEFAULT)
        val raw = bundle[key] ?: BUNDLES.getValue(DEFAULT)[key] ?: return key
        return if (args.isEmpty()) raw else runCatching { raw.format(*args) }.getOrDefault(raw)
    }

    /** 같은 키의 두 언어 값을 비교/디버그 — 운영 진단용. */
    fun debugBoth(key: String): String = "[en=${BUNDLES.getValue("en")[key]} ko=${BUNDLES.getValue("ko")[key]}]"

    /** 모든 번들 — 양쪽 언어가 같은 key set 유지. */
    private val BUNDLES: Map<String, Map<String, String>> = mapOf(
        "en" to MessagesEn.MAP,
        "ko" to MessagesKo.MAP,
    )
}
