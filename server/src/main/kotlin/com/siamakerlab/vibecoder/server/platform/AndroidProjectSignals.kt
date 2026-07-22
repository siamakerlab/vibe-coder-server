package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.projects.PackageNameDetector
import com.siamakerlab.vibecoder.server.projects.VersionNameResolver
import java.nio.file.Files
import java.nio.file.Path

internal object AndroidProjectSignals {
    fun matchesGradleProject(root: Path): Boolean =
        listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
            .any { Files.isRegularFile(root.resolve(it)) }

    fun detectPackageName(root: Path): String? = PackageNameDetector.detectApplicationId(root)

    fun detectModuleName(root: Path): String? = PackageNameDetector.detectAppModule(root)

    fun resolveGradleVersionName(projectRoot: Path, moduleName: String): String? {
        val moduleDir = projectRoot.resolve(moduleName.replace(':', '/'))
        val props = loadVersionProps(projectRoot)
        for (g in listOf("build.gradle.kts", "build.gradle")) {
            val gf = moduleDir.resolve(g)
            if (!Files.isRegularFile(gf)) continue
            val text = runCatching { Files.readString(gf) }.getOrNull() ?: continue
            VersionNameResolver.resolve(text, props)?.let { return it }
        }
        return null
    }

    fun resolveFlutterVersionName(projectRoot: Path): String? {
        val pubspec = projectRoot.resolve("pubspec.yaml")
        if (!Files.isRegularFile(pubspec)) return null
        return runCatching { Files.readString(pubspec) }.getOrNull()?.let { text ->
            Regex("""(?m)^version:\s*([^\s#]+)""").find(text)
                ?.groupValues
                ?.get(1)
                ?.substringBefore('+')
                ?.take(32)
        }
    }

    fun resolveAndroidAppIcon(projectRoot: Path, moduleName: String, flutter: Boolean): Path? {
        val modulePath = moduleName.replace(':', '/')
        val resCandidates = buildList {
            add(modulePath)
            if (flutter) {
                add("android/app")
                add("android/$modulePath")
            }
        }.distinct()
        val densities = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "")
        val bases = listOf("mipmap", "drawable")
        val names = listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground")
        for (modPath in resCandidates) {
            val resRoot = projectRoot.resolve(modPath).resolve("src/main/res")
            if (!Files.isDirectory(resRoot)) continue
            for (density in densities) for (base in bases) {
                val dir = resRoot.resolve(if (density.isEmpty()) base else "$base-$density")
                if (!Files.isDirectory(dir)) continue
                for (name in names) for (ext in listOf("png", "webp")) {
                    val file = dir.resolve("$name.$ext")
                    if (Files.isRegularFile(file)) return file
                }
            }
        }
        val rootIcon = projectRoot.resolve("icon.png")
        if (Files.isRegularFile(rootIcon)) return rootIcon
        return null
    }

    private fun loadVersionProps(root: Path): Map<String, String> {
        val f = root.resolve("version.properties")
        if (!Files.isRegularFile(f)) return emptyMap()
        return runCatching {
            Files.readAllLines(f).mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val i = trimmed.indexOf('=')
                if (i < 0) null else trimmed.substring(0, i).trim() to trimmed.substring(i + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
