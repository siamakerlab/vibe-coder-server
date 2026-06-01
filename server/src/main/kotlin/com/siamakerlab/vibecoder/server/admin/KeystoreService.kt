package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.5.0 — Android 앱 키스토어 관리.
 *
 * 디렉토리: [keystoreDir] (기본 `/home/vibe/keystores`). 패키지명 prefix 의 4개
 * 파일 set 으로 관리:
 *  - `<pkg>.keystore`            — 릴리즈 (PKCS12, RSA 4096, validity N년)
 *  - `<pkg>-debug.keystore`      — 디버그 (동일 정보 + alias suffix `-debug`)
 *  - `<pkg>-keystore.properties` — Gradle signing config 파일 (storeFile /
 *                                  storePassword / keyAlias / keyPassword)
 *  - `<pkg>-admob.properties`    — AdMob IDs (선택)
 *
 * keytool 호출은 비동기 / sync 양쪽 가능하나 본 service 는 sync (Ktor route
 * handler 가 IO dispatcher 안에서 호출). 100년 validity 가 기본 (v1.54.2) —
 * Play Store 권장 (25+) 을 크게 상회, 운영자 정책. UI max 도 100.
 */
class KeystoreService(
    private val keystoreDir: Path = Path.of("/home/vibe/keystores"),
    private val defaults: KeystoreDefaults,
) {

    init {
        runCatching {
            Files.createDirectories(keystoreDir)
            setPerm(keystoreDir, "rwx------")
        }
    }

    /** 패키지명 유효성 — Android applicationId 표준. */
    private val packageNameRegex = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")

    /**
     * v1.57.0 — 마지막 생성 폼 입력값(비밀번호 제외) 캐시 파일.
     *
     * `.keystore` suffix 가 아니므로 [list] 의 스캔 대상에 잡히지 않는다. 비밀번호 /
     * alias 등 비밀값은 절대 저장하지 않으며, DN 메타(name/org/unit/country/state/city)
     * + validityYears 만 기록 → 다음 생성 폼 prefill 에 재사용. 운영자가 server.yml
     * 기본값과 다른 DN 을 한 번 입력하면 그 값이 이후 폼에 자동 반영된다.
     */
    private val lastInputFile = keystoreDir.resolve(".last-input.properties")

    /**
     * 전체 키스토어 entry list — 디렉토리 스캔 + 메타 추출.
     * `.keystore` (debug 제외) 파일만 일차 후보로, 같은 prefix 의 다른 파일
     * 존재 여부를 검사.
     */
    fun list(): List<KeystoreEntry> {
        if (!Files.isDirectory(keystoreDir)) return emptyList()
        return Files.list(keystoreDir).use { stream ->
            stream.toList()
                .filter { it.fileName.toString().endsWith(".keystore") && !it.fileName.toString().endsWith("-debug.keystore") }
                .map { entryFor(it.fileName.toString().removeSuffix(".keystore")) }
                .sortedBy { it.packageName }
        }
    }

    fun get(packageName: String): KeystoreEntry? {
        val release = keystoreDir.resolve("$packageName.keystore")
        if (!Files.exists(release)) return null
        return entryFor(packageName)
    }

    /** Host path of `<pkg>.keystore` (always under [keystoreDir]) — for build.gradle.kts inline reference. */
    fun storeFilePath(packageName: String): Path = keystoreDir.resolve("$packageName.keystore")

    /** Host path of `<pkg>-keystore.properties` — Claude prompt 에 절대경로로 노출. */
    fun propertiesPath(packageName: String): Path = keystoreDir.resolve("$packageName-keystore.properties")

    /**
     * v1.8.0 — 빌드 시 Gradle `-Pandroid.injected.signing.*` inject 용 자격증명 로드.
     *
     * 우선 `<pkg>-keystore.properties` 의 storeFile / storePassword / keyAlias /
     * keyPassword 를 읽고, storeFile 이 비어있으면 `<pkg>.keystore` 로 fallback.
     * key files 자체가 존재하지 않으면 null — 호출자(BuildService)가 silent skip.
     */
    fun loadSigning(packageName: String): SigningCredentials? {
        if (!packageNameRegex.matches(packageName)) return null
        val release = keystoreDir.resolve("$packageName.keystore")
        val props = keystoreDir.resolve("$packageName-keystore.properties")
        if (!Files.exists(release) || !Files.exists(props)) return null
        val p = Properties()
        runCatching {
            Files.newBufferedReader(props).use { p.load(it) }
        }.getOrElse { return null }
        val storePassword = p.getProperty("storePassword")?.takeIf { it.isNotBlank() } ?: return null
        val keyAlias = p.getProperty("keyAlias")?.takeIf { it.isNotBlank() } ?: return null
        val keyPassword = p.getProperty("keyPassword")?.takeIf { it.isNotBlank() } ?: storePassword
        val storeFile = p.getProperty("storeFile")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { Files.exists(it) }
            ?: release
        return SigningCredentials(
            storeFile = storeFile,
            storePassword = storePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword,
            propertiesFile = props,
        )
    }

    private fun entryFor(packageName: String): KeystoreEntry {
        val release = keystoreDir.resolve("$packageName.keystore")
        val debug = keystoreDir.resolve("$packageName-debug.keystore")
        val props = keystoreDir.resolve("$packageName-keystore.properties")
        val admob = keystoreDir.resolve("$packageName-admob.properties")
        return KeystoreEntry(
            packageName = packageName,
            releaseExists = Files.exists(release),
            debugExists = Files.exists(debug),
            propertiesExists = Files.exists(props),
            admobExists = Files.exists(admob),
            createdAt = runCatching { Files.getLastModifiedTime(release).toString() }.getOrNull(),
        )
    }

    /**
     * v1.71.0 — 패키지명 변경 시 키스토어 파일 4종을 새 prefix 로 rename.
     * `<old>.keystore` / `-debug.keystore` / `-keystore.properties` / `-admob.properties`
     * → `<new>…`. properties 내부의 옛 패키지 문자열(storeFile 경로 등)도 치환.
     * 대상 파일이 없으면 skip(무해). @return 이동한 파일 수.
     */
    fun renamePackage(oldPackageName: String, newPackageName: String): Int {
        if (!packageNameRegex.matches(newPackageName)) {
            throw IllegalArgumentException("invalid_package_name: $newPackageName")
        }
        if (oldPackageName == newPackageName) return 0
        val suffixes = listOf(".keystore", "-debug.keystore", "-keystore.properties", "-admob.properties")
        // v1.71.0 (정밀점검 H3) — 대상(newPackage) 파일이 이미 있으면 release 키 덮어쓰기로
        // 복구 불가한 서명키 손실 위험 → 사전 차단(REPLACE_EXISTING 미사용).
        suffixes.forEach { suffix ->
            val dst = keystoreDir.resolve("$newPackageName$suffix")
            if (Files.exists(keystoreDir.resolve("$oldPackageName$suffix")) && Files.exists(dst)) {
                throw IllegalStateException("keystore_target_exists: $newPackageName$suffix")
            }
        }
        var moved = 0
        for (suffix in suffixes) {
            val src = keystoreDir.resolve("$oldPackageName$suffix")
            if (!Files.exists(src)) continue
            val dst = keystoreDir.resolve("$newPackageName$suffix")
            Files.move(src, dst)
            moved++
            // v1.71.0 (정밀점검 M6) — properties 는 storeFile 경로 줄만 치환(전체 substring
            // 치환은 password/alias 안에 옛 패키지 문자열이 있으면 손상시킴).
            if (suffix.endsWith(".properties")) {
                runCatching {
                    val rewritten = Files.readString(dst).lineSequence().joinToString("\n") { line ->
                        if (line.trimStart().startsWith("storeFile=")) line.replace(oldPackageName, newPackageName) else line
                    }
                    Files.writeString(dst, rewritten)
                }
            }
            runCatching { setPerm(dst, "rw-------") }
        }
        log.info { "keystore rename: $oldPackageName → $newPackageName ($moved file(s))" }
        return moved
    }

    /**
     * 신규 키스토어 set 생성. 이미 존재하면 IllegalStateException.
     *
     * 빈 input 은 [defaults] 또는 throw — Routes 가 검증 후 호출.
     */
    fun create(req: CreateKeystoreRequest): KeystoreEntry {
        if (!packageNameRegex.matches(req.packageName)) {
            throw IllegalArgumentException("invalid_package_name: ${req.packageName}")
        }
        if (req.password.isBlank()) throw IllegalArgumentException("password_required")
        val release = keystoreDir.resolve("${req.packageName}.keystore")
        if (Files.exists(release)) throw IllegalStateException("already_exists: ${req.packageName}")
        Files.createDirectories(keystoreDir)
        runCatching { setPerm(keystoreDir, "rwx------") }

        val debug = keystoreDir.resolve("${req.packageName}-debug.keystore")
        val props = keystoreDir.resolve("${req.packageName}-keystore.properties")
        val admob = keystoreDir.resolve("${req.packageName}-admob.properties")

        val dname = buildDname(req)
        val validity = (req.validityYears ?: defaults.validityYears).coerceIn(1, 100) * 365

        keytool(release, "key0", req.password, dname, validity)
        runCatching { setPerm(release, "rw-------") }

        keytool(debug, "key0", req.password, dname, validity)
        runCatching { setPerm(debug, "rw-------") }

        Files.writeString(
            props,
            buildString {
                appendLine("# v1.5.0 — auto-generated by vibe-coder-server /settings/keystores")
                appendLine("# Android Gradle signing config — host path mounted at /home/vibe/keystores")
                appendLine("storeFile=/home/vibe/keystores/${req.packageName}.keystore")
                appendLine("storePassword=${req.password}")
                appendLine("keyAlias=key0")
                appendLine("keyPassword=${req.password}")
            },
        )
        runCatching { setPerm(props, "rw-------") }

        if (req.admob != null && (req.admob.appId.isNotBlank() || req.admob.bannerUnitId.isNotBlank())) {
            Files.writeString(
                admob,
                buildString {
                    appendLine("# v1.5.0 — auto-generated AdMob IDs")
                    if (req.admob.appId.isNotBlank()) appendLine("admobAppId=${req.admob.appId}")
                    if (req.admob.appOpenUnitId.isNotBlank()) appendLine("appOpenAdUnitId=${req.admob.appOpenUnitId}")
                    if (req.admob.bannerUnitId.isNotBlank()) appendLine("bannerAdUnitId=${req.admob.bannerUnitId}")
                    if (req.admob.nativeUnitId.isNotBlank()) appendLine("nativeAdUnitId=${req.admob.nativeUnitId}")
                },
            )
            runCatching { setPerm(admob, "rw-------") }
        }
        // v1.57.0 — DN 메타 + validityYears 캐시 (비밀번호 제외). 다음 생성 폼 prefill 용.
        runCatching { recordLastInput(req) }
            .onFailure { log.warn(it) { "last-input record failed (non-fatal)" } }

        log.info { "Keystore created: ${req.packageName}" }
        return entryFor(req.packageName)
    }

    /**
     * v1.57.0 — 다음 생성 폼 prefill 에 쓸 [KeystoreDefaults] 계산.
     *
     * 마지막 입력 캐시([lastInputFile])가 있으면 그 DN 값을 우선하고, 빈 필드는
     * server.yml [defaults] 로 fallback. 비밀번호([KeystoreDefaults.defaultPassword])는
     * 캐시에 없으므로 항상 server.yml 값 유지 (보통 운영자 기본 비번 prefill).
     */
    fun effectiveDefaults(): KeystoreDefaults {
        val last = loadLastInput() ?: return defaults
        fun pick(key: String, fallback: String): String =
            last.getProperty(key)?.trim()?.ifBlank { null } ?: fallback
        return defaults.copy(
            name = pick("name", defaults.name),
            organization = pick("organization", defaults.organization),
            unit = pick("unit", defaults.unit),
            country = pick("country", defaults.country),
            state = pick("state", defaults.state),
            city = pick("city", defaults.city),
            validityYears = last.getProperty("validityYears")?.trim()?.toIntOrNull()
                ?.coerceIn(1, 100) ?: defaults.validityYears,
        )
    }

    private fun loadLastInput(): Properties? {
        if (!Files.exists(lastInputFile)) return null
        return runCatching {
            Properties().apply { Files.newBufferedReader(lastInputFile).use { load(it) } }
        }.getOrNull()
    }

    /** DN 메타(비밀번호 제외)를 캐시 파일에 기록. create() 성공 직후 호출. */
    private fun recordLastInput(req: CreateKeystoreRequest) {
        val props = Properties().apply {
            setProperty("name", req.name)
            setProperty("organization", req.organization)
            setProperty("unit", req.unit)
            setProperty("country", req.country)
            setProperty("state", req.state)
            setProperty("city", req.city)
            setProperty("validityYears", (req.validityYears ?: defaults.validityYears).toString())
        }
        Files.newBufferedWriter(lastInputFile).use {
            props.store(it, "v1.57.0 — last keystore form input (NO password/secret). prefill cache.")
        }
        runCatching { setPerm(lastInputFile, "rw-------") }
    }

    /** 전체 set 삭제 — release / debug / properties / admob 모두. */
    fun delete(packageName: String) {
        if (!packageNameRegex.matches(packageName)) {
            throw IllegalArgumentException("invalid_package_name")
        }
        listOf(
            "$packageName.keystore",
            "$packageName-debug.keystore",
            "$packageName-keystore.properties",
            "$packageName-admob.properties",
        ).forEach { name ->
            runCatching { Files.deleteIfExists(keystoreDir.resolve(name)) }
        }
        log.info { "Keystore deleted: $packageName" }
    }

    private fun buildDname(req: CreateKeystoreRequest): String {
        // distinguished name: CN=name, OU=unit, O=org, L=city, ST=state, C=country
        // 빈 값은 default fallback. CN 만 필수.
        val cn = req.name.ifBlank { defaults.name }.ifBlank { "vibe-coder" }
        val ou = req.unit.ifBlank { defaults.unit }.ifBlank { "Mobile" }
        val o = req.organization.ifBlank { defaults.organization }.ifBlank { "vibe-coder" }
        val l = req.city.ifBlank { defaults.city }.ifBlank { "Seoul" }
        val st = req.state.ifBlank { defaults.state }.ifBlank { "KR" }
        val c = req.country.ifBlank { defaults.country }.ifBlank { "KR" }
        return "CN=$cn, OU=$ou, O=$o, L=$l, ST=$st, C=$c"
    }

    private fun keytool(path: Path, alias: String, password: String, dname: String, validityDays: Int) {
        val cmd = listOf(
            "keytool", "-genkeypair", "-v",
            "-keystore", path.toString(),
            "-storetype", "PKCS12",
            "-keyalg", "RSA", "-keysize", "4096",
            "-validity", validityDays.toString(),
            "-alias", alias,
            "-dname", dname,
            "-storepass", password,
            "-keypass", password,
        )
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("keytool_timeout for ${path.fileName}")
        }
        if (proc.exitValue() != 0) {
            val output = proc.inputStream.bufferedReader().readText().take(2_000)
            throw IllegalStateException("keytool_failed (exit=${proc.exitValue()}) for ${path.fileName}: $output")
        }
    }

    private fun setPerm(path: Path, mode: String) {
        val perms: Set<PosixFilePermission> = PosixFilePermissions.fromString(mode)
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }
}

