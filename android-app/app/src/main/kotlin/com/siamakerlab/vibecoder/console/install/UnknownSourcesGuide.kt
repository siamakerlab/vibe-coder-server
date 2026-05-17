package com.siamakerlab.vibecoder.console.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object UnknownSourcesGuide {
    /**
     * Open the OS settings screen for "install unknown apps" scoped to our package.
     * Required at least once on Android 8+; missing this permission is the most
     * common reason the install Intent silently no-ops.
     */
    fun launch(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val uri = Uri.parse("package:${context.packageName}")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
