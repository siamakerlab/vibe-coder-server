package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadJobDto
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class TestFlightUploadJobRepository(private val clock: Clock) {
    fun createQueued(
        projectId: String,
        buildId: String?,
        artifactId: String?,
        ipaPath: String,
        bundleId: String?,
        appId: String?,
        appName: String?,
        buildNumber: String?,
        distributionGroups: String?,
        releaseNotes: String?,
        message: String? = null,
    ): TestFlightUploadJobDto = transaction {
        val now = clock.nowIso()
        val id = "tfup-" + UUID.randomUUID().toString().replace("-", "").take(16)
        TestFlightUploadJobs.insert {
            it[TestFlightUploadJobs.id] = id
            it[TestFlightUploadJobs.projectId] = projectId
            it[TestFlightUploadJobs.buildId] = buildId
            it[TestFlightUploadJobs.artifactId] = artifactId
            it[TestFlightUploadJobs.ipaPath] = ipaPath
            it[TestFlightUploadJobs.bundleId] = bundleId
            it[TestFlightUploadJobs.appId] = appId
            it[TestFlightUploadJobs.appName] = appName
            it[TestFlightUploadJobs.buildNumber] = buildNumber
            it[TestFlightUploadJobs.status] = TestFlightUploadStatus.QUEUED.wire
            it[TestFlightUploadJobs.distributionGroups] = distributionGroups
            it[TestFlightUploadJobs.releaseNotes] = releaseNotes
            it[TestFlightUploadJobs.message] = message
            it[TestFlightUploadJobs.createdAt] = now
            it[TestFlightUploadJobs.updatedAt] = now
        }
        get(id) ?: error("created TestFlight upload job missing: $id")
    }

    fun mark(id: String, status: TestFlightUploadStatus, message: String? = null, errorCode: String? = null): TestFlightUploadJobDto? =
        transaction {
            val now = clock.nowIso()
            val normalizedError = errorCode
                ?: message?.takeIf { status == TestFlightUploadStatus.FAILED }
                    ?.let { TestFlightFailureClassifier.classify(it) }
            TestFlightUploadJobs.update({ TestFlightUploadJobs.id eq id }) {
                it[TestFlightUploadJobs.status] = status.wire
                it[TestFlightUploadJobs.updatedAt] = now
                if (message != null) it[TestFlightUploadJobs.message] = message
                if (normalizedError != null) it[TestFlightUploadJobs.errorCode] = normalizedError
                if (status.terminal) it[TestFlightUploadJobs.finishedAt] = now
            }
            get(id)
        }

    fun get(id: String): TestFlightUploadJobDto? = transaction {
        TestFlightUploadJobs.selectAll().where { TestFlightUploadJobs.id eq id }
            .map { it.toDto() }
            .singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 10): List<TestFlightUploadJobDto> = transaction {
        TestFlightUploadJobs.selectAll().where { TestFlightUploadJobs.projectId eq projectId }
            .orderBy(TestFlightUploadJobs.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toDto() }
    }

    fun listForStatusPolling(limit: Int = 20): List<TestFlightUploadJobDto> = transaction {
        TestFlightUploadJobs.selectAll()
            .where {
                TestFlightUploadJobs.status inList listOf(
                    TestFlightUploadStatus.UPLOADING.wire,
                    TestFlightUploadStatus.PROCESSING.wire,
                )
            }
            .orderBy(TestFlightUploadJobs.updatedAt to SortOrder.ASC)
            .limit(limit)
            .map { it.toDto() }
    }

    fun deleteForProject(projectId: String): Int = transaction {
        TestFlightUploadJobs.deleteWhere { TestFlightUploadJobs.projectId eq projectId }
    }

    fun renameProject(oldId: String, newId: String) = transaction {
        TestFlightUploadJobs.update({ TestFlightUploadJobs.projectId eq oldId }) {
            it[TestFlightUploadJobs.projectId] = newId
        }
    }

    private fun ResultRow.toDto() = TestFlightUploadJobDto(
        id = this[TestFlightUploadJobs.id],
        projectId = this[TestFlightUploadJobs.projectId],
        buildId = this[TestFlightUploadJobs.buildId],
        artifactId = this[TestFlightUploadJobs.artifactId],
        ipaPath = this[TestFlightUploadJobs.ipaPath],
        bundleId = this[TestFlightUploadJobs.bundleId],
        appId = this[TestFlightUploadJobs.appId],
        appName = this[TestFlightUploadJobs.appName],
        buildNumber = this[TestFlightUploadJobs.buildNumber],
        status = this[TestFlightUploadJobs.status],
        distributionGroups = this[TestFlightUploadJobs.distributionGroups],
        releaseNotes = this[TestFlightUploadJobs.releaseNotes],
        message = this[TestFlightUploadJobs.message],
        errorCode = this[TestFlightUploadJobs.errorCode],
        createdAt = this[TestFlightUploadJobs.createdAt],
        updatedAt = this[TestFlightUploadJobs.updatedAt],
        finishedAt = this[TestFlightUploadJobs.finishedAt],
    )
}

enum class TestFlightUploadStatus(val wire: String, val terminal: Boolean = false) {
    QUEUED("queued"),
    UPLOADING("uploading"),
    PROCESSING("processing"),
    ACCEPTED("accepted", terminal = true),
    FAILED("failed", terminal = true),
    ;

    companion object {
        fun fromWire(raw: String): TestFlightUploadStatus? =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
    }
}

object TestFlightFailureClassifier {
    fun classify(message: String): String? {
        val m = message.lowercase()
        return when {
            provisioning.any { it in m } -> "invalid_provisioning"
            duplicateBuild.any { it in m } -> "duplicate_build_number"
            compliance.any { it in m } -> "missing_compliance"
            metadata.any { it in m } -> "invalid_icon_screenshot_metadata"
            auth.any { it in m } -> "authentication_failed"
            ipa.any { it in m } -> "invalid_ipa"
            else -> null
        }
    }

    private val provisioning = listOf(
        "invalid provisioning",
        "provisioning profile",
        "profile doesn't include",
        "profile does not include",
        "no matching provisioning profiles",
        "doesn't match the entitlements",
        "does not match the entitlements",
        "invalid code signing",
        "code signing",
    )
    private val duplicateBuild = listOf(
        "duplicate build",
        "build number already",
        "bundle version",
        "cfbundleversion",
        "has already been used",
        "already exists for this train",
        "redundant binary upload",
    )
    private val compliance = listOf(
        "export compliance",
        "missing compliance",
        "encryption compliance",
        "uses non-exempt encryption",
        "compliance is required",
        "beta app review information",
        "app encryption documentation",
    )
    private val metadata = listOf(
        "invalid icon",
        "app icon",
        "icon dimensions",
        "screenshot",
        "invalid screenshot",
        "metadata rejected",
        "missing required icon",
        "asset validation failed",
        "invalid image dimensions",
    )
    private val auth = listOf(
        "unauthorized",
        "authentication",
        "not authorized",
        "invalid issuer",
        "invalid token",
        "jwt",
    )
    private val ipa = listOf(
        "invalid ipa",
        "ipa is invalid",
        "invalid binary",
        "asset validation",
        "could not parse",
        "missing info.plist",
    )
}
