package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.AdminUsers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class AdminUserRow(
    val id: String,
    val username: String,
    val passwordHash: String,
    val createdAt: String,
    val lastLoginAt: String?,
    val passwordChangedAt: String,
    /** v0.26.0 — TOTP. null = 2FA 비활성. */
    val totpSecret: String? = null,
    val totpEnabledAt: String? = null,
    /**
     * v0.37.0 — "admin" | "member". 첫 admin (setup) 은 항상 admin.
     * v0.40.0 — "viewer" 추가. read-only 작업만 허용 (콘솔 입력 / 빌드 큐 /
     * git commit 등 차단). UI 의 write button 도 숨김.
     */
    val role: String = "admin",
    /** v0.57.0 — passkey 전용 로그인 강제. password/TOTP 차단 (passkey 있는 사용자만 의미). */
    val passwordlessOnly: Boolean = false,
    /**
     * v0.77.0 — Phase 64 i18n. 사용자 별 SSR 언어. null = 서버 default 사용.
     * 허용: "en", "ko". Settings 의 language dropdown 에서 변경.
     */
    val language: String? = null,
) {
    val totpEnabled: Boolean get() = !totpSecret.isNullOrBlank()
    val isAdmin: Boolean get() = role == "admin"
    /** v0.40.0 — admin / member 만 write 가능. viewer 는 read-only. */
    val canWrite: Boolean get() = role == "admin" || role == "member"
}

/**
 * 단일 admin 사용자 저장소.
 *
 * 1인 LAN 도구 전제이므로 사실상 0~1행만 존재한다. 다중 사용자 지원은
 * docs/01-plan/admin-web.md §11에 따라 명시적 비범위.
 */
class AdminUserRepository(private val clock: Clock) {

    fun count(): Long = transaction {
        AdminUsers.selectAll().count()
    }

    fun findByUsername(username: String): AdminUserRow? = transaction {
        AdminUsers.selectAll()
            .where { AdminUsers.username eq username }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun findById(id: String): AdminUserRow? = transaction {
        AdminUsers.selectAll()
            .where { AdminUsers.id eq id }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun insert(id: String, username: String, passwordHash: String, role: String = "admin"): AdminUserRow = transaction {
        val now = clock.nowIso()
        AdminUsers.insert {
            it[AdminUsers.id] = id
            it[AdminUsers.username] = username
            it[AdminUsers.passwordHash] = passwordHash
            it[createdAt] = now
            it[passwordChangedAt] = now
            it[AdminUsers.role] = role
        }
        AdminUserRow(id, username, passwordHash, now, null, now, role = role)
    }

    fun touchLogin(id: String) = transaction {
        AdminUsers.update({ AdminUsers.id eq id }) { it[lastLoginAt] = clock.nowIso() }
    }

    fun updatePassword(id: String, newHash: String) = transaction {
        val now = clock.nowIso()
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[passwordHash] = newHash
            it[passwordChangedAt] = now
        }
    }

    /** v0.26.0 — TOTP 활성화. secret 은 이미 generateSecret() 으로 생성된 Base32. */
    fun enableTotp(id: String, base32Secret: String) = transaction {
        val now = clock.nowIso()
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[totpSecret] = base32Secret
            it[totpEnabledAt] = now
        }
    }

    /** v0.26.0 — TOTP 비활성화. */
    fun disableTotp(id: String) = transaction {
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[totpSecret] = null
            it[totpEnabledAt] = null
        }
    }

    /** v0.37.0 — role 변경. 호출자가 마지막 admin 보존 책임. */
    fun setRole(id: String, role: String): Int = transaction {
        AdminUsers.update({ AdminUsers.id eq id }) { it[AdminUsers.role] = role }
    }

    /** v0.37.0 — 전체 사용자. /users 페이지가 listing 용. */
    fun listAll(): List<AdminUserRow> = transaction {
        AdminUsers.selectAll().map { it.toRow() }
            .sortedWith(compareBy({ it.role != "admin" }, { it.username.lowercase() }))
    }

    /** v0.37.0 — admin role 의 사용자 수. 마지막 admin 강등/삭제 차단 가드. */
    fun adminCount(): Long = transaction {
        AdminUsers.selectAll().where { AdminUsers.role eq "admin" }.count()
    }

    /** v0.37.0 — 사용자 삭제. AuthService 가 device row 도 cascade 정리할 책임. */
    fun delete(id: String): Int = transaction {
        AdminUsers.deleteWhere { AdminUsers.id eq id }
    }

    private fun ResultRow.toRow() = AdminUserRow(
        id = this[AdminUsers.id],
        username = this[AdminUsers.username],
        passwordHash = this[AdminUsers.passwordHash],
        createdAt = this[AdminUsers.createdAt],
        lastLoginAt = this[AdminUsers.lastLoginAt],
        passwordChangedAt = this[AdminUsers.passwordChangedAt],
        totpSecret = this[AdminUsers.totpSecret],
        totpEnabledAt = this[AdminUsers.totpEnabledAt],
        role = this[AdminUsers.role],
        passwordlessOnly = this[AdminUsers.passwordlessOnly],
        language = this[AdminUsers.language],
    )

    /** v0.57.0 — passwordless-only toggle (passkey 등록된 사용자만 의미 있음). */
    fun setPasswordlessOnly(userId: String, enabled: Boolean): Boolean = transaction {
        AdminUsers.update({ AdminUsers.id eq userId }) {
            it[AdminUsers.passwordlessOnly] = enabled
        } > 0
    }

    /**
     * v0.77.0 — Phase 64 i18n. 사용자 별 SSR 언어 설정.
     * null 또는 빈 문자열 → 서버 default fallback. 허용: "en", "ko".
     */
    fun setLanguage(userId: String, language: String?): Boolean = transaction {
        val normalized = language?.trim()?.ifBlank { null }
        AdminUsers.update({ AdminUsers.id eq userId }) {
            it[AdminUsers.language] = normalized
        } > 0
    }
}
