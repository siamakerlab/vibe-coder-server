package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.repo.WebauthnCredentialRepository
import com.siamakerlab.vibecoder.server.repo.WebauthnCredentialRow
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * v0.48.0 — Phase 27 WebAuthn (passkey) facade around `webauthn4j`.
 *
 * Two flows:
 *
 *   1. **Registration** — `beginRegistration(username) → PublicKeyCredentialCreationOptionsLite`
 *      → browser calls `navigator.credentials.create(...)` → `finishRegistration(userId, raw)`
 *      verifies the attestation and persists the credential.
 *
 *   2. **Assertion (login 2FA)** — `beginAssertion(username) → PublicKeyCredentialRequestOptionsLite`
 *      → browser calls `navigator.credentials.get(...)` → `finishAssertion(raw)` verifies the
 *      signature, bumps signCount, and returns the matching credential's owning userId.
 *
 * The Relying Party (RP) ID + Origin are derived from configuration. For LAN deployments where
 * the user reaches the server via `http://vibe.local:17880`, set both to `vibe.local`; for
 * external HTTPS deployments set the actual public hostname.
 *
 * Challenges are kept in-memory with a 5-minute TTL — a single-user dev server doesn't need
 * persistence here. Operator restart invalidates pending ceremonies (user re-clicks Register).
 */
