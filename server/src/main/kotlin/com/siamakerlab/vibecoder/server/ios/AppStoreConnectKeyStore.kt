package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeyDto
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeySaveRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

class AppStoreConnectKeyStore(
    private val workspace: WorkspacePath,
    private val clock: Clock = SystemClock(),
) {
    data class Credentials(
        val keyId: String,
        val issuerId: String,
        val privateKeyPem: String,
        val privateKeyPath: Path,
    )

    fun get(): IosAppStoreConnectKeyDto {
        val metadata = readMetadata() ?: return IosAppStoreConnectKeyDto()
        val privateKeyPath = metadata.privateKeyPath.takeIf { it.isNotBlank() }
        val privateKeyPresent = privateKeyPath
            ?.let { runCatching { workspace.ensureUnderWorkspace(Path.of(it).toAbsolutePath().normalize()) }.getOrNull() }
            ?.isRegularFile()
            ?: false
        return IosAppStoreConnectKeyDto(
            configured = metadata.keyId.isNotBlank() && metadata.issuerId.isNotBlank() && privateKeyPresent,
            keyId = metadata.keyId,
            issuerId = metadata.issuerId,
            privateKeyPresent = privateKeyPresent,
            privateKeyPath = privateKeyPath,
            updatedAt = metadata.updatedAt,
        )
    }

    fun save(req: IosAppStoreConnectKeySaveRequestDto): IosAppStoreConnectKeyDto {
        val keyId = normalizeKeyId(req.keyId)
        val issuerId = normalizeIssuerId(req.issuerId)
        val dir = secretsDir()
        val existing = readMetadata()
        val privateKeyPath = req.privateKeyPem
            ?.let { writePrivateKey(dir, keyId, it) }
            ?: existing?.privateKeyPath?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("privateKeyPem is required for first App Store Connect key registration")

        writeMetadata(
            AscKeyMetadata(
                keyId = keyId,
                issuerId = issuerId,
                privateKeyPath = privateKeyPath,
                updatedAt = clock.nowIso(),
            )
        )
        return get()
    }

    fun loadCredentials(): Credentials? {
        val metadata = readMetadata() ?: return null
        val privateKeyPath = metadata.privateKeyPath.takeIf { it.isNotBlank() } ?: return null
        val path = runCatching {
            workspace.ensureUnderWorkspace(Path.of(privateKeyPath).toAbsolutePath().normalize())
        }.getOrNull() ?: return null
        if (!path.isRegularFile()) return null
        return Credentials(
            keyId = metadata.keyId,
            issuerId = metadata.issuerId,
            privateKeyPem = Files.readString(path),
            privateKeyPath = path,
        )
    }

    private fun writePrivateKey(dir: Path, keyId: String, raw: String): String {
        val pem = normalizePem(raw)
        val target = dir.resolve("AuthKey_$keyId.p8")
        val tmp = dir.resolve("AuthKey_$keyId.p8.tmp")
        Files.writeString(
            tmp,
            pem,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        setOwnerOnlyFile(tmp)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        setOwnerOnlyFile(target)
        return target.toAbsolutePath().normalize().toString()
    }

    private fun writeMetadata(metadata: AscKeyMetadata) {
        val dir = secretsDir()
        val target = dir.resolve(METADATA_FILE)
        val tmp = dir.resolve("$METADATA_FILE.tmp")
        Files.writeString(
            tmp,
            json.encodeToString(AscKeyMetadata.serializer(), metadata),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        setOwnerOnlyFile(tmp)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        setOwnerOnlyFile(target)
    }

    private fun readMetadata(): AscKeyMetadata? {
        val file = secretsDir(create = false).resolve(METADATA_FILE)
        if (!file.isRegularFile()) return null
        return runCatching {
            json.decodeFromString(AscKeyMetadata.serializer(), Files.readString(file))
        }.getOrNull()
    }

    private fun secretsDir(create: Boolean = true): Path {
        val dir = workspace.root.resolve(".vibecoder").resolve("secrets").resolve("app-store-connect")
        if (create && dir.notExists()) dir.createDirectories()
        if (create) setOwnerOnlyDir(dir)
        return dir
    }

    private fun normalizeKeyId(raw: String): String {
        val value = raw.trim().uppercase()
        require(value.matches(Regex("[A-Z0-9]{8,20}"))) { "keyId must be 8-20 uppercase letters or digits" }
        return value
    }

    private fun normalizeIssuerId(raw: String): String {
        val value = raw.trim().lowercase()
        require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            "issuerId must be a UUID"
        }
        return value
    }

    private fun normalizePem(raw: String): String {
        require('\u0000' !in raw) { "privateKeyPem must not contain NUL" }
        val value = raw.trim().replace("\r\n", "\n").replace('\r', '\n')
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 64..MAX_PRIVATE_KEY_BYTES) { "privateKeyPem size must be 64-$MAX_PRIVATE_KEY_BYTES bytes" }
        require(value.contains("-----BEGIN PRIVATE KEY-----")) { "privateKeyPem must contain BEGIN PRIVATE KEY" }
        require(value.contains("-----END PRIVATE KEY-----")) { "privateKeyPem must contain END PRIVATE KEY" }
        return value + "\n"
    }

    private fun setOwnerOnlyDir(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    private fun setOwnerOnlyFile(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    @Serializable
    private data class AscKeyMetadata(
        val keyId: String,
        val issuerId: String,
        val privateKeyPath: String,
        val updatedAt: String,
    )

    companion object {
        private const val METADATA_FILE = "app-store-connect-key.json"
        private const val MAX_PRIVATE_KEY_BYTES = 16 * 1024
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
