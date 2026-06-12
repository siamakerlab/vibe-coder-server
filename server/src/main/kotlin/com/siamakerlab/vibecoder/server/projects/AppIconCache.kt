package com.siamakerlab.vibecoder.server.projects

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val log = KotlinLogging.logger {}

/**
 * v1.137.3 — 프로젝트 목록 런처 아이콘의 리사이즈 + 캐시.
 *
 * 종전 `/projects/{id}/app-icon` 은 [ProjectService.resolveAppIcon] 이 찾은 **원본**
 * (최대 density ic_launcher, 루트 `icon.png` 폴백 시 업로드 원본 수 MB 가능)을 매 요청
 * 그대로 서빙했고 브라우저 캐시도 60초뿐이라, 목록(수십 행 × 30px `<img>`)을 열 때마다
 * 큰 PNG 들을 반복 전송했다.
 *
 * 이제 [ICON_SIZE]px(표시 30px 의 2× — HiDPI 대응) PNG 로 다운스케일한 결과를
 * **in-memory 캐시**(프로젝트당 수 KB)하고, 원본의 (경로, mtime, size) 가 바뀌면
 * 자동 재생성한다. 응답에는 ETag 를 실어 라우트가 304 재검증을 처리한다.
 *
 * - WebP 등 ImageIO 가 디코드 못 하는 포맷이나 디코드 실패 시엔 **원본 바이트로 폴백**
 *   (기능 회귀 없음 — 캐시/ETag 는 동일 적용).
 * - 디스크에 쓰지 않으므로 프로젝트 삭제/이름변경 시 정리할 부수 파일이 없다.
 *   재시작 시 캐시는 비지만 재생성 비용은 아이콘당 수 ms.
 */
object AppIconCache {

    const val ICON_SIZE = 64

    class Entry(
        val bytes: ByteArray,
        val contentType: String,
        val etag: String,
        internal val srcPath: String,
        internal val srcMtime: Long,
        internal val srcSize: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * [src] 의 리사이즈본(또는 폴백 원본)을 반환. 원본이 바뀌면 재생성.
     * IO 실패 시 null — 호출자는 404 처리.
     */
    fun get(projectId: String, src: Path): Entry? {
        val attrs = runCatching { Files.readAttributes(src, java.nio.file.attribute.BasicFileAttributes::class.java) }
            .getOrNull() ?: return null
        val mtime = attrs.lastModifiedTime().toMillis()
        val size = attrs.size()
        val srcPath = src.toString()
        cache[projectId]?.let { e ->
            if (e.srcPath == srcPath && e.srcMtime == mtime && e.srcSize == size) return e
        }
        val raw = runCatching { Files.readAllBytes(src) }.getOrNull() ?: return null
        val (bytes, contentType) = resize(raw, srcPath)
        val etag = "\"${java.lang.Long.toHexString(mtime)}-${java.lang.Long.toHexString(size)}-$ICON_SIZE\""
        val entry = Entry(bytes, contentType, etag, srcPath, mtime, size)
        cache[projectId] = entry
        return entry
    }

    /** 디코드 가능하고 [ICON_SIZE] 보다 크면 다운스케일 PNG, 아니면 원본 그대로. */
    private fun resize(raw: ByteArray, srcPath: String): Pair<ByteArray, String> {
        val fallbackType = if (srcPath.endsWith(".webp", ignoreCase = true)) "image/webp" else "image/png"
        val img = runCatching { ImageIO.read(raw.inputStream()) }.getOrNull()
            ?: return raw to fallbackType  // webp 등 미지원 포맷 — 원본 폴백
        if (img.width <= ICON_SIZE && img.height <= ICON_SIZE) return raw to fallbackType
        return runCatching {
            val scale = ICON_SIZE.toDouble() / maxOf(img.width, img.height)
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(img, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            val buf = ByteArrayOutputStream(8 * 1024)
            ImageIO.write(out, "png", buf)
            buf.toByteArray() to "image/png"
        }.getOrElse { e ->
            log.debug(e) { "app icon resize failed ($srcPath); serving original" }
            raw to fallbackType
        }
    }
}
