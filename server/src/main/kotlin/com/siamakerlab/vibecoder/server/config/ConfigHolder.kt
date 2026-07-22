package com.siamakerlab.vibecoder.server.config

import java.util.concurrent.atomic.AtomicReference

/**
 * v1.90.0 — 런타임 [ServerConfig] holder.
 *
 * 기존엔 startup 시 로드한 `ServerConfig` 를 immutable snapshot 으로 각 컴포넌트에 값으로
 * 넘겨, `/settings` 저장이 파일엔 기록돼도 메모리/폼엔 반영되지 않아 "저장이 안 먹는 것처럼"
 * 보였다. 이 holder 는 **현재 적용 중인 설정의 단일 출처(SSOT)** 로, 저장 직후 [update] 로
 * 갱신된다:
 *   - `/settings` GET 폼은 [current] 를 그려 저장값을 즉시 보여준다.
 *   - 매 호출마다 `current` 를 읽는 컴포넌트(provider lambda 방식)는 즉시 새 값을 쓴다.
     *   - 그렇지 않은(startup 에 값을 박은) 컴포넌트는 여전히 재시작이 필요하므로, 그런 값은
     *     UI 에서 "재시작 후 적용" 으로 안내한다.
 */
object ConfigHolder {
    private val ref = AtomicReference<ServerConfig>()

    /** 현재 적용 중인 설정. [init] 전 접근은 IllegalStateException. */
    val current: ServerConfig
        get() = ref.get() ?: error("ConfigHolder not initialized")

    /** startup 1회 초기화. */
    fun init(config: ServerConfig) = ref.set(config)

    /** 저장 직후 갱신. */
    fun update(config: ServerConfig) = ref.set(config)
}
