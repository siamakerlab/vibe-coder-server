package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.Properties
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/** v1.101.0 — 업로드 키스토어 파일의 안전 상한(키스토어는 보통 수 KB — 256KB 면 충분). */
private const val MAX_KEYSTORE_BYTES = 256 * 1024

/**
 * v1.102.0 — [KeystoreService.stageUploaded] 결과. 서버는 최종 배치를 직접 하지 않고
 * staging 디렉토리에 규약 파일명으로만 저장한 뒤, Claude 콘솔 프롬프트가 이동배치한다.
 * [stagingDir] = staging 절대경로, [releaseFile]/[debugFile]/[propertiesFile] = 저장된
 * 규약 파일명(없으면 null).
 */
data class KeystoreStageResult(
    val stagingDir: Path,
    val releaseFile: String?,
    val debugFile: String?,
    val propertiesFile: String?,
) {
    val stagedAny: Boolean get() = releaseFile != null || debugFile != null || propertiesFile != null
    val stagedNames: List<String> get() = listOfNotNull(releaseFile, debugFile, propertiesFile)
}

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

    /** v1.98.0 — 아카이브용: 해당 패키지의 **존재하는** 키스토어 파일 경로(최대 4종). */
    fun keystoreFiles(packageName: String): List<Path> {
        if (!packageNameRegex.matches(packageName)) return emptyList()
        return listOf(
            "$packageName.keystore", "$packageName-debug.keystore",
            "$packageName-keystore.properties", "$packageName-admob.properties",
        ).map { keystoreDir.resolve(it) }.filter { Files.exists(it) }
    }

    /** v1.98.0 — 복원 시 키스토어 파일을 되돌려 놓을 디렉토리(`/home/vibe/keystores`). */
    fun keystoreDirPath(): Path = keystoreDir

    /**
     * v1.8.0 — 빌드 시 Gradle `-Pandroid.injected.signing.*` inject 용 자격증명 로드.
     *
     * 우선 `<pkg>-keystore.properties` 의 storeFile / storePassword / keyAlias /
     * keyPassword 를 읽고, storeFile 이 비어있으면 `<pkg>.keystore` 로 fallback.
     * key files 자체가 존재하지 않으면 null — 호출자(BuildService)가 silent skip.
     *
     * v1.144.5 — [debug] true 면 **디버그 빌드용**으로 `<pkg>-debug.keystore` 를
     * 강제 사용한다(properties 의 `storeFile`=릴리즈 경로는 무시). `android.injected.signing.*`
     * 는 AGP 가 빌드되는 variant(debug task 포함)에 그대로 적용하므로, 이전엔
     * `assembleDebug` 빌드에도 릴리즈 키(`<pkg>.keystore`)가 주입되던 회귀가 있었다.
     *
     * v1.154.1 — debug 빌드 시 keyAlias 도 `{properties.alias}-debug` 로 변환한다.
     * debug keystore 의 alias 는 release 와 다를 수 있다(운영자/업로드가 `-debug` 접미사로
     * 생성한 경우). [resolveDebugAlias] 가 Java KeyStore API 로 debug keystore 를 열어
     * `{alias}-debug` 가 실제로 존재하는지 검증한 뒤, 없으면 원래 alias 로 fallback 한다
     * (create() 가 양쪽을 동일 alias 로 생성한 기존 키스토어 호환).
     *
     * debug 키스토어가 없으면(과거 release 만 업로드 등) null → 미주입(AGP 표준 debug 서명).
     */
    fun loadSigning(packageName: String, debug: Boolean = false): SigningCredentials? {
        if (!packageNameRegex.matches(packageName)) return null
        val release = keystoreDir.resolve("$packageName.keystore")
        val debugKs = keystoreDir.resolve("$packageName-debug.keystore")
        val props = keystoreDir.resolve("$packageName-keystore.properties")
        // 서명에 쓸 키스토어: debug 빌드면 -debug.keystore, 아니면 release.
        val targetKeystore = if (debug) debugKs else release
        if (!Files.exists(targetKeystore) || !Files.exists(props)) return null
        val p = Properties()
        runCatching {
            Files.newBufferedReader(props).use { p.load(it) }
        }.getOrElse { return null }
        val storePassword = p.getProperty("storePassword")?.takeIf { it.isNotBlank() } ?: return null
        val keyAlias = p.getProperty("keyAlias")?.takeIf { it.isNotBlank() } ?: return null
        val keyPassword = p.getProperty("keyPassword")?.takeIf { it.isNotBlank() } ?: storePassword
        // debug 빌드는 항상 -debug.keystore 강제 — properties.storeFile 은 릴리즈 경로라 무시.
        // release 빌드만 properties 의 storeFile(보통 릴리즈 키 경로)을 신뢰, 없으면 fallback.
        val storeFile = if (debug) {
            debugKs
        } else {
            p.getProperty("storeFile")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?.takeIf { Files.exists(it) }
                ?: release
        }
        // debug 빌드: debug keystore 의 실제 alias 를 Java KeyStore API 로 확인.
        // {keyAlias}-debug 가 있으면 사용, 없거나 keystore를 열 수 없으면 원래 alias 로 fallback.
        val effectiveKeyAlias = if (debug) {
            resolveDebugAlias(debugKs, storePassword, keyAlias)
        } else {
            keyAlias
        }
        return SigningCredentials(
            storeFile = storeFile,
            storePassword = storePassword,
            keyAlias = effectiveKeyAlias,
            keyPassword = keyPassword,
            propertiesFile = props,
        )
    }

    /**
     * v1.154.1 — debug keystore 에서 `{baseAlias}-debug` alias 존재 여부를 Java KeyStore API 로
     * 검증한다. 존재하면 `{baseAlias}-debug` 를 반환하고, 없거나 keystore를 열 수 없으면
     * 원래 [baseAlias] 를 반환한다(create() 가 동일 alias 로 생성한 기존 키스토어 호환).
     *
     * keytool 프로세스 spawn 은 무거우므로 JVM 내 KeyStore API 로 처리.
     */
    private fun resolveDebugAlias(debugKs: Path, storePassword: String, baseAlias: String): String {
        val debugAlias = "$baseAlias-debug"
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(debugKs.toFile()).use { fis ->
                ks.load(fis, storePassword.toCharArray())
            }
            if (ks.containsAlias(debugAlias)) debugAlias else baseAlias
        }.getOrElse {
            // keystore를 열 수 없는 경우(더미 파일, 손상, 지원하지 않는 형식 등) baseAlias fallback.
            log.debug { "resolveDebugAlias: keystore 열기 실패, baseAlias=$baseAlias 사용 — ${it.message}" }
            baseAlias
        }
    }

    /**
     * v1.93.0 — 인증서 SHA 지문 열람 (Firebase / Google Sign-In / Maps API 등록용).
     *
     * release / debug 키스토어를 `keytool -list -v` 로 읽어 SHA-1 / SHA-256 / MD5 +
     * 만료일을 추출. store password 는 `<pkg>-keystore.properties` 에서 가져온다
     * (운영자가 생성한 키스토어는 이 properties 를 항상 동반). 비밀번호를 모르면
     * available=false 로 반환 — 호출자(라우트)가 안내 문구 표시. keytool 호출이
     * 무거워(프로세스 spawn) 프로젝트 탭 첫 렌더가 아니라 SHA 섹션을 펼칠 때만 lazy 호출.
     */
    fun fingerprints(packageName: String): KeystoreFingerprints {
        if (!packageNameRegex.matches(packageName)) {
            return KeystoreFingerprints(false, "invalid_package_name", null, null)
        }
        val release = keystoreDir.resolve("$packageName.keystore")
        if (!Files.exists(release)) return KeystoreFingerprints(false, "no_keystore", null, null)
        val signing = loadSigning(packageName)
            ?: return KeystoreFingerprints(false, "password_unknown", null, null)
        val releaseInfo = runCatching { readCert(release, signing.storePassword, signing.keyAlias) }
            .onFailure { log.warn(it) { "keytool -list release failed for $packageName" } }
            .getOrNull()
        val debugFile = keystoreDir.resolve("$packageName-debug.keystore")
        val debugInfo = if (Files.exists(debugFile)) {
            // v1.154.1 — debug keystore 의 alias 가 release 와 다를 수 있으므로 resolveDebugAlias 로 확인.
            val debugAlias = resolveDebugAlias(debugFile, signing.storePassword, signing.keyAlias)
            runCatching { readCert(debugFile, signing.storePassword, debugAlias) }
                .onFailure { log.warn(it) { "keytool -list debug failed for $packageName" } }
                .getOrNull()
        } else null
        val available = releaseInfo != null || debugInfo != null
        return KeystoreFingerprints(
            available = available,
            error = if (available) null else "read_failed",
            release = releaseInfo,
            debug = debugInfo,
        )
    }

    /** keytool -list -v 출력 파싱 — 단일 alias 의 SHA-1/SHA-256/MD5 + 만료일. */
    private fun readCert(path: Path, password: String, alias: String): CertInfo {
        val cmd = listOf(
            "keytool", "-list", "-v",
            "-keystore", path.toString(),
            "-storepass", password,
            "-alias", alias,
        )
        // redirectErrorStream(true) 단일 스트림을 readText() 로 완독 후 waitFor (read→wait 순서라
        // 버퍼 데드락 없음). exit!=0 이면 출력 일부를 메시지에 실어 throw.
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(20, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("keytool_list_timeout for ${path.fileName}")
        }
        if (proc.exitValue() != 0) {
            throw IllegalStateException("keytool_list_failed (exit=${proc.exitValue()}): ${out.take(300)}")
        }
        fun grab(vararg labels: String): String? {
            for (raw in out.lineSequence()) {
                val line = raw.trim()
                for (lbl in labels) {
                    if (line.startsWith(lbl, ignoreCase = true)) {
                        return line.substring(lbl.length).trim().ifBlank { null }
                    }
                }
            }
            return null
        }
        val validUntil = out.lineSequence()
            .firstOrNull { it.contains("until:", ignoreCase = true) }
            ?.substringAfter("until:")?.trim()?.ifBlank { null }
        return CertInfo(
            sha1 = grab("SHA1:", "SHA-1:"),
            sha256 = grab("SHA256:", "SHA-256:"),
            md5 = grab("MD5:"),
            validUntil = validUntil,
        )
    }

    /**
     * v1.93.0 / v1.94.0 — `<pkg>-admob.properties` 읽기. 없으면 빈 [AdmobIds].
     * 키스토어 존재와 독립. 각 unit 키는 콤마 구분 다중값 (v1.93.0 단일값과 호환).
     */
    fun readAdmob(packageName: String): AdmobIds {
        val admob = keystoreDir.resolve("$packageName-admob.properties")
        if (!Files.exists(admob)) return AdmobIds()
        val p = Properties()
        runCatching { Files.newBufferedReader(admob).use { p.load(it) } }
            .onFailure { log.warn(it) { "admob read failed for $packageName" } }
        // v1.107.4 — 키 이름 스키마가 매번 달라(저장폼: admobAppId/bannerAdUnitId, relocate 변형:
        // release.admob_banner_id / admob.bannerId / admob.test.* 등) 화이트리스트로는 한계.
        // → **값 패턴 + 키 키워드 기반 일반 파싱**으로 어떤 스키마든 인식한다.
        //   · 값이 ca-app-pub-…~… (틸드) → App ID,  …/… (슬래시) → 광고 unit ID
        //   · unit 타입은 키 키워드(banner/appOpen/native/interstitial/rewarded[Interstitial])로 분류
        //   · **테스트 ID 는 섹션에 표시하지 않음**: 키에 test/debug 포함 OR 값이 Google 공식
        //     테스트 publisher(ca-app-pub-3940256099942544) 면 제외. 운영(실) ID 만 노출.
        val types = linkedMapOf(
            "appOpen" to mutableListOf<String>(), "banner" to mutableListOf(), "native" to mutableListOf(),
            "interstitial" to mutableListOf(), "rewardedInterstitial" to mutableListOf(), "rewarded" to mutableListOf(),
        )
        val appIds = mutableListOf<String>()
        fun classify(keyLower: String): String? = when {
            Regex("rewarded.?interstitial").containsMatchIn(keyLower) -> "rewardedInterstitial"
            keyLower.contains("rewarded") -> "rewarded"
            keyLower.contains("interstitial") -> "interstitial"
            keyLower.contains("appopen") || keyLower.contains("app_open") ||
                (keyLower.contains("open") && keyLower.contains("app")) -> "appOpen"
            keyLower.contains("native") -> "native"
            keyLower.contains("banner") -> "banner"
            else -> null
        }
        for (name in p.stringPropertyNames()) {
            val keyLower = name.lowercase()
            val keyIsTest = keyLower.contains("test") || keyLower.contains("debug")
            val tokens = p.getProperty(name).orEmpty().split(",")
                .map { it.trim() }.filter { it.startsWith("ca-app-pub-", ignoreCase = true) }
            for (tok in tokens) {
                // 테스트 ID 제외: 테스트 키이거나 Google 공식 테스트 publisher.
                if (keyIsTest || tok.startsWith("ca-app-pub-3940256099942544")) continue
                when {
                    tok.contains("~") -> appIds.add(tok)
                    tok.contains("/") -> classify(keyLower)?.let { types.getValue(it).add(tok) }
                }
            }
        }
        return AdmobIds(
            appId = appIds.firstOrNull().orEmpty(),
            appOpenUnitIds = types.getValue("appOpen").distinct(),
            bannerUnitIds = types.getValue("banner").distinct(),
            nativeUnitIds = types.getValue("native").distinct(),
            interstitialUnitIds = types.getValue("interstitial").distinct(),
            rewardedUnitIds = types.getValue("rewarded").distinct(),
            rewardedInterstitialUnitIds = types.getValue("rewardedInterstitial").distinct(),
        )
    }

    /**
     * v1.94.0 — `<pkg>-admob.properties` 본문 직렬화. 다중 unit ID 는 콤마 구분.
     * [saveAdmob] 와 [create] 가 공유 (key 이름 일치 보장).
     */
    private fun admobFileBody(ids: AdmobIds): String = buildString {
        appendLine("# v1.94.0 — AdMob IDs (vibe-coder-server 키스토어 관리)")
        appendLine("# 다중 unit ID 는 콤마(,) 구분. 빌드 스크립트에서 split(\",\") 로 읽으세요.")
        if (ids.appId.isNotBlank()) appendLine("admobAppId=${ids.appId}")
        fun line(key: String, v: List<String>) {
            val clean = v.map { it.trim() }.filter { it.isNotBlank() }
            if (clean.isNotEmpty()) appendLine("$key=${clean.joinToString(",")}")
        }
        line("appOpenAdUnitId", ids.appOpenUnitIds)
        line("bannerAdUnitId", ids.bannerUnitIds)
        line("nativeAdUnitId", ids.nativeUnitIds)
        line("interstitialAdUnitId", ids.interstitialUnitIds)
        line("rewardedAdUnitId", ids.rewardedUnitIds)
        line("rewardedInterstitialAdUnitId", ids.rewardedInterstitialUnitIds)
    }

    /**
     * v1.93.0 / v1.94.0 — `<pkg>-admob.properties` 갱신 (키스토어 재생성 없이 광고 ID 만 단독 저장).
     * App ID 도 unit 도 모두 비면 파일 삭제 (admobExists=false 로). create() 와 같은 key 이름 사용.
     */
    fun saveAdmob(packageName: String, ids: AdmobIds) {
        if (!packageNameRegex.matches(packageName)) {
            throw IllegalArgumentException("invalid_package_name: $packageName")
        }
        val admob = keystoreDir.resolve("$packageName-admob.properties")
        if (ids.isBlank) {
            runCatching { Files.deleteIfExists(admob) }
            return
        }
        Files.createDirectories(keystoreDir)
        runCatching { setPerm(keystoreDir, "rwx------") }
        Files.writeString(admob, admobFileBody(ids))
        runCatching { setPerm(admob, "rw-------") }
        log.info { "AdMob IDs saved: $packageName" }
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

        // v1.154.1 — debug keystore 의 alias 는 `key0-debug` (release 와 구분).
        // loadSigning(debug=true) 가 resolveDebugAlias 로 `{properties.alias}-debug` 를 자동 탐지.
        keytool(debug, "key0-debug", req.password, dname, validity)
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

        if (req.admob != null && !req.admob.isBlank) {
            Files.writeString(admob, admobFileBody(req.admob))
            runCatching { setPerm(admob, "rw-------") }
        }
        // v1.57.0 — DN 메타 + validityYears 캐시 (비밀번호 제외). 다음 생성 폼 prefill 용.
        runCatching { recordLastInput(req) }
            .onFailure { log.warn(it) { "last-input record failed (non-fatal)" } }

        log.info { "Keystore created: ${req.packageName}" }
        return entryFor(req.packageName)
    }

    /** v1.102.0 — 업로드 staging 디렉토리 `<keystoreDir>/_staging/<pkg>/`. 워크스페이스 밖이라 git 무관. */
    fun stagingDir(packageName: String): Path = keystoreDir.resolve("_staging").resolve(packageName)

    /**
     * v1.102.0 — 사용자가 업로드한 키스토어 파일을 **staging 디렉토리에 규약 파일명으로** 저장.
     *
     * 서버는 최종 위치([keystoreDir])로 직접 배치하지 않는다 — 대신 Claude 콘솔 프롬프트
     * ([buildKeystorePlacementPrompt])가 mv(기존 파일 백업 포함) + build.gradle.kts 서명
     * 적용 + staging 정리를 한 turn 에 수행한다(사용자 요청 — 이동배치를 Claude 에 위임).
     *
     * release/debug/properties 각각 선택적이되 최소 1개. keystore 바이트는 PKCS12/JKS
     * 매직 + 256KB 1차 검증. properties 는 storePassword/keyAlias/keyPassword 추출 +
     * `storeFile` 을 서버 표준 경로로 강제 정규화(업로드된 로컬 경로 무효화). 같은 패키지의
     * 이전 staging 잔재는 비우고 새로 만든다.
     */
    fun stageUploaded(
        packageName: String,
        releaseBytes: ByteArray?,
        debugBytes: ByteArray?,
        propertiesText: String?,
    ): KeystoreStageResult {
        if (!packageNameRegex.matches(packageName)) {
            throw IllegalArgumentException("invalid_package_name: $packageName")
        }
        if (releaseBytes == null && debugBytes == null && propertiesText.isNullOrBlank()) {
            throw IllegalArgumentException("no_files")
        }
        val staging = stagingDir(packageName)
        runCatching { if (Files.exists(staging)) staging.toFile().deleteRecursively() }
        Files.createDirectories(staging)
        runCatching { setPerm(keystoreDir, "rwx------") }
        runCatching { setPerm(staging.parent, "rwx------") }
        runCatching { setPerm(staging, "rwx------") }

        fun stageKeystore(bytes: ByteArray, fileName: String): String {
            validateKeystoreMagic(bytes, fileName)
            val f = staging.resolve(fileName)
            Files.write(f, bytes)
            runCatching { setPerm(f, "rw-------") }
            return fileName
        }

        val releaseFile = releaseBytes?.let { stageKeystore(it, "$packageName.keystore") }
        val debugFile = debugBytes?.let { stageKeystore(it, "$packageName-debug.keystore") }
        var propertiesFile: String? = null
        if (!propertiesText.isNullOrBlank()) {
            val normalized = normalizeUploadedProperties(packageName, propertiesText)
            val name = "$packageName-keystore.properties"
            val f = staging.resolve(name)
            Files.writeString(f, normalized)
            runCatching { setPerm(f, "rw-------") }
            propertiesFile = name
        }
        log.info {
            "Keystore staged: $packageName at $staging " +
                "(release=${releaseFile != null} debug=${debugFile != null} props=${propertiesFile != null})"
        }
        return KeystoreStageResult(staging, releaseFile, debugFile, propertiesFile)
    }

    /** PKCS12(DER SEQUENCE 0x30) 또는 JKS(magic 0xFEEDFEED) 매직 + 크기 1차 검증. */
    private fun validateKeystoreMagic(bytes: ByteArray, fileName: String) {
        if (bytes.isEmpty()) throw IllegalArgumentException("empty_keystore: $fileName")
        if (bytes.size > MAX_KEYSTORE_BYTES) throw IllegalArgumentException("keystore_too_large: $fileName")
        val isPkcs12 = (bytes[0].toInt() and 0xFF) == 0x30
        val isJks = bytes.size >= 4 &&
            (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xED &&
            (bytes[2].toInt() and 0xFF) == 0xFE && (bytes[3].toInt() and 0xFF) == 0xED
        if (!isPkcs12 && !isJks) throw IllegalArgumentException("not_a_keystore: $fileName")
    }

    /**
     * 업로드된 properties 에서 storePassword/keyAlias/keyPassword 를 추출하고 storeFile 을
     * 서버 표준 경로로 강제 재작성. create() 와 동일한 raw `key=value` 라인 형식(escape 안 함).
     */
    private fun normalizeUploadedProperties(packageName: String, text: String): String {
        val map = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val i = line.indexOf('=')
                line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
        val storePassword = map["storePassword"]?.ifBlank { null }
        val keyAlias = map["keyAlias"]?.ifBlank { null }
        val keyPassword = map["keyPassword"]?.ifBlank { null } ?: storePassword
        if (storePassword == null || keyAlias == null) {
            throw IllegalArgumentException("invalid_properties")
        }
        return buildString {
            appendLine("# v1.101.0 — uploaded via vibe-coder-server (storeFile normalized to host path)")
            appendLine("storeFile=/home/vibe/keystores/$packageName.keystore")
            appendLine("storePassword=$storePassword")
            appendLine("keyAlias=$keyAlias")
            appendLine("keyPassword=$keyPassword")
        }
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

/**
 * v1.94.0 — AdMob 광고 ID. App ID 1개 + 6개 광고 유형별 **다중** unit ID.
 *
 * 6 유형: 배너 / 앱 오프닝 / 네이티브 고급형 / 전면 / 보상형 / 보상형 전면.
 * 각 유형 리스트는 `<pkg>-admob.properties` 에 콤마 구분으로 직렬화한다 — AdMob unit ID
 * (`ca-app-pub-…/…`) 에는 콤마가 없으므로 안전하고, v1.93.0 까지의 단일값 파일
 * (`bannerAdUnitId=ca-…`) 도 split 시 1개짜리 리스트로 그대로 호환된다.
 */
data class AdmobIds(
    val appId: String = "",
    val appOpenUnitIds: List<String> = emptyList(),
    val bannerUnitIds: List<String> = emptyList(),
    val nativeUnitIds: List<String> = emptyList(),
    val interstitialUnitIds: List<String> = emptyList(),
    val rewardedUnitIds: List<String> = emptyList(),
    val rewardedInterstitialUnitIds: List<String> = emptyList(),
) {
    /** unit ID 가 한 유형이라도 1개 이상 있는가. */
    val hasAnyUnit: Boolean
        get() = appOpenUnitIds.isNotEmpty() || bannerUnitIds.isNotEmpty() ||
            nativeUnitIds.isNotEmpty() || interstitialUnitIds.isNotEmpty() ||
            rewardedUnitIds.isNotEmpty() || rewardedInterstitialUnitIds.isNotEmpty()

    /** App ID 도 unit 도 전혀 없는 상태 (= 파일 삭제 대상). */
    val isBlank: Boolean get() = appId.isBlank() && !hasAnyUnit
}

/** v1.93.0 — 단일 인증서의 지문 + 만료일 (keytool -list -v 파싱 결과). */
data class CertInfo(
    val sha1: String?,
    val sha256: String?,
    val md5: String?,
    val validUntil: String?,
)

/**
 * v1.93.0 — release/debug 키스토어 지문 묶음.
 * [available] = 비밀번호를 알고 최소 하나를 읽었는지. false 면 [error] 사유
 * (`no_keystore` / `password_unknown` / `read_failed` / `invalid_package_name`).
 */
data class KeystoreFingerprints(
    val available: Boolean,
    val error: String?,
    val release: CertInfo?,
    val debug: CertInfo?,
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
