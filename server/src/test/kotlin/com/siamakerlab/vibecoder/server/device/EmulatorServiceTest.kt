package com.siamakerlab.vibecoder.server.device

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * v1.73.0 — EmulatorService 비-실행 상태 불변식. 실제 에뮬레이터 부팅은 KVM/런타임 의존이라
 * 단위테스트 범위 밖 — 여기서는 SDK/프로세스가 없을 때의 안전한 기본 상태만 확증한다.
 *
 * v1.96.0 — isRunning()/status() 가 `adb devices`(외부 에뮬레이터 인식)를 참조하므로,
 * 테스트 머신에 실제 adb + emulator-5554 가 떠 있으면 "정지 상태" 불변식이 환경에 오염된다.
 * 존재하지 않는 adb 경로를 주입해 devices() 를 항상 빈 목록으로 만들어 결정성을 보장한다.
 */
class EmulatorServiceTest {

    private fun svc(): EmulatorService = EmulatorService(AdbService(adbBinOverride = "/nonexistent/adb"))

    @Test
    fun `serial is the first console-port emulator`() {
        svc().serial shouldBe "emulator-5554"
    }

    @Test
    fun `avd name and system image are configurable`() {
        val s = EmulatorService(AdbService(), avdName = "my_avd", systemImage = "system-images;android-34;google_apis;x86_64")
        s.avdName shouldBe "my_avd"
        s.systemImage shouldBe "system-images;android-34;google_apis;x86_64"
    }

    @Test
    fun `not running before start`() {
        val s = svc()
        s.isRunning().shouldBeFalse()
        s.booted().shouldBeFalse()
    }

    @Test
    fun `status reflects stopped state with stable serial`() {
        kotlinx.coroutines.runBlocking {
            val st = svc().status()
            st.running.shouldBeFalse()
            st.booted.shouldBeFalse()
            st.serial shouldBe "emulator-5554"
            st.startedAtIso shouldBe null
        }
    }

    @Test
    fun `pool exposes five stable emulator slots`() {
        kotlinx.coroutines.runBlocking {
            val pool = svc().poolStatus()
            pool.max shouldBe 5
            pool.slots.map { it.serial } shouldBe listOf(
                "emulator-5554",
                "emulator-5556",
                "emulator-5558",
                "emulator-5560",
                "emulator-5562",
            )
            pool.slots.map { it.kind }.toSet() shouldBe setOf("phone", "tablet", "foldable")
        }
    }
}
