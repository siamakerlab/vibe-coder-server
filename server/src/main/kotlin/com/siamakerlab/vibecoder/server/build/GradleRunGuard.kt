package com.siamakerlab.vibecoder.server.build

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.117.0 — 프로젝트별 Gradle 실행 가드.
 *
 * 같은 프로젝트에서 동시에 두 개 이상의 Gradle 작업(lint / connectedAndroidTest 등)이
 * spawn 되면 Gradle 프로젝트 락 충돌(`Could not acquire lock`)이나 단일 에뮬레이터에서의
 * connectedTest 충돌이 발생한다. 이를 막기 위해 projectId 단위로 in-flight 1개만 허용한다.
 *
 * 동작: [tryAcquire] 가 false 면 호출자는 "이미 실행 중" 으로 graceful 응답하고 spawn 을
 * 건너뛴다(큐잉/블로킹하지 않음 — 버튼 더블클릭/동시 요청의 파일럿 누적 방지).
 *
 * 품질 탭의 [LintQualityService] 와 [InstrumentedTestService] 가 **같은 인스턴스**를
 * 공유해 서로 간 동시 실행도 직렬화한다.
 */
class GradleRunGuard {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 획득 성공(새로 추가됨) 시 true. 이미 실행 중이면 false. */
    fun tryAcquire(projectId: String): Boolean = inFlight.add(projectId)

    fun release(projectId: String) {
        inFlight.remove(projectId)
    }

    fun isRunning(projectId: String): Boolean = inFlight.contains(projectId)

    /** 획득→실행→해제 를 묶는 헬퍼. 획득 실패 시 [onBusy] 결과 반환. */
    inline fun <T> withLock(projectId: String, onBusy: () -> T, block: () -> T): T {
        if (!tryAcquire(projectId)) return onBusy()
        return try {
            block()
        } finally {
            release(projectId)
        }
    }
}
