package com.siamakerlab.vibecoder.server.metrics

import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.74.0 — 홈 대시보드 "서버 상태" 카드 데이터. CPU/RAM/프로세스(vibe-coder 컨테이너) 점유.
 * 저민감(리소스 사용률) → `/api/server/quota` 와 동일하게 무인증. /proc·cgroup 읽기는
 * Dispatchers.IO 로 격리(코루틴 워커 비블로킹).
 */
fun Routing.systemStatsRoutes(stats: SystemStatsService) {
    get(ApiPath.SERVER_STATS) {
        val s = withContext(Dispatchers.IO) { stats.snapshot() }
        call.respondText(
            """{"cpuPercent":${s.cpuPercent},"processCpuPercent":${s.processCpuPercent},""" +
                """"ramUsedMb":${s.ramUsedMb},"ramTotalMb":${s.ramTotalMb},"ramPercent":${s.ramPercent},""" +
                """"processRssMb":${s.processRssMb},"heapUsedMb":${s.heapUsedMb},"heapMaxMb":${s.heapMaxMb},""" +
                """"loadAvg":${s.loadAvg},"cores":${s.cores},"uptimeSec":${s.uptimeSec}}""",
            ContentType.Application.Json,
        )
    }
}
