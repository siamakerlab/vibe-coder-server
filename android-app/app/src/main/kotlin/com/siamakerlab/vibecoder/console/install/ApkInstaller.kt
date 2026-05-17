package com.siamakerlab.vibecoder.console.install

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

class ApkInstaller(private val context: Context) {

    sealed interface Result {
        data object InstallStarted : Result
        data object Sha256Mismatch : Result
        data object UnknownSourcesNotAllowed : Result
        data class  Other(val message: String) : Result
    }

    /**
     * Verify the SHA-256 of [apk] against [expectedSha256]. On match, launch
     * the system installer via a FileProvider URI. On mismatch, delete the file
     * and return [Result.Sha256Mismatch]. On Android 8+, if the user has not
     * allowed install from this app, launch the settings screen.
     */
    fun verifyAndInstall(apk: File, expectedSha256: String): Result {
        if (!Sha256Verifier.matches(apk, expectedSha256)) {
            runCatching { apk.delete() }
            return Result.Sha256Mismatch
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            UnknownSourcesGuide.launch(context)
            return Result.UnknownSourcesNotAllowed
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.InstallStarted
        } catch (e: Throwable) {
            Result.Other(e.message ?: e.javaClass.simpleName)
        }
    }
}
