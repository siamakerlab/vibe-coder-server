package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.AdminUserRow
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

data class LoginOutcome(
    val token: String,
    val device: DeviceRow,
    val user: AdminUserRow,
)

/**
 * 통합 인증 서비스.
 *
 * - login: username + password → 토큰 발급 (Device row 생성)
 * - setup: 첫 admin 생성 (admin 없을 때만 허용)
 * - changePassword: 현재 비번 검증 후 갱신
 * - brute force 방어: 같은 username 10회 실패 시 15분 잠금
 */
class AuthService(
    private val users: AdminUserRepository,
    private val devices: DeviceRepository,
    private val tokens: TokenService,
    private val hasher: PasswordHasher,
    private val clock: Clock,
) {

    /** username → (failCount, lockedUntilMillis) */
    private val failures = ConcurrentHashMap<String, FailureState>()

    private data class FailureState(val count: Int, val lockedUntilMs: Long)

    fun adminExists(): Boolean = users.count() > 0

    fun setup(username: String, password: String, deviceName: String, channel: String): LoginOutcome {
        if (adminExists()) {
            throw ApiException(409, "admin_exists", "admin이 이미 존재합니다. /api/auth/login 사용.")
        }
        UsernamePolicy.violation(username)?.let { throw ApiException(400, "bad_username", it) }
        PasswordPolicy.violation(password)?.let { throw ApiException(400, "bad_password", it) }

        val hash = hasher.hash(password)
        val user = users.insert(Ids.deviceId(), username, hash)
        log.info { "admin 계정 생성됨: ${user.username}" }

        return issueToken(user, deviceName, channel)
    }

    fun login(username: String, password: String, deviceName: String, channel: String): LoginOutcome {
        val now = System.currentTimeMillis()

        // 1) 잠금 확인
        failures[username]?.let { state ->
            if (state.lockedUntilMs > now) {
                val remainingSec = (state.lockedUntilMs - now) / 1000
                throw ApiException(
                    429, "locked",
                    "로그인 시도 횟수 초과. ${remainingSec}초 후 다시 시도해주세요."
                )
            }
        }

        // 2) 사용자 조회 — 비밀번호 검증은 사용자 존재 여부와 무관하게 같은 시간 소요
        val user = users.findByUsername(username)
        val ok = if (user != null) {
            hasher.verify(password, user.passwordHash)
        } else {
            // dummy verify로 timing-attack 방어
            hasher.verify(password, DUMMY_HASH)
            false
        }

        if (!ok || user == null) {
            recordFailure(username, now)
            log.warn { "로그인 실패: $username" }
            throw ApiException(401, "invalid_credentials", "사용자명 또는 비밀번호가 올바르지 않습니다.")
        }

        // 3) 성공 → 실패 카운터 초기화 + 토큰 발급
        failures.remove(username)
        users.touchLogin(user.id)
        return issueToken(user, deviceName, channel)
    }

    fun changePassword(userId: String, currentPassword: String, newPassword: String) {
        val user = users.findById(userId)
            ?: throw ApiException(404, "user_not_found", "사용자를 찾을 수 없습니다.")

        if (!hasher.verify(currentPassword, user.passwordHash)) {
            throw ApiException(401, "wrong_password", "현재 비밀번호가 일치하지 않습니다.")
        }
        PasswordPolicy.violation(newPassword)
            ?.let { throw ApiException(400, "bad_password", it) }
        if (currentPassword == newPassword) {
            throw ApiException(400, "same_password", "새 비밀번호는 현재와 달라야 합니다.")
        }

        users.updatePassword(user.id, hasher.hash(newPassword))
        log.info { "admin 비밀번호 변경됨: ${user.username}" }
    }

    private fun issueToken(user: AdminUserRow, deviceName: String, channel: String): LoginOutcome {
        val issued = tokens.issue()
        val device = devices.insert(
            id = Ids.deviceId(),
            name = deviceName.ifBlank { "unknown" },
            tokenHash = issued.tokenHash,
            userId = user.id,
            channel = channel,
        )
        return LoginOutcome(issued.token, device, user)
    }

    private fun recordFailure(username: String, nowMs: Long) {
        failures.compute(username) { _, prev ->
            val count = (prev?.count ?: 0) + 1
            val locked = if (count >= MAX_FAIL_BEFORE_LOCK) {
                nowMs + LOCK_DURATION_MS
            } else 0L
            FailureState(count, locked)
        }
    }

    companion object {
        private const val MAX_FAIL_BEFORE_LOCK = 10
        private const val LOCK_DURATION_MS = 15 * 60 * 1000L

        // 임의의 유효한 BCrypt 해시 (timing-attack 방어용 dummy)
        private const val DUMMY_HASH =
            "\$2a\$12\$abcdefghijklmnopqrstuuxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }
}
