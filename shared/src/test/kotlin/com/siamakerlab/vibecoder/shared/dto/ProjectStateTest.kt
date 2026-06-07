package com.siamakerlab.vibecoder.shared.dto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Test

/**
 * v1.114.0 (P4) — [ProjectState] 단일 진실(SSOT) 불변식 잠금.
 *
 * 상태값이 6개+ 파일에 흩어진 문자열 리터럴이던 것을 이 enum 으로 모았다(P1). 이 테스트는
 * 그 SSOT 계약을 회귀로부터 보호한다:
 *  - wire 문자열은 Android 클라이언트와의 wire 프로토콜이므로 **절대 변경 금지** (값 고정 검증).
 *  - busy 파생(RESPONDING/WAITING 만 busy)이 setBusy 의 (busy,state) 일관성을 보장한다.
 *  - fromWire/fromBusy 라운드트립이 깨지면 구버전/오염 프레임 폴백이 무너진다.
 */
class ProjectStateTest {

    @Test
    fun `wire 값은 고정된 계약이다 (Android wire 호환 — 변경 금지)`() {
        // 이 값들이 바뀌면 배포된 Android 클라이언트의 상태 뱃지가 깨진다. 일부러 하드코딩해 잠근다.
        ProjectState.READY.wire shouldBe "ready"
        ProjectState.RESPONDING.wire shouldBe "responding"
        ProjectState.WAITING.wire shouldBe "waiting"
        ProjectState.STOPPED.wire shouldBe "stopped"
        ProjectState.ERROR.wire shouldBe "error"
    }

    @Test
    fun `wire 문자열은 enum 전체에서 유일하다`() {
        val wires = ProjectState.entries.map { it.wire }
        wires.toSet().size shouldBe wires.size
    }

    @Test
    fun `busy 파생은 응답중_대기중만 true 다`() {
        ProjectState.entries.filter { it.busy }.map { it.wire }
            .shouldContainExactly("responding", "waiting")
        // 종료 계열은 모두 유휴(busy=false).
        ProjectState.READY.busy shouldBe false
        ProjectState.STOPPED.busy shouldBe false
        ProjectState.ERROR.busy shouldBe false
    }

    @Test
    fun `fromWire 는 모든 상태를 라운드트립한다`() {
        ProjectState.entries.forEach { st ->
            ProjectState.fromWire(st.wire) shouldBe st
        }
    }

    @Test
    fun `fromWire 는 알 수 없거나 null 이면 null 이다 (오염 프레임 방어)`() {
        ProjectState.fromWire(null) shouldBe null
        ProjectState.fromWire("") shouldBe null
        ProjectState.fromWire("bogus") shouldBe null
        ProjectState.fromWire("READY") shouldBe null // 대문자 enum name 은 wire 가 아니다
    }

    @Test
    fun `fromBusy 는 boolean 만 아는 구버전 폴백이다`() {
        ProjectState.fromBusy(true) shouldBe ProjectState.RESPONDING
        ProjectState.fromBusy(false) shouldBe ProjectState.READY
    }

    @Test
    fun `i18nKey 는 wire 와 같다 (projects_status_·console_busy_ 키 접미사)`() {
        ProjectState.entries.forEach { it.i18nKey shouldBe it.wire }
    }

    @Test
    fun `enum name 과 wire 는 다르다 (직렬화에 name 이 새지 않도록)`() {
        // kotlinx.serialization 이 enum 을 쓰면 name(대문자)이 나간다. 우리는 항상 wire 를 쓰므로
        // name != wire 임을 명시적으로 인지(누군가 enum 을 직접 직렬화하면 이 테스트가 경고).
        ProjectState.READY.name shouldNotBe ProjectState.READY.wire
    }
}
