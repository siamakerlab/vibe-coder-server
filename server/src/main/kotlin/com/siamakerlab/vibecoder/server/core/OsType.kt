package com.siamakerlab.vibecoder.server.core

enum class OsType {
    WINDOWS, LINUX, MAC;

    fun gradleWrapperFileName(): String = when (this) {
        WINDOWS -> "gradlew.bat"
        LINUX, MAC -> "gradlew"
    }

    /**
     * Build the OS-appropriate command line for running the Gradle wrapper.
     * On Windows we run gradlew.bat directly. On POSIX we prefix with `./`.
     */
    fun gradleCommand(projectDir: java.nio.file.Path, tasks: List<String>): List<String> {
        val wrapper = projectDir.resolve(gradleWrapperFileName())
        return when (this) {
            WINDOWS -> listOf(wrapper.toString()) + tasks
            LINUX, MAC -> listOf("./" + gradleWrapperFileName()) + tasks
        }
    }

    companion object {
        fun detect(): OsType {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> WINDOWS
                os.contains("mac") || os.contains("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}
