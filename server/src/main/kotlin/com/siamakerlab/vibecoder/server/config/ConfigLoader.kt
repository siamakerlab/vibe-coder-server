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

        val cfg = yaml.decodeFromString(ServerConfig.serializer(), stripRemovedLegacyConfigKeys(text))
        // v1.70.7 — `server.version` 은 코드(번들 리소스)에서만 결정한다. 외부 config
        // (사용자가 /settings 에서 저장한 설정본)가 version 까지 박제해, 이미지 업그레이드
        // 후에도 대시보드/health 가 옛 버전을 표시하던 문제 회수. 사용자 설정(port/timeout 등)은
        // 외부 config 가 그대로 유지하고, version 만 번들로 덮어쓴다.
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
            ?.let { yaml.decodeFromString(ServerConfig.serializer(), stripRemovedLegacyConfigKeys(it)).server.version }
    }.getOrNull()

    /**
     * v1.161.x — provider별 turn/session cap 은 ResourceGuard 중심 관리로 제거됐다. 오래된
     * 외부 server.yml 에 키가 남아 있어도 부팅은 계속되도록 디코드 전에 해당 scalar line 만 버린다.
     */
    private fun stripRemovedLegacyConfigKeys(text: String): String {
        val removed = setOf(
            "maxConcurrentTurns",
            "maxResidentSessions",
            "sessionResetTokens",
            "sessionTurnCap",
        )
        return text.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                removed.any { key -> trimmed.startsWith("$key:") }
            }
            .joinToString("\n")
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

        current = current.copy(resources = applyResourceGuardEnvOverrides(current.resources))
        current = current.copy(ios = applyIosEnvOverrides(current.ios))

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
        System.getenv("VIBECODER_TERMINAL_IDLE_TIMEOUT_MINUTES")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(security = current.security.copy(terminalIdleTimeoutMinutes = it.coerceAtLeast(0)))
            }
        System.getenv("VIBECODER_CONSOLE_TUI_IDLE_TIMEOUT_MINUTES")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(security = current.security.copy(consoleTuiIdleTimeoutMinutes = it.coerceAtLeast(0)))
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
        // v1.123.0 — 2단계 경고.
        System.getenv("VIBECODER_CLAUDE_CONTEXT_CRITICAL_TOKENS")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                current = current.copy(claude = current.claude.copy(contextCriticalTokens = it.coerceAtLeast(0)))
            }
        System.getenv("VIBECODER_CODEX_MODEL")?.trim()?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(codex = current.codex.copy(model = it))
        }
        // v1.147.0 — Codex usage 모니터링 env override (Claude usage 와 대칭).
        System.getenv("VIBECODER_CODEX_USAGE_ENABLED")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(codex = current.codex.copy(usage = current.codex.usage.copy(enabled = it.equals("true", true))))
        }
        System.getenv("VIBECODER_CODEX_USAGE_POLL_MINUTES")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let {
            current = current.copy(codex = current.codex.copy(usage = current.codex.usage.copy(pollIntervalMinutes = it.coerceAtLeast(1))))
        }
        System.getenv("VIBECODER_CODEX_USAGE_WARN_PERCENT")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let {
            current = current.copy(codex = current.codex.copy(usage = current.codex.usage.copy(warnThresholdPercent = it.coerceIn(1, 100))))
        }
        System.getenv("VIBECODER_CODEX_USAGE_CRITICAL_PERCENT")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let {
            current = current.copy(codex = current.codex.copy(usage = current.codex.usage.copy(criticalThresholdPercent = it.coerceIn(1, 100))))
        }
        // v1.150.0 — OpenCode provider env override.
        System.getenv("VIBECODER_OPENCODE_MODEL")?.trim()?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(opencode = current.opencode.copy(model = it))
        }
        System.getenv("VIBECODER_OPENCODE_CONFIG_HOME")?.trim()?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(opencode = current.opencode.copy(configHome = it))
        }
        System.getenv("VIBECODER_OPENCODE_CMD")?.trim()?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(opencode = current.opencode.copy(cmd = it))
        }
        System.getenv("VIBECODER_ZAI_ENFORCE_CODING_PLAN")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(opencode = current.opencode.copy(zai = current.opencode.zai.copy(enforceCodingPlan = it.equals("true", true))))
        }

        return current
    }

    private fun applyResourceGuardEnvOverrides(r: ResourceGuardSection): ResourceGuardSection {
        var v = r
        System.getenv("VIBECODER_RESOURCE_GUARD_ENABLED")?.takeIf { it.isNotBlank() }?.let {
            v = v.copy(enabled = it.equals("true", true))
        }
        System.getenv("VIBECODER_RESOURCE_MEMORY_SOFT_PERCENT")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                v = v.copy(memorySoftLimitPercent = it.coerceIn(1, 100))
            }
        System.getenv("VIBECODER_RESOURCE_MEMORY_HARD_PERCENT")?.takeIf { it.isNotBlank() }
            ?.toIntOrNull()?.let {
                v = v.copy(memoryHardLimitPercent = it.coerceIn(1, 100))
            }
        System.getenv("VIBECODER_RESOURCE_MIN_FREE_MEMORY_MB")?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()?.let {
                v = v.copy(minFreeMemoryMb = it.coerceAtLeast(0))
            }
        System.getenv("VIBECODER_RESOURCE_KILL_IDLE_TUI_ON_PRESSURE")?.takeIf { it.isNotBlank() }?.let {
            v = v.copy(killIdleTuiSessionsOnPressure = it.equals("true", true))
        }
        return v
    }

    private fun applyIosEnvOverrides(ios: IosSection): IosSection {
        var agent = ios.agent
        System.getenv("VIBECODER_IOS_AGENT_ENABLED")?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(enabled = it.equals("true", true))
        }
        System.getenv("VIBECODER_IOS_AGENT_MODE")?.trim()?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(mode = normalizeIosAgentMode(it))
        }
        System.getenv("VIBECODER_IOS_AGENT_HOST")?.trim()?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(host = it)
        }
        System.getenv("VIBECODER_IOS_AGENT_PORT")?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let {
            agent = agent.copy(port = it.coerceIn(1, 65535))
        }
        System.getenv("VIBECODER_IOS_AGENT_USER")?.trim()?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(user = it)
        }
        System.getenv("VIBECODER_IOS_AGENT_WORKSPACE_ROOT")?.trim()?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(workspaceRoot = it)
        }
        System.getenv("VIBECODER_IOS_AGENT_XCODE_PATH")?.trim()?.takeIf { it.isNotBlank() }?.let {
            agent = agent.copy(xcodePath = it)
        }
        return ios.copy(agent = agent)
    }

    private fun normalizeIosAgentMode(value: String): String =
        when (value.trim().lowercase()) {
            "ssh", "remote" -> "ssh"
            else -> "local"
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
