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
        return applyEnvironmentOverrides(cfg)
    }

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
