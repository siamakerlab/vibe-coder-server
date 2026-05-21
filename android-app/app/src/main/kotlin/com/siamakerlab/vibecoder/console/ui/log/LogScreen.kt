package com.siamakerlab.vibecoder.console.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.remote.WsClient
import com.siamakerlab.vibecoder.console.data.repository.BuildRepository
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import com.siamakerlab.vibecoder.shared.ws.WsLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogLine(val level: String, val message: String, val ts: String)
data class LogUi(
    val lines: List<LogLine> = emptyList(),
    val done: Boolean = false,
    val doneStatus: String? = null,
    val error: String? = null,
)

/**
 * Live log viewer for build jobs. As of v0.2.1 the one-shot Claude task pipeline
 * was retired, so this screen is build-only — the Claude console handles its own
 * streaming in `ui/console/`.
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val ws: WsClient,
    private val buildRepo: BuildRepository,
) : ViewModel() {
    val state = MutableStateFlow(LogUi())

    fun connect(projectId: String, buildId: String) {
        viewModelScope.launch {
            try {
                ws.streamBuildLogs(projectId, buildId).collect { frame ->
                    when (frame) {
                        is WsFrame.Log -> state.update {
                            it.copy(lines = it.lines + LogLine(frame.level, frame.message, frame.ts))
                        }
                        is WsFrame.Done -> state.update { it.copy(done = true, doneStatus = frame.status) }
                        is WsFrame.Error -> state.update { it.copy(error = "${frame.code}: ${frame.message}") }
                        else -> Unit
                    }
                }
            } catch (e: Throwable) {
                state.update { it.copy(error = e.message ?: "ws_failed") }
            }
        }
    }

    fun cancel(projectId: String, buildId: String) {
        viewModelScope.launch {
            runCatching { buildRepo.cancel(projectId, buildId) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    projectId: String,
    buildId: String,
    onBack: () -> Unit,
    vm: LogViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(projectId, buildId) { vm.connect(projectId, buildId) }
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.cancel(projectId, buildId) },
                    enabled = !state.done,
                ) { Text(stringResource(R.string.log_cancel)) }
                OutlinedButton(onClick = { copyAll(ctx, state.lines) }) {
                    Text(stringResource(R.string.log_copy))
                }
                if (state.done) Text("done: ${state.doneStatus ?: ""}",
                    style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(state.lines) { line ->
                    Text(
                        text = "[${line.ts.takeLast(12)}] ${line.message}",
                        color = colorFor(line.level),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun copyAll(ctx: Context, lines: List<LogLine>) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("log", lines.joinToString("\n") { "[${it.ts}] [${it.level}] ${it.message}" }))
}

private fun colorFor(level: String): Color = when (level) {
    WsLevel.ERROR, WsLevel.STDERR -> Color(0xFFEF4444)
    WsLevel.WARN -> Color(0xFFF59E0B)
    WsLevel.STDOUT -> Color(0xFFCBD5E1)
    else -> Color.Unspecified
}