class WebauthnService(
    private val credentialRepo: WebauthnCredentialRepository,
    rpIdProvider: () -> String,
    rpNameProvider: () -> String,
    originProvider: () -> String,
) {

    private val rpIdProvider = rpIdProvider
    private val rpNameProvider = rpNameProvider
    private val originProvider = originProvider

    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()
    private val objectConverter = ObjectConverter()
    private val attestationObjectConverter = AttestationObjectConverter(objectConverter)
    private val random = SecureRandom()

    private val challenges = ConcurrentHashMap<String, ChallengeEntry>()

    data class RegistrationStart(
        val challengeBase64Url: String,
        val rpId: String,
        val rpName: String,
        val userIdBase64Url: String,
        val username: String,
        val excludeCredentialIds: List<String>,
    )

    data class AssertionStart(
        val challengeBase64Url: String,
        val rpId: String,
        val allowCredentialIds: List<String>,
    )

    /** Begin a registration ceremony for an already-authenticated user. */
    fun beginRegistration(userId: String, username: String): RegistrationStart {
        val challenge = ByteArray(32).also { random.nextBytes(it) }
        val key = "reg:$userId"
        challenges[key] = ChallengeEntry(challenge, Instant.now().plus(CHALLENGE_TTL))
        return RegistrationStart(
            challengeBase64Url = encode(challenge),
            rpId = rpIdProvider(),
            rpName = rpNameProvider(),
            userIdBase64Url = encode(userId.toByteArray(Charsets.UTF_8)),
            username = username,
            excludeCredentialIds = credentialRepo.listForUser(userId).map { it.credentialId },
        )
    }

    /**
     * Finish registration. [clientDataJSONBase64Url] + [attestationObjectBase64Url] are the
     * two raw outputs from `navigator.credentials.create(...)`. Returns the persisted row.
     */
    fun finishRegistration(
        userId: String,
        clientDataJSONBase64Url: String,
        attestationObjectBase64Url: String,
        transports: List<String>?,
        name: String,
    ): WebauthnCredentialRow {
        val key = "reg:$userId"
        val entry = challenges.remove(key)
            ?: throw IllegalStateException("no pending registration challenge")
        if (entry.expiresAt.isBefore(Instant.now())) {
            throw IllegalStateException("registration challenge expired — restart the flow")
        }

        val clientDataJSON = decode(clientDataJSONBase64Url)
        val attestationObject = decode(attestationObjectBase64Url)

        val origin = Origin.create(originProvider())
        val rpId = rpIdProvider()
        val challenge: Challenge = DefaultChallenge(entry.challenge)
        val serverProperty = ServerProperty(origin, rpId, challenge, null)

        val request = RegistrationRequest(attestationObject, clientDataJSON)
        val params = RegistrationParameters(serverProperty, null, false, true)

        val data = try {
            webAuthnManager.parse(request).also { webAuthnManager.validate(it, params) }
        } catch (e: Throwable) {
            log.warn(e) { "WebAuthn registration validation failed for user=$userId" }
            throw IllegalArgumentException("registration validation failed: ${e.message}", e)
        }

        val attObj: AttestationObject = data.attestationObject
            ?: throw IllegalStateException("attestation object missing after validation")
        val acd = attObj.authenticatorData.attestedCredentialData
            ?: throw IllegalStateException("attested credential data missing")
        val credentialId = encode(acd.credentialId)
        // Persist the FULL attestation object — assertion needs to rebuild a CredentialRecord
        // and the 4-arg CredentialRecordImpl(AttestationObject, ...) constructor wants it.
        val attestationObjectBytes = attestationObjectConverter.convertToBytes(attObj)
        val signCount = attObj.authenticatorData.signCount

        return credentialRepo.insert(
            userId = userId,
            credentialId = credentialId,
            attestationObject = encode(attestationObjectBytes),
            signCount = signCount,
            transports = transports?.takeIf { it.isNotEmpty() }?.joinToString(","),
            attestationType = attObj.format,
            name = name.ifBlank { "passkey" }.take(64),
        )
    }

    /**
     * Begin an assertion ceremony. If [usernameHint] resolves to a user that has registered
     * credentials, only those credential IDs are advertised in `allowCredentials`. Otherwise
     * `allowCredentials` is empty (browser falls back to a discoverable credential prompt).
     */
    fun beginAssertion(usernameHint: String?, userId: String?): AssertionStart {
        val challenge = ByteArray(32).also { random.nextBytes(it) }
        val cred = userId?.let { credentialRepo.listForUser(it) } ?: emptyList()
        val key = "auth:${usernameHint ?: ""}:${userId ?: ""}:${System.nanoTime()}"
        challenges[key] = ChallengeEntry(challenge, Instant.now().plus(CHALLENGE_TTL))
        // 가장 최근의 auth challenge 만 유효하므로 username 키 기준으로 정리 (단순화).
        // 실제로는 ceremony 종료 시 finishAssertion 이 정확한 challenge 를 찾아 검증해야 함.
        // 본 구현은 finishAssertion 에서 모든 auth: 항목을 순회하며 매칭.
        return AssertionStart(
            challengeBase64Url = encode(challenge),
            rpId = rpIdProvider(),
            allowCredentialIds = cred.map { it.credentialId },
        )
    }

    data class AssertionResult(val userId: String, val credentialId: String)

    /**
     * Finish assertion. Looks up the credential, verifies the signature, bumps signCount.
     * Returns the owning userId on success.
     */
    fun finishAssertion(
        credentialIdBase64Url: String,
        authenticatorDataBase64Url: String,
        clientDataJSONBase64Url: String,
        signatureBase64Url: String,
        userHandleBase64Url: String?,
    ): AssertionResult {
        val row = credentialRepo.findByCredentialId(credentialIdBase64Url)
            ?: throw IllegalArgumentException("credential not registered")

        // 어떤 auth challenge 가 이 ceremony 의 challenge 인지 찾기 위해, clientDataJSON 의
        // challenge 필드를 파싱한 뒤 메모리에 있는 ChallengeEntry 중 일치하는 항목을 찾는다.
        val clientDataJSON = decode(clientDataJSONBase64Url)
        val expectedChallenge = parseChallengeFromClientData(clientDataJSON)
            ?: throw IllegalArgumentException("client data missing challenge")
        val entry = popAssertionChallengeMatching(expectedChallenge)
            ?: throw IllegalStateException("assertion challenge not found / expired")

        val origin = Origin.create(originProvider())
        val rpId = rpIdProvider()
        val serverProperty = ServerProperty(origin, rpId, DefaultChallenge(entry.challenge), null)

        // Rebuild the AttestationObject from the persisted CBOR bytes and feed it to the
        // 4-arg CredentialRecordImpl constructor (which derives signCount + AttestedCredentialData
        // from the authenticatorData inside).
        val storedAttObj = attestationObjectConverter.convert(decode(row.attestationObject))
            ?: throw IllegalStateException("stored attestation object failed to parse")
        val credentialRecord = com.webauthn4j.credential.CredentialRecordImpl(
            storedAttObj, null, null, null,
        ).also { rec ->
            // webauthn4j keeps a live signCount on the record — seed it with the persisted value.
            rec.counter = row.signCount
        }

        val request = AuthenticationRequest(
            decode(credentialIdBase64Url),
            userHandleBase64Url?.let { decode(it) },
            decode(authenticatorDataBase64Url),
            clientDataJSON,
            null,
            decode(signatureBase64Url),
        )
        val params = AuthenticationParameters(serverProperty, credentialRecord, null, false, true)

        val data = try {
            webAuthnManager.parse(request).also { webAuthnManager.validate(it, params) }
        } catch (e: Throwable) {
            log.warn(e) { "WebAuthn assertion validation failed for credentialId=${credentialIdBase64Url.take(20)}…" }
            throw IllegalArgumentException("assertion validation failed: ${e.message}", e)
        }

        val newSignCount = data.authenticatorData?.signCount ?: row.signCount
        credentialRepo.touchAfterAssertion(row.id, newSignCount)
        return AssertionResult(userId = row.userId, credentialId = row.credentialId)
    }

    fun listCredentials(userId: String): List<WebauthnCredentialRow> =
        credentialRepo.listForUser(userId)

    fun deleteCredential(userId: String, credentialRowId: String): Boolean {
        val row = credentialRepo.findById(credentialRowId) ?: return false
        if (row.userId != userId) return false
        return credentialRepo.deleteById(credentialRowId)
    }

    fun hasCredentials(userId: String): Boolean = credentialRepo.countForUser(userId) > 0

    // region challenge helpers

    private data class ChallengeEntry(val challenge: ByteArray, val expiresAt: Instant)

    private fun popAssertionChallengeMatching(expectedChallenge: ByteArray): ChallengeEntry? {
        val now = Instant.now()
        // 만료된 항목 정리
        challenges.entries.removeIf { (_, v) -> v.expiresAt.isBefore(now) }
        val match = challenges.entries.firstOrNull { (k, v) ->
            k.startsWith("auth:") && v.challenge.contentEquals(expectedChallenge)
        } ?: return null
        challenges.remove(match.key)
        return match.value
    }

    /**
     * Pull the `challenge` field out of clientDataJSON (a UTF-8 JSON blob whose `challenge`
     * is base64url-encoded). We avoid pulling in a full JSON parser for this; the structure
     * is fixed and small.
     */
    private fun parseChallengeFromClientData(clientDataJSON: ByteArray): ByteArray? {
        val text = String(clientDataJSON, Charsets.UTF_8)
        val match = Regex("\"challenge\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").find(text) ?: return null
        return runCatching { decode(match.groupValues[1]) }.getOrNull()
    }

    // endregion

    companion object {
        private val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)

        private val B64_URL = Base64.getUrlEncoder().withoutPadding()
        private val B64_URL_DEC = Base64.getUrlDecoder()

        fun encode(b: ByteArray): String = B64_URL.encodeToString(b)
        fun decode(s: String): ByteArray = B64_URL_DEC.decode(s.trim())
    }
}
