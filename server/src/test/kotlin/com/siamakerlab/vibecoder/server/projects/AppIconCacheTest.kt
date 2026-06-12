package com.siamakerlab.vibecoder.server.projects

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO

/**
 * v1.137.3 — 프로젝트 목록 아이콘 리사이즈+캐시 회귀 검증.
 * 큰 원본 → 64px 이하 PNG 로 축소, 동일 원본 재요청은 캐시 재사용, 원본 변경(mtime) 시 재생성.
 */
class AppIconCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writePng(size: Int): Path {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until size) for (y in 0 until size) img.setRGB(x, y, 0xFF3366CC.toInt())
        val f = tmp.newFile("icon-$size.png").toPath()
        ImageIO.write(img, "png", f.toFile())
        return f
    }

    @Test
    fun `큰 원본은 64px 이하 PNG 로 축소된다`() {
        val src = writePng(512)
        val entry = AppIconCache.get("p-large", src)!!
        entry.contentType shouldBe "image/png"
        (entry.bytes.size < Files.size(src)).shouldBeTrue()
        val out = ImageIO.read(entry.bytes.inputStream())
        out.width shouldBeLessThanOrEqual AppIconCache.ICON_SIZE
        out.height shouldBeLessThanOrEqual AppIconCache.ICON_SIZE
    }

    @Test
    fun `동일 원본 재요청은 캐시를 재사용하고, 원본 변경 시 재생성한다`() {
        val src = writePng(256)
        val first = AppIconCache.get("p-cache", src)!!
        val second = AppIconCache.get("p-cache", src)!!
        (first === second).shouldBeTrue()  // 같은 캐시 인스턴스

        // 원본 변경(mtime 이동) → 다른 entry + 새 ETag 재검증 키
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        val third = AppIconCache.get("p-cache", src)!!
        (first === third) shouldBe false
        third.etag shouldNotBe first.etag
    }

    @Test
    fun `이미 작은 원본은 그대로 통과한다`() {
        val src = writePng(48)
        val entry = AppIconCache.get("p-small", src)!!
        entry.bytes.size.toLong() shouldBe Files.size(src)
    }
}
