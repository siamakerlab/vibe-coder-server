package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * v0.46.0 — Phase 25 Web Push notifier (payload-less mode).
 *
 * Sends VAPID-authenticated push notifications to every registered browser subscription.
 * No payload is sent — the service worker shows a generic notification on receipt and
 * the user clicks it to open the dashboard. This trade-off avoids implementing
 * ECDH/HKDF/AES-128-GCM payload encryption (which would need either web-push-java or
 * a substantial in-house crypto module). For now we trade payload customisation for a
 * zero-extra-dependency implementation built entirely on JDK 11+ stdlib.
 *
 * Key storage: VAPID keypair lives at "&lt;workspace.root&gt;/.vibecoder/vapid-keys.json"
 * so it survives container restarts and stays inside the workspace boundary.
 */
class WebPushNotifier(
    private val workspace: WorkspacePath,
    private val subjectMailto: String = "mailto:noreply@vibecoder.local",
    private val subscriptionListProvider: () -> List<PushSubscription>,
    private val onGoneSubscription: (String) -> Unit = { _ -> },
) {

    data class PushSubscription(
        val id: String,
        val endpoint: String,
    )

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    private val keyPair: KeyPair by lazy { loadOrGenerate() }

    /** Returns the public key encoded as uncompressed point (04||X||Y), base64url no padding. */
    fun publicKeyBase64Url(): String {
        val pub = keyPair.public as ECPublicKey
        val x = unsignedFixed(pub.w.affineX, 32)
        val y = unsignedFixed(pub.w.affineY, 32)
        val raw = ByteArray(65)
        raw[0] = 0x04
        System.arraycopy(x, 0, raw, 1, 32)
        System.arraycopy(y, 0, raw, 33, 32)
        return base64Url(raw)
    }

    /**
     * Build + sign a VAPID JWT for the given push endpoint. Spec: RFC 8292.
     * `aud` = origin of the push endpoint (scheme + host[:port]).
     */
    fun buildVapidJwt(endpoint: String, ttlSeconds: Long = 12 * 3600L): String {
        val origin = endpointOrigin(endpoint)
        val header = """{"typ":"JWT","alg":"ES256"}"""
        val payload = """{"aud":"$origin","exp":${Instant.now().epochSecond + ttlSeconds},"sub":"$subjectMailto"}"""
        val signingInput = base64Url(header.toByteArray(StandardCharsets.US_ASCII)) +
            "." + base64Url(payload.toByteArray(StandardCharsets.US_ASCII))

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        val derSig = sig.sign()
        val jose = derToJose(derSig)
        return signingInput + "." + base64Url(jose)
    }

    /**
     * Fan-out a push to every subscription. Each push is independent: 410/404 → DELETE
     * via [onGoneSubscription]; other errors logged + skipped.
     */
    fun broadcast(title: String, body: String) {
        val subs = runCatching { subscriptionListProvider() }.getOrDefault(emptyList())
        if (subs.isEmpty()) return
        // Encode generic title/body so the service-worker can still show distinct messages
        // even though we don't actually encrypt — the body just doesn't reach the SW until
        // we add AES-GCM. Keep it as a placeholder so the SW code path is exercised.
        val payloadJson = """{"title":${jsonQuote(title)},"body":${jsonQuote(body)}}"""
        log.info { "webpush broadcast → ${subs.size} subscription(s): $title — $body" }
        for (sub in subs) {
            try {
                sendOne(sub, payloadJson)
            } catch (e: Throwable) {
                log.warn(e) { "webpush send failed for ${sub.endpoint.take(80)}" }
            }
        }
    }

    private fun sendOne(sub: PushSubscription, payloadJson: String) {
        val jwt = buildVapidJwt(sub.endpoint)
        val req = HttpRequest.newBuilder(URI.create(sub.endpoint))
            .timeout(Duration.ofSeconds(8))
            .header("Authorization", "vapid t=$jwt, k=${publicKeyBase64Url()}")
            .header("TTL", "60")
            .header("Content-Type", "application/json")
            .header("Urgency", "normal")
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.discarding())
        when (res.statusCode()) {
            201, 202, 204 -> { /* delivered */ }
            404, 410 -> {
                log.info { "webpush subscription gone (status ${res.statusCode()}) — removing ${sub.id}" }
                runCatching { onGoneSubscription(sub.id) }
            }
            else -> log.warn { "webpush unexpected status ${res.statusCode()} for ${sub.endpoint.take(80)}" }
        }
    }

    // region key load/save

    private fun loadOrGenerate(): KeyPair {
        val dir = workspace.root.resolve(".vibecoder")
        val file = dir.resolve("vapid-keys.json")
        if (Files.exists(file)) {
            runCatching {
                val text = Files.readString(file)
                val (privB64, xB64, yB64) = parseKeyJson(text)
                return rebuildKeyPair(privB64, xB64, yB64)
            }.onFailure {
                log.warn(it) { "vapid-keys.json corrupt — regenerating" }
            }
        }
        Files.createDirectories(dir)
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        val priv = (kp.private as ECPrivateKey).s
        val pub = kp.public as ECPublicKey
        val json = """
            {
              "privateD": "${base64Url(unsignedFixed(priv, 32))}",
              "publicX": "${base64Url(unsignedFixed(pub.w.affineX, 32))}",
              "publicY": "${base64Url(unsignedFixed(pub.w.affineY, 32))}"
            }
        """.trimIndent()
        val tmp = dir.resolve("vapid-keys.json.tmp")
        Files.writeString(tmp, json)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        log.info { "generated VAPID keypair → $file" }
        return kp
    }

    private fun parseKeyJson(text: String): Triple<String, String, String> {
        fun field(key: String): String =
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?: error("missing $key")
        return Triple(field("privateD"), field("publicX"), field("publicY"))
    }

    private fun rebuildKeyPair(privB64: String, xB64: String, yB64: String): KeyPair {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val spec = params.getParameterSpec(ECParameterSpec::class.java)
        val kf = KeyFactory.getInstance("EC")
        val d = BigInteger(1, base64UrlDecode(privB64))
        val x = BigInteger(1, base64UrlDecode(xB64))
        val y = BigInteger(1, base64UrlDecode(yB64))
        val priv = kf.generatePrivate(ECPrivateKeySpec(d, spec))
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
        return KeyPair(pub, priv)
    }

    // endregion

    companion object {
        private val B64_URL = Base64.getUrlEncoder().withoutPadding()
        private val B64_URL_DEC = Base64.getUrlDecoder()

        fun base64Url(bytes: ByteArray): String = B64_URL.encodeToString(bytes)
        fun base64UrlDecode(s: String): ByteArray = B64_URL_DEC.decode(s)

        /** Pad/truncate a BigInteger.toByteArray() result to a fixed unsigned big-endian length. */
        private fun unsignedFixed(v: BigInteger, len: Int): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == len -> raw
                raw.size == len + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, raw.size)
                raw.size < len -> ByteArray(len).also { raw.copyInto(it, len - raw.size) }
                else -> raw.copyOfRange(raw.size - len, raw.size)
            }
        }

        /** ECDSA DER signature → JOSE raw (R||S, 32 bytes each). */
        private fun derToJose(der: ByteArray): ByteArray {
            // DER: 30 LL 02 RL Rbytes 02 SL Sbytes
            // tolerate leading zero byte in R/S
            var i = 0
            if (der[i++] != 0x30.toByte()) error("not DER seq")
            val totalLen = der[i++].toInt() and 0xff
            // ignore long-form length (signature lengths fit in one byte for P-256)
            if (der[i++] != 0x02.toByte()) error("not int R")
            val rLen = der[i++].toInt() and 0xff
            var r = der.copyOfRange(i, i + rLen); i += rLen
            if (der[i++] != 0x02.toByte()) error("not int S")
            val sLen = der[i++].toInt() and 0xff
            var s = der.copyOfRange(i, i + sLen)
            // Strip leading zero if R/S is 33 bytes (BigInteger sign byte)
            if (r.size == 33 && r[0].toInt() == 0) r = r.copyOfRange(1, 33)
            if (s.size == 33 && s[0].toInt() == 0) s = s.copyOfRange(1, 33)
            val rPad = ByteArray(32).also { r.copyInto(it, 32 - r.size) }
            val sPad = ByteArray(32).also { s.copyInto(it, 32 - s.size) }
            val out = ByteArray(64)
            rPad.copyInto(out, 0)
            sPad.copyInto(out, 32)
            return out
        }

        private fun endpointOrigin(endpoint: String): String {
            val u = URI.create(endpoint)
            val port = if (u.port == -1) "" else ":${u.port}"
            return "${u.scheme}://${u.host}$port"
        }

        private fun jsonQuote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append("\"")
            return sb.toString()
        }
    }
}
