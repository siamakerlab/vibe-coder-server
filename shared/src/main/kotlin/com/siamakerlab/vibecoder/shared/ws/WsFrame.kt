package com.siamakerlab.vibecoder.shared.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WebSocket frame format used on /ws/projects/{projectId}/tasks/{taskId}/logs
 * and /ws/projects/{projectId}/builds/{buildId}/logs.
 *
 * The very first frame from the client MUST be [Auth]; otherwise the server
 * closes the connection within 5 seconds.
 */
@Serializable
sealed class WsFrame {

    @Serializable
    @SerialName("auth")
    data class Auth(val token: String) : WsFrame()

    @Serializable
    @SerialName("log")
    data class Log(
        val taskId: String,
        val level: String, // "INFO" | "WARN" | "ERROR" | "STDOUT" | "STDERR"
        val message: String,
        val ts: String,
    ) : WsFrame()

    @Serializable
    @SerialName("done")
    data class Done(
        val taskId: String,
        val status: String, // TaskStatus name
        val errorMessage: String? = null,
    ) : WsFrame()

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
    ) : WsFrame()

    @Serializable
    @SerialName("ping")
    data object Ping : WsFrame()
}

object WsLevel {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
    const val STDOUT = "STDOUT"
    const val STDERR = "STDERR"
}
