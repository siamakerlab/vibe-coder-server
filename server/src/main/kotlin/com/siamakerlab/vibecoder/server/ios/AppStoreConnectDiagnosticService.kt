package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectDiagnosticDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

class AppStoreConnectDiagnosticService(
    private val keyStore: AppStoreConnectKeyStore,
    private val client: AscHttpClient = JavaNetAscHttpClient(),
    private val clock: Clock = SystemClock(),
) {
    fun diagnose(bundleId: String? = null): IosAppStoreConnectDiagnosticDto {
        val credentials = keyStore.loadCredentials()
            ?: return IosAppStoreConnectDiagnosticDto(
                checkedAt = clock.nowIso(),
                configured = false,
                message = "App Store Connect API key is not configured.",
                warnings = listOf("app_store_connect_key_missing"),
            )
        val normalizedBundleId = bundleId?.trim()?.takeIf { it.isNotBlank() }?.take(255)
        val token = runCatching { AscJwtSigner.sign(credentials, nowEpochSeconds()) }
            .getOrElse { e ->
                return IosAppStoreConnectDiagnosticDto(
                    checkedAt = clock.nowIso(),
                    configured = true,
                    message = e.message ?: "Failed to sign App Store Connect JWT.",
                    warnings = listOf("jwt_sign_failed"),
                )
            }
        val response = runCatching {
            client.get(appsUrl(normalizedBundleId), token)
        }.getOrElse { e ->
            return IosAppStoreConnectDiagnosticDto(
                checkedAt = clock.nowIso(),
                configured = true,
                message = e.message ?: "App Store Connect request failed.",
                warnings = listOf("app_store_connect_unreachable"),
            )
        }
        val parsed = parseAppsResponse(response.body)
        val ok = response.statusCode in 200..299
        return IosAppStoreConnectDiagnosticDto(
            checkedAt = clock.nowIso(),
            configured = true,
            authenticated = ok,
            appsReachable = ok,
            appCount = parsed.count,
            bundleId = normalizedBundleId,
            matchingAppId = parsed.firstAppId,
            matchingAppName = parsed.firstAppName,
            statusCode = response.statusCode,
            errorCode = parsed.errorCode,
            message = if (ok) parsed.message else parsed.message ?: "App Store Connect returned HTTP ${response.statusCode}.",
            warnings = buildList {
                if (!ok) add("app_store_connect_http_${response.statusCode}")
                if (ok && normalizedBundleId != null && parsed.count == 0) add("bundle_id_not_found")
            },
        )
    }

    fun lookupBuild(appId: String?, bundleId: String?, buildNumber: String?): AppStoreConnectBuildLookupResult {
        val credentials = keyStore.loadCredentials()
            ?: return AppStoreConnectBuildLookupResult(
                checkedAt = clock.nowIso(),
                configured = false,
                message = "App Store Connect API key is not configured.",
                warnings = listOf("app_store_connect_key_missing"),
            )
        val effectiveAppId = appId?.trim()?.takeIf { it.isNotBlank() }
            ?: bundleId?.trim()?.takeIf { it.isNotBlank() }?.let { diagnose(it).matchingAppId }
        if (effectiveAppId == null) {
            return AppStoreConnectBuildLookupResult(
                checkedAt = clock.nowIso(),
                configured = true,
                message = "App Store Connect app id is not available for build lookup.",
                warnings = listOf("app_store_connect_app_missing"),
            )
        }
        val token = runCatching { AscJwtSigner.sign(credentials, nowEpochSeconds()) }
            .getOrElse { e ->
                return AppStoreConnectBuildLookupResult(
                    checkedAt = clock.nowIso(),
                    configured = true,
                    appId = effectiveAppId,
                    message = e.message ?: "Failed to sign App Store Connect JWT.",
                    warnings = listOf("jwt_sign_failed"),
                )
            }
        val response = runCatching {
            client.get(buildsUrl(effectiveAppId, buildNumber?.trim()?.takeIf { it.isNotBlank() }), token)
        }.getOrElse { e ->
            return AppStoreConnectBuildLookupResult(
                checkedAt = clock.nowIso(),
                configured = true,
                appId = effectiveAppId,
                message = e.message ?: "App Store Connect build lookup failed.",
                warnings = listOf("app_store_connect_unreachable"),
            )
        }
        val parsed = parseBuildsResponse(response.body)
        val ok = response.statusCode in 200..299
        return AppStoreConnectBuildLookupResult(
            checkedAt = clock.nowIso(),
            configured = true,
            authenticated = ok,
            appId = effectiveAppId,
            buildId = parsed.firstBuildId,
            version = parsed.version,
            processingState = parsed.processingState,
            uploadedDate = parsed.uploadedDate,
            expired = parsed.expired,
            statusCode = response.statusCode,
            errorCode = parsed.errorCode,
            message = if (ok) parsed.message else parsed.message ?: "App Store Connect returned HTTP ${response.statusCode}.",
            warnings = buildList {
                if (!ok) add("app_store_connect_http_${response.statusCode}")
                if (ok && parsed.firstBuildId == null) add("app_store_connect_build_not_found")
            },
        )
    }

    private fun appsUrl(bundleId: String?): String {
        val base = "$ASC_API_BASE/v1/apps?limit=1"
        return if (bundleId == null) {
            base
        } else {
            "$base&filter%5BbundleId%5D=${URLEncoder.encode(bundleId, Charsets.UTF_8)}"
        }
    }

    private fun buildsUrl(appId: String, buildNumber: String?): String {
        val params = mutableListOf(
            "limit=1",
            "sort=-uploadedDate",
            "fields%5Bbuilds%5D=version,uploadedDate,expirationDate,expired,processingState",
            "filter%5Bapp%5D=${URLEncoder.encode(appId, Charsets.UTF_8)}",
        )
        if (buildNumber != null) {
            params += "filter%5Bversion%5D=${URLEncoder.encode(buildNumber, Charsets.UTF_8)}"
        }
        return "$ASC_API_BASE/v1/builds?${params.joinToString("&")}"
    }

    private fun nowEpochSeconds(): Long = Instant.parse(clock.nowIso()).epochSecond

    private fun parseAppsResponse(body: String): ParsedAppsResponse =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray.orEmpty()
            val first = data.firstOrNull()?.jsonObject
            val attrs = first?.get("attributes")?.jsonObject
            val errors = root["errors"]?.jsonArray.orEmpty()
            val firstError = errors.firstOrNull()?.jsonObject
            ParsedAppsResponse(
                count = data.size,
                firstAppId = first?.get("id")?.jsonPrimitive?.contentOrNull,
                firstAppName = attrs?.get("name")?.jsonPrimitive?.contentOrNull,
                errorCode = firstError?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: firstError?.get("status")?.jsonPrimitive?.contentOrNull,
                message = firstError?.get("detail")?.jsonPrimitive?.contentOrNull
                    ?: firstError?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: root["meta"]?.jsonObject?.get("paging")?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
                        ?.let { "App Store Connect apps lookup returned $it total app(s)." },
            )
        }.getOrElse { ParsedAppsResponse(message = body.take(500)) }

    private fun parseBuildsResponse(body: String): ParsedBuildsResponse =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray.orEmpty()
            val first = data.firstOrNull()?.jsonObject
            val attrs = first?.get("attributes")?.jsonObject
            val errors = root["errors"]?.jsonArray.orEmpty()
            val firstError = errors.firstOrNull()?.jsonObject
            ParsedBuildsResponse(
                firstBuildId = first?.get("id")?.jsonPrimitive?.contentOrNull,
                version = attrs?.get("version")?.jsonPrimitive?.contentOrNull,
                processingState = attrs?.get("processingState")?.jsonPrimitive?.contentOrNull,
                uploadedDate = attrs?.get("uploadedDate")?.jsonPrimitive?.contentOrNull,
                expired = attrs?.get("expired")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                errorCode = firstError?.get("code")?.jsonPrimitive?.contentOrNull
                    ?: firstError?.get("status")?.jsonPrimitive?.contentOrNull,
                message = firstError?.get("detail")?.jsonPrimitive?.contentOrNull
                    ?: firstError?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: root["meta"]?.jsonObject?.get("paging")?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull
                        ?.let { "App Store Connect builds lookup returned $it total build(s)." },
            )
        }.getOrElse { ParsedBuildsResponse(message = body.take(500)) }

    data class AscHttpResponse(
        val statusCode: Int,
        val body: String,
    )

    interface AscHttpClient {
        fun get(url: String, bearerToken: String): AscHttpResponse
    }

    class JavaNetAscHttpClient : AscHttpClient {
        private val httpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()

        override fun get(url: String, bearerToken: String): AscHttpResponse {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            return AscHttpResponse(response.statusCode(), response.body())
        }
    }

    private data class ParsedAppsResponse(
        val count: Int = 0,
        val firstAppId: String? = null,
        val firstAppName: String? = null,
        val errorCode: String? = null,
        val message: String? = null,
    )

    private data class ParsedBuildsResponse(
        val firstBuildId: String? = null,
        val version: String? = null,
        val processingState: String? = null,
        val uploadedDate: String? = null,
        val expired: Boolean? = null,
        val errorCode: String? = null,
        val message: String? = null,
    )

    companion object {
        private const val ASC_API_BASE = "https://api.appstoreconnect.apple.com"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

data class AppStoreConnectBuildLookupResult(
    val checkedAt: String,
    val configured: Boolean,
    val authenticated: Boolean = false,
    val appId: String? = null,
    val buildId: String? = null,
    val version: String? = null,
    val processingState: String? = null,
    val uploadedDate: String? = null,
    val expired: Boolean? = null,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

internal object AscJwtSigner {
    fun sign(credentials: AppStoreConnectKeyStore.Credentials, issuedAtEpochSeconds: Long): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("kid", credentials.keyId)
            put("typ", "JWT")
        }
        val payload = buildJsonObject {
            put("iss", credentials.issuerId)
            put("iat", issuedAtEpochSeconds)
            put("exp", issuedAtEpochSeconds + TOKEN_TTL_SECONDS)
            put("aud", "appstoreconnect-v1")
        }
        val signingInput = "${b64(Json.encodeToString(JsonObject.serializer(), header).toByteArray())}." +
            b64(Json.encodeToString(JsonObject.serializer(), payload).toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(parseEcPrivateKey(credentials.privateKeyPem))
        signature.update(signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${b64(derToJose(signature.sign()))}"
    }

    private fun parseEcPrivateKey(pem: String): ECPrivateKey {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
    }

    private fun derToJose(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER signature" }
        var offset = 2
        if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7f
        require(der[offset] == 0x02.toByte()) { "Invalid ECDSA DER R marker" }
        val rLen = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen)
        offset += 2 + rLen
        require(der[offset] == 0x02.toByte()) { "Invalid ECDSA DER S marker" }
        val sLen = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen)
        return unsignedFixed(r) + unsignedFixed(s)
    }

    private fun unsignedFixed(value: ByteArray): ByteArray {
        val normalized = BigInteger(1, value).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(normalized.size <= 32) { "Invalid ES256 signature component" }
        return ByteArray(32 - normalized.size) + normalized
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val TOKEN_TTL_SECONDS = 10 * 60L
}
