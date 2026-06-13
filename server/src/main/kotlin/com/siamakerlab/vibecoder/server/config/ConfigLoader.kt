package com.siamakerlab.vibecoder.server.config

import com.charleskorn.kaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object ConfigLoader {

    private val yaml = Yaml.default

    /**
     * Load `server.yml` from (in order):
     *   1. $VIBECODER_CONFIG_DIR/server.yml
     *   2. ./config/server.yml          (working directory)
     *   3. classpath:config/server.yml  (packaged default)
     */
    fun load(): ServerConfig {
        val external = sequenceOf(
            System.getenv("VIBECODER_CONFIG_DIR")?.let { Path.of(it, "server.yml") },
            Path.of("config", "server.yml"),
        ).filterNotNull().firstOrNull { it.exists() }

        val text = if (external != null) {
            Files.readString(external)
        } else {
            requireNotNull(
                ConfigLoader::class.java.classLoader
                    .getResourceAsStream("config/server.yml")
            ) { "config/server.yml not found on classpath" }
                .bufferedReader().use { it.readText() }
        }

        val cfg = yaml.decodeFromString(ServerConfig.serializer(), text)
        // v1.70.7 — `server.version` 은 코드(번들 리소스)에서만 결정한다. 외부 config
        // (사용자가 /settings 에서 저장한 설정본)가 version 까지 박제해, 이미지 업그레이드
        // 후에도 대시보드/health 가 옛 버전을 표시하던 문제 회수. 사용자 설정(port/timeout/
        // maxConcurrentTurns 등)은 외부 config 가 그대로 유지하고, version 만 번들로 덮어쓴다.
        val withVersion = if (external != null) {
            val bundled = readBundledVersion()
            if (bundled != null && bundled != cfg.server.version)
                cfg.copy(server = cfg.server.copy(version = bundled)) else cfg
        } else cfg
        return applyEnvironmentOverrides(withVersion)
    }

    /** 번들(classpath) server.yml 의 server.version. 외부 config 의 박제된 version 무시용. */
    private fun readBundledVersion(): String? = runCatching {
        ConfigLoader::class.java.classLoader
            .getResourceAsStream("config/server.yml")
            ?.bufferedReader()?.use { it.readText() }
            ?.let { yaml.decodeFromString(ServerConfig.serializer(), it).server.version }
    }.getOrNull()

    private fun applyEnvironmentOverrides(cfg: ServerConfig): ServerConfig {
        var current = cfg

        // workspace root
        System.getenv("VIBECODER_WORKSPACE_ROOT")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(workspace = current.workspace.copy(root = it))
        }

        // v0.12.0 — CORS allowed hosts (콤마 구분)
        System.getenv("VIBECODER_CORS_ALLOWED_HOSTS")?.takeIf { it.isNotBlank() }?.let { raw ->
            val hosts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isNotEmpty()) {
                current = current.copy(cors = current.cors.copy(allowedHosts = hosts))
            }
        }
        System.getenv("VIBECODER_CORS_ALLOW_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(cors = current.cors.copy(allowCredentials = it.equals("true", true)))
        }

        // v0.14.0 — Database 설정 env override
        current = current.copy(database = applyDatabaseEnvOverrides(current.database))

        // v0.17.0 — Email/SMTP env override
        current = current.copy(email = applyEmailEnvOverrides(current.email))

        // v0.77.0 — Phase 64 i18n. VIBECODER_DEFAULT_LANGUAGE env override.
        //   허용 값: "en", "ko". 그 외 값은 무시 (server.yml 값 유지).
        System.getenv("VIBECODER_DEFAULT_LANGUAGE")?.trim()?.lowercase()
            ?.takeIf { it in setOf("en", "ko") }
            ?.let { current = current.copy(i18n = current.i18n.copy(defaultLanguage = it)) }

        // v1.28.0 (B-1) — 신뢰 프록시 뒤 배포 시 X-Forwarded-For 사용. 운영(openresty
        //   뒤)에서 server.yml 수정 없이 compose env 로 켤 수 있도록 override 지원.
        System.getenv("VIBECODER_TRUST_FORWARDED_FOR")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(security = current.security.copy(trustForwardedFor = it.equals("true", true)))
        }
        // v1.52.0 — 세션 idle timeout 분 env override. 기본 0(무제한, LAN 단일 사용자 편의).
        // 외부 노출 시 양수로 설정해 미사용 토큰 수명 제한(탈취 토큰 노출창 축소). server.yml
        // 수정 없이 compose env 로 조정.
        System.getenv("VIBECODER_SECURITY_SESSION_IDLE_TIMEOUT_MINUTES")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(security = current.security.copy(sessionIdleTimeoutMinutes = it.coerceAtLeast(0)))
            }

        // v1.106.0 — Claude 기본 모델 env override. 토큰 사용량 최대 레버(Opus→Sonnet).
        // server.yml 수정 없이 compose env 로 조정. "sonnet"/"opus"/"fable"/"haiku"/모델ID/"default".
        System.getenv("VIBECODER_CLAUDE_MODEL")?.trim()?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(claude = current.claude.copy(model = it))
        }
        System.getenv("VIBECODER_CLAUDE_CONTEXT_WARN_TOKENS")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(contextWarnTokens = it.coerceAtLeast(0)))
            }
        System.getenv("VIBECODER_CLAUDE_AUTO_COMPACT_TOKENS")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(autoCompactTokens = it.coerceAtLeast(0)))
            }
        // v1.123.0 — 2단계 경고 + 세션 길이 캡.
        System.getenv("VIBECODER_CLAUDE_CONTEXT_CRITICAL_TOKENS")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(contextCriticalTokens = it.coerceAtLeast(0)))
            }
        System.getenv("VIBECODER_CLAUDE_SESSION_RESET_TOKENS")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(sessionResetTokens = it.coerceAtLeast(0)))
            }
        System.getenv("VIBECODER_CLAUDE_SESSION_TURN_CAP")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(sessionTurnCap = it.coerceAtLeast(0)))
            }

        return current
    }

    private fun applyEmailEnvOverrides(e: EmailSection): EmailSection {
        var v = e
        System.getenv("VIBECODER_SMTP_ENABLED")?.takeIf { it.isNotBlank() }?.let {
            v = v.copy(enabled = it.equals("true", true))
        }
        System.getenv("VIBECODER_SMTP_HOST")?.takeIf { it.isNotBlank() }?.let { v = v.copy(host = it) }
        System.getenv("VIBECODER_SMTP_PORT")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { v = v.copy(port = it) }
        System.getenv("VIBECODER_SMTP_USER")?.takeIf { it.isNotBlank() }?.let { v = v.copy(user = it) }
        System.getenv("VIBECODER_SMTP_PASSWORD")?.takeIf { it.isNotBlank() }?.let { v = v.copy(password = it) }
        System.getenv("VIBECODER_SMTP_PASSWORD_FILE")?.takeIf { it.isNotBlank() }?.let { v = v.copy(passwordFile = it) }
        System.getenv("VIBECODER_SMTP_FROM")?.takeIf { it.isNotBlank() }?.let { v = v.copy(from = it) }
        System.getenv("VIBECODER_SMTP_TO")?.takeIf { it.isNotBlank() }?.let { v = v.copy(to = it) }
        System.getenv("VIBECODER_SMTP_TLS")?.takeIf { it.isNotBlank() }?.let {
            v = v.copy(tls = it.equals("true", true))
        }
        return v
    }

    private fun applyDatabaseEnvOverrides(db: DatabaseSection): DatabaseSection {
        var d = db
        System.getenv("VIBECODER_DB_HOST")?.takeIf { it.isNotBlank() }?.let { d = d.copy(host = it) }
        System.getenv("VIBECODER_DB_PORT")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { d = d.copy(port = it) }
        System.getenv("VIBECODER_DB_NAME")?.takeIf { it.isNotBlank() }?.let { d = d.copy(name = it) }
        System.getenv("VIBECODER_DB_USER")?.takeIf { it.isNotBlank() }?.let { d = d.copy(user = it) }
        System.getenv("VIBECODER_DB_PASSWORD")?.takeIf { it.isNotBlank() }?.let { d = d.copy(password = it) }
        System.getenv("VIBECODER_DB_PASSWORD_FILE")?.takeIf { it.isNotBlank() }?.let { d = d.copy(passwordFile = it) }
        System.getenv("VIBECODER_DB_MAX_POOL")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { d = d.copy(maxPoolSize = it) }
        System.getenv("VIBECODER_DB_SSLMODE")?.takeIf { it.isNotBlank() }?.let { d = d.copy(sslMode = it) }
        return d
    }
}
