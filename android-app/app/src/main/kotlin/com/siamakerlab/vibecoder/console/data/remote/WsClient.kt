package com.siamakerlab.vibecoder.console.data.remote

import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsClient @Inject constructor(
    private val client: HttpClient,
    private val prefs: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    fun streamBuildLogs(projectId: String, buildId: String): Flow<WsFrame> =
        streamPath(ApiPath.wsBuildLogs(projectId, buildId))

    private fun streamPath(path: String): Flow<WsFrame> = flow {
        val session = prefs.session.first()
        val url = (session.serverUrl ?: error("not paired")).trimEnd('/') + path
        val wsUrl = url.replaceFirst(Regex("^http"), "ws")
        val token = session.token ?: error("no token")
        client.webSocket(wsUrl) {
            send(Frame.Text(json.encodeToString(WsFrame.serializer(), WsFrame.Auth(token))))
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val parsed = runCatching { json.decodeFromString(WsFrame.serializer(), frame.readText()) }.getOrNull()
                    if (parsed != null) emit(parsed)
                    if (parsed is WsFrame.Done) {
                        close()
                        return@webSocket
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
