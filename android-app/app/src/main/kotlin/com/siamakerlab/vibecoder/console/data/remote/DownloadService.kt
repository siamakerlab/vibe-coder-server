package com.siamakerlab.vibecoder.console.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: HttpClient,
) {
    /**
     * Download a file from [url] into the app's private apks cache and report progress.
     *
     * Uses `bodyAsChannel().toInputStream()` (Ktor 3.x JVM helper) instead of the
     * various `readAvailable` extensions whose locations have moved between Ktor
     * minor versions. Java InputStream is stable across versions.
     */
    suspend fun downloadApk(
        url: String,
        artifactId: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val target = File(dir, "$artifactId.apk")
        val tmp = File(dir, "$artifactId.apk.part")
        if (tmp.exists()) tmp.delete()

        val resp: HttpResponse = client.get(url)
        val total = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L

        resp.bodyAsChannel().toInputStream().use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var transferred = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    transferred += n
                    onProgress(transferred, total)
                }
            }
        }

        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return target
    }
}
