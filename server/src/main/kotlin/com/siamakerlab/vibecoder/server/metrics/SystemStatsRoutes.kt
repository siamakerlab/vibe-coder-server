package com.siamakerlab.vibecoder.server.metrics

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.74.0 — 홈 대시보드 "서버 상태" 카드 데이터. CPU/RAM/프로세스(vibe-coder 컨테이너) 점유.
 * v1.77.0 — 인증 게이트(quota/adb/emulator 와 일괄). 인프라 정보(RAM/CPU/코어/RSS) 미인증
 * 노출 회수. 대시보드 카드는 fetch(credentials:'same-origin') 로 vibe_session 쿠키 전송 → 통과.
 * /proc·cgroup 읽기는 Dispatchers.IO 로 격리(코루틴 워커 비블로킹).
 */
fun Routing.systemStatsRoutes(stats: SystemStatsService) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.SERVER_STATS) {
            call.requireDevice() // 인증만 강제(단일 admin)
            val s = withContext(Dispatchers.IO) { stats.snapshot() }
            call.respondText(
            """{"cpuPercent":${s.cpuPercent},"processCpuPercent":${s.processCpuPercent},"vibeCpuPercent":${s.vibeCpuPercent},""" +
                """"cpuScope":"${s.cpuScope}","vibeCpuScope":"${s.vibeCpuScope}",""" +
                """"ramUsedMb":${s.ramUsedMb},"ramTotalMb":${s.ramTotalMb},"ramPercent":${s.ramPercent},"ramScope":"${s.ramScope}",""" +
                """"vibeRamUsedMb":${s.vibeRamUsedMb},"vibeRamLimitMb":${s.vibeRamLimitMb},"vibeRamPercent":${s.vibeRamPercent},""" +
                """"vibeRamSharePercent":${s.vibeRamSharePercent},"vibeRamScope":"${s.vibeRamScope}",""" +
                """"processRssMb":${s.processRssMb},"heapUsedMb":${s.heapUsedMb},"heapMaxMb":${s.heapMaxMb},""" +
                """"loadAvg":${s.loadAvg},"cores":${s.cores},"uptimeSec":${s.uptimeSec}}""",
                ContentType.Application.Json,
            )
        }
    }
}
