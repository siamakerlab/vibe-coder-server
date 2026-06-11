package com.siamakerlab.vibecoder.server.projects

/**
 * v1.128.7 — Android `versionName` 정적 해석기.
 *
 * ## 배경
 * `ProjectService.appVersionName` 은 `versionName = "1.2.3"` 리터럴만 읽었다. 운영 프로젝트
 * 다수가 versionName 을 **간접 참조**로 정의해 버전이 미표시되거나 raw 변수명이 깨져 보였다:
 *  - 변수 참조:      `versionName = pocketmindVersionName` → `val pocketmindVersionName = "0.17.17"`
 *  - 보간:           `versionName = "v$versionMajor.$versionMinor.$versionPatch"` (+ `val versionMajor = 1`)
 *  - version.properties: `versionName = "${versionProps["VERSION_MAJOR"] ?: "1"}.…"` (+ version.properties)
 *  - 다단계:         `= autoVersionName` → `"$vMajor.…"` → `versionProps["VERSION_MAJOR"].toString().toInt()`
 *
 * ## 한계
 * Gradle 을 실행하지 않는 best-effort 정적 파싱이다. 위 패턴(리터럴/`val` 참조/문자열 보간/
 * `versionProps["KEY"]`)을 커버하고, 끝까지 못 풀어 `$` 가 남으면 null 을 반환한다(깨진 raw 를
 * 표시하느니 미표시가 낫다 — 호출부가 placeholder 처리).
 */
internal object VersionNameResolver {

    // `versionName = <rhs>` — 소문자 versionName 만(buildConfigField 의 "APP_VERSION_NAME" 등 제외).
    private val VERSION_NAME = Regex("""\bversionName\s*=\s*(.+)""")
    private val VAL_DEF = Regex("""\bval\s+([A-Za-z_]\w*)\s*=\s*(.+)""")
    private val VERSION_PROPS = Regex("""versionProps\s*\[\s*"(\w+)"\s*]""")
    private val ELVIS_STR = Regex("""\?:\s*"([^"]*)"""")
    // greedy — trailing(`} }` 등)이 붙어도 마지막 따옴표까지 잡아 내부만 추출.
    private val STR_LIT = Regex("""^"(.*)"""")
    private val INT_HEAD = Regex("""^(\d+)""")
    private val IDENT_HEAD = Regex("""^([A-Za-z_]\w*)""")
    private val INTERP_BLOCK = Regex("""\$\{([^}]*)}""")
    private val INTERP_VAR = Regex("""\$([A-Za-z_]\w*)""")

    /**
     * build.gradle(.kts) 텍스트 + version.properties map 에서 versionName 을 해석. 실패 시 null.
     */
    fun resolve(gradleText: String, props: Map<String, String>): String? {
        // 라인별 `//` 주석 제거 — 주석에 적힌 versionName 설명을 정의로 오인하지 않도록.
        val text = gradleText.lineSequence().joinToString("\n") { it.substringBefore("//") }
        val rhs = VERSION_NAME.findAll(text)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotBlank() } ?: return null
        val resolved = eval(rhs, text, props, 0) ?: return null
        return resolved.takeIf { it.isNotBlank() && '$' !in it }?.take(32)
    }

    private fun eval(exprRaw: String, text: String, props: Map<String, String>, depth: Int): String? {
        if (depth > 8) return null
        val e = exprRaw.trim()
        // 1) 문자열 리터럴(보간 포함 가능). trailing 토큰은 greedy 매칭이 무시.
        if (e.startsWith("\"")) {
            STR_LIT.find(e)?.let { return substVars(it.groupValues[1], text, props, depth) }
            return null
        }
        // 2) versionProps["KEY"] (.toString()/.toInt()/cast/elvis 가 뒤따라도 KEY 만 본다)
        VERSION_PROPS.find(e)?.let { m ->
            props[m.groupValues[1]]?.let { return it }
            ELVIS_STR.find(e)?.let { return it.groupValues[1] }   // props 없으면 elvis 기본값
            return null
        }
        // 3) 정수 리터럴
        INT_HEAD.find(e)?.let { return it.groupValues[1] }
        // 4) bareword 식별자 → `val <ident> = <expr>` 재귀
        IDENT_HEAD.find(e)?.let { m ->
            VAL_DEF.findAll(text).firstOrNull { it.groupValues[1] == m.groupValues[1] }?.let {
                return eval(it.groupValues[2], text, props, depth + 1)
            }
        }
        return null
    }

    private fun substVars(s: String, text: String, props: Map<String, String>, depth: Int): String {
        if (depth > 8) return s
        // ${ ... } 블록 먼저, 그다음 $ident
        var r = INTERP_BLOCK.replace(s) { m -> eval(m.groupValues[1], text, props, depth + 1) ?: m.value }
        r = INTERP_VAR.replace(r) { m -> eval(m.groupValues[1], text, props, depth + 1) ?: m.value }
        return r
    }
}
