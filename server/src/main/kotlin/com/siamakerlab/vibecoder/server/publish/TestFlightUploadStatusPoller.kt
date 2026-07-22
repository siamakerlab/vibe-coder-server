package com.siamakerlab.vibecoder.server.publish

import com.siamakerlab.vibecoder.server.ios.AppStoreConnectBuildLookupResult
import com.siamakerlab.vibecoder.server.ios.AppStoreConnectDiagnosticService
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadJobRepository
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadStatus
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadJobDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

private val testFlightPollLog = KotlinLogging.logger {}

class TestFlightUploadStatusPoller(
    private val uploadJobs: TestFlightUploadJobRepository,
    private val appStoreConnect: AppStoreConnectDiagnosticService,
    private val interval: Duration = Duration.ofMinutes(5),
    private val batchSize: Int = 20,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            testFlightPollLog.info { "TestFlight upload status poller started (interval=${interval.toMinutes()}m)" }
            while (isActive) {
                runCatching { tick() }
                    .onFailure { testFlightPollLog.debug(it) { "TestFlight upload status poll failed: ${it.message}" } }
                delay(interval.toMillis().coerceAtLeast(60_000L))
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    fun tick(): Int {
        var updated = 0
        for (job in uploadJobs.listForStatusPolling(limit = batchSize)) {
            if (refresh(job) != null) updated += 1
        }
        return updated
    }

    fun refresh(job: TestFlightUploadJobDto): TestFlightUploadJobDto? {
        if (job.status != TestFlightUploadStatus.UPLOADING.wire && job.status != TestFlightUploadStatus.PROCESSING.wire) {
            return null
        }
        if (job.buildNumber.isNullOrBlank()) return null
        if (job.appId.isNullOrBlank() && job.bundleId.isNullOrBlank()) return null

        val lookup = appStoreConnect.lookupBuild(
            appId = job.appId,
            bundleId = job.bundleId,
            buildNumber = job.buildNumber,
        )
        if (!lookup.authenticated || lookup.buildId.isNullOrBlank()) {
            testFlightPollLog.debug {
                "TestFlight ASC lookup did not return build: job=${job.id} status=${lookup.statusCode} warnings=${lookup.warnings}"
            }
            return null
        }
        val next = mapProcessingState(lookup.processingState) ?: return null
        if (next.wire == job.status && next != TestFlightUploadStatus.FAILED) return null
        return uploadJobs.mark(
            id = job.id,
            status = next,
            message = buildMessage(lookup),
            errorCode = if (next == TestFlightUploadStatus.FAILED) "asc_processing_failed" else null,
        )
    }

    companion object {
        fun mapProcessingState(processingState: String?): TestFlightUploadStatus? =
            when (processingState?.trim()?.uppercase()) {
                "PROCESSING" -> TestFlightUploadStatus.PROCESSING
                "VALID" -> TestFlightUploadStatus.ACCEPTED
                "FAILED", "INVALID" -> TestFlightUploadStatus.FAILED
                else -> null
            }

        private fun buildMessage(lookup: AppStoreConnectBuildLookupResult): String {
            val state = lookup.processingState?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"
            val version = lookup.version?.trim()?.takeIf { it.isNotBlank() }?.let { " version=$it" }.orEmpty()
            val buildId = lookup.buildId?.trim()?.takeIf { it.isNotBlank() }?.let { " buildId=$it" }.orEmpty()
            return "App Store Connect processingState=$state$version$buildId"
        }
    }
}