/** v1.5.0 — 메타데이터만 (실제 password / properties 내용은 API 응답에 포함 X). */
data class KeystoreEntry(
    val packageName: String,
    val releaseExists: Boolean,
    val debugExists: Boolean,
    val propertiesExists: Boolean,
    val admobExists: Boolean,
    val createdAt: String?,
)

data class CreateKeystoreRequest(
    val packageName: String,
    val name: String = "",
    val organization: String = "",
    val unit: String = "",
    val country: String = "",
    val state: String = "",
    val city: String = "",
    val password: String = "",
    val validityYears: Int? = null,
    val admob: AdmobIds? = null,
)

data class AdmobIds(
    val appId: String = "",
    val appOpenUnitId: String = "",
    val bannerUnitId: String = "",
    val nativeUnitId: String = "",
)

/**
 * v1.8.0 — Gradle `android.injected.signing.*` inject 용 자격증명 + 원본 properties 경로.
 *
 * 호출자(BuildService)는 storePassword / keyPassword 가 log / CLI command line 에
 * echo 되지 않게 redact 책임. [propertiesFile] 은 Phase 2 (Claude 콘솔 prompt)에서
 * build.gradle.kts 영구 수정 안내용으로 참조.
 */
data class SigningCredentials(
    val storeFile: Path,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val propertiesFile: Path,
)
