package com.siamakerlab.vibecoder.console.data.remote

import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorClientFactory @Inject constructor(
    private val prefs: AppPreferences,
) {
    fun create(): HttpClient {
        val cfg = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
        return HttpClient(OkHttp) {
            install(ContentNegotiation) { json(cfg) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) { Timber.tag("Ktor").d(message) }
                }
                level = LogLevel.INFO
            }
            // Bearer token loaded asynchronously from DataStore. `loadTokens` is a
            // suspending callback so we can do this without `runBlocking` — the
            // earlier implementation blocked every request and risked a deadlock
            // when called from the main dispatcher.
            install(Auth) {
                bearer {
                    loadTokens {
                        val token = prefs.session.first().token ?: return@loadTokens null
                        BearerTokens(accessToken = token, refreshToken = token)
                    }
                    // Login으로 새 토큰을 받아 prefs에 저장 → refresh는 그걸 다시 읽어옴.
                    refreshTokens {
                        val token = prefs.session.first().token ?: return@refreshTokens null
                        BearerTokens(accessToken = token, refreshToken = token)
                    }
                    sendWithoutRequest { request ->
                        // 인증 없이 호출되는 엔드포인트: login / setup / setup-status / 레거시 pair / health
                        // 이들엔 Authorization 헤더를 붙이지 않음.
                        val path = request.url.pathSegments.joinToString("/", prefix = "/")
                        path != "/api/auth/login" &&
                            path != "/api/auth/setup" &&
                            path != "/api/auth/setup/status" &&
                            !path.endsWith("/api/auth/pair") &&
                            path != "/health"
                    }
                }
            }
        }
    }
}
