package com.siamakerlab.vibecoder.server.notify

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * v0.72.0 — Phase 52 #4. Firebase Cloud Messaging HTTP v1 직접 호출.
 *
 * Firebase Admin SDK 미사용 — JDK stdlib (java.security) + Ktor HTTP client 로 minimum
 * 구현. Dependency 추가 없음.
 *
 * 인증: service account private key 로 RS256 JWT 서명 → Google OAuth token endpoint
 * exchange → access token → FCM HTTP v1 호출.
 *
 * 활성화 조건:
 *  - `FCM_PROJECT_ID` env var (예: "vibe-coder-12345")
 *  - `FCM_SERVICE_ACCOUNT_JSON_PATH` env var (Firebase Console → 서비스 계정 → 비공개 키 .json path)
 *
 * 둘 다 설정되면 활성. 미설정 시 [isEnabled] = false → 모든 send() 가 no-op.
 *
 * Token 등록 storage: 현재 stub — Firebase Console 에서 Android 앱 등록 + Android 앱이
 * `/api/notifications/fcm-token` POST 시 in-memory map 저장 (process restart 시 reset).
 * DB 영속화는 후속 cycle.
 */
class FcmSender {

    private val projectId: String? = System.getenv("FCM_PROJECT_ID")?.ifBlank { null }
    private val serviceAccountJsonPath: String? = System.getenv("FCM_SERVICE_ACCOUNT_JSON_PATH")?.ifBlank { null }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** userId → device FCM tokens. v0.72.0 stub: in-memory. */
    private val tokensByUser = ConcurrentHashMap<String, MutableSet<String>>()

    /** Cached OAuth access token + expiry epoch sec. Refresh 1분 전 부터 재발급. */
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenExp: Long = 0L

    /** Service account JSON parsed lazily on first send. */
    private val serviceAccount: ServiceAccount? by lazy {
        val path = serviceAccountJsonPath ?: return@lazy null
        runCatching {
            val obj = json.parseToJsonElement(File(path).readText()) as JsonObject
            ServiceAccount(
                clientEmail = (obj["client_email"] as JsonPrimitive).content,
                privateKeyPem = (obj["private_key"] as JsonPrimitive).content,
                tokenUri = (obj["token_uri"] as? JsonPrimitive)?.content
                    ?: "https://oauth2.googleapis.com/token",
            )
        }.onFailure { log.warn(it) { "FCM service account JSON parse failed: $path" } }
            .getOrNull()
    }

    val isEnabled: Boolean
        get() = projectId != null && serviceAccount != null

    /** Token 등록 (Android `/api/notifications/fcm-token` POST). userId null = legacy bucket. */
    fun registerToken(userId: String?, token: String) {
        val key = userId ?: NotificationService.BUCKET_LEGACY
        tokensByUser.getOrPut(key) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(token)
    }

    /**
     * Send notification to all tokens of the given users. Fire-and-forget.
     * 미설정 시 silent skip — NotificationService.emit() 의 polling path 가 항상 동작하므로
     * Android 가 알림 못 받는 경우는 없음 (FCM 은 instant 알림 부가 채널).
     */
    fun send(userIds: List<String?>, title: String, body: String, deepLink: String? = null) {
        if (!isEnabled) return
        val keys = userIds.map { it ?: NotificationService.BUCKET_LEGACY }.distinct()
        val tokens = keys.flatMap { tokensByUser[it] ?: emptySet() }.distinct()
        if (tokens.isEmpty()) return
        scope.launch {
            val accessToken = obtainAccessToken() ?: return@launch
            tokens.forEach { token -> trySend(accessToken, token, title, body, deepLink) }
        }
    }

    private fun trySend(accessToken: String, token: String, title: String, body: String, deepLink: String?) {
        val payload = buildJsonObject {
            put("message", buildJsonObject {
                put("token", token)
                put("notification", buildJsonObject {
                    put("title", title)
                    put("body", body)
                })
                if (deepLink != null) {
                    put("data", buildJsonObject {
                        put("deep_link", deepLink)
                    })
                }
            })
        }
        val url = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
        runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                    json.encodeToString(JsonObject.serializer(), payload)))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                log.info { "FCM token stale (${resp.statusCode()}), removing: ${token.take(10)}..." }
                tokensByUser.values.forEach { it.remove(token) }
            } else if (resp.statusCode() >= 400) {
                log.warn { "FCM send failed ${resp.statusCode()}: ${resp.body().take(200)}" }
            }
        }.onFailure { log.warn(it) { "FCM send exception: ${it.message}" } }
    }

    private fun obtainAccessToken(): String? {
        val nowSec = System.currentTimeMillis() / 1000
        val cur = cachedToken
        if (cur != null && nowSec < cachedTokenExp - 60) return cur

        val sa = serviceAccount ?: return null
        val jwt = makeJwt(sa, nowSec) ?: return null
        val resp = runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(sa.tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(
                    "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$jwt"))
                .build()
            client.send(req, HttpResponse.BodyHandlers.ofString()).body()
        }.onFailure { log.warn(it) { "OAuth token request failed: ${it.message}" } }
            .getOrNull() ?: return null

        val obj = runCatching { json.parseToJsonElement(resp) as JsonObject }.getOrNull() ?: return null
        val accessToken = (obj["access_token"] as? JsonPrimitive)?.content ?: run {
            log.warn { "OAuth response missing access_token: ${resp.take(200)}" }
            return null
        }
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        cachedToken = accessToken
        cachedTokenExp = nowSec + expiresIn
        return accessToken
    }

    private fun makeJwt(sa: ServiceAccount, nowSec: Long): String? {
        // RS256 JWT for service account assertion. JDK SignatureSpec.
        val header = buildJsonObject { put("alg", "RS256"); put("typ", "JWT") }
        val claim = buildJsonObject {
            put("iss", sa.clientEmail)
            put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            put("aud", sa.tokenUri)
            put("iat", JsonPrimitive(nowSec))
            put("exp", JsonPrimitive(nowSec + 3600))
        }
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val headerB64 = b64.encodeToString(json.encodeToString(JsonObject.serializer(), header).toByteArray())
        val claimB64 = b64.encodeToString(json.encodeToString(JsonObject.serializer(), claim).toByteArray())
        val signingInput = "$headerB64.$claimB64"

        // PKCS#8 PEM 파싱.
        return runCatching {
            val pemBody = sa.privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.getDecoder().decode(pemBody)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(privateKey)
            sig.update(signingInput.toByteArray())
            val sigB64 = b64.encodeToString(sig.sign())
            "$signingInput.$sigB64"
        }.onFailure { log.warn(it) { "JWT signing failed: ${it.message}" } }
            .getOrNull()
    }

    fun shutdown() {
        // JDK HttpClient 는 close 불요 (Java 21 부터 AutoCloseable 이나 stable 동작).
        // 21차 점검(minor) — send() 가 scope.launch 로 OAuth 교환 + FCM POST 를 비동기
        // 실행하므로 graceful shutdown 시 scope 를 cancel 해 in-flight launch + SupervisorJob
        // 을 정리. EmailNotifier/WebhookNotifier 와 동일(이전엔 fcm 만 누락).
        scope.cancel()
    }

    private data class ServiceAccount(
        val clientEmail: String,
        val privateKeyPem: String,
        val tokenUri: String,
    )
}
