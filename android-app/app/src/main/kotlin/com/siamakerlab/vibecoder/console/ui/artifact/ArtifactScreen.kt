package com.siamakerlab.vibecoder.console.ui.artifact

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.remote.DownloadService
import com.siamakerlab.vibecoder.console.data.repository.ArtifactRepository
import com.siamakerlab.vibecoder.console.install.ApkInstaller
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ArtifactUi(
    val items: List<ArtifactDto> = emptyList(),
    val downloading: String? = null,
    val progress: Float = 0f,
    val downloaded: Map<String, File> = emptyMap(),
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class ArtifactViewModel @Inject constructor(
    private val repo: ArtifactRepository,
    private val downloader: DownloadService,
) : ViewModel() {
    val state = MutableStateFlow(ArtifactUi())

    fun load(projectId: String) {
        viewModelScope.launch {
            runCatching { repo.list(projectId) }
                .onSuccess { l -> state.update { it.copy(items = l) } }
                .onFailure { e -> state.update { it.copy(error = e.message) } }
        }
    }

    fun download(projectId: String, artifact: ArtifactDto) {
        viewModelScope.launch {
            state.update { it.copy(downloading = artifact.id, progress = 0f, error = null) }
            runCatching {
                val url = repo.downloadUrl(projectId, artifact.id)
                downloader.downloadApk(url, artifact.id) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0f
                    state.update { it.copy(progress = frac) }
                }
            }.onSuccess { file ->
                state.update { it.copy(downloading = null, downloaded = it.downloaded + (artifact.id to file)) }
            }.onFailure { e ->
                state.update { it.copy(downloading = null, error = e.message) }
            }
        }
    }

    fun install(context: Context, artifact: ArtifactDto) {
        val file = state.value.downloaded[artifact.id] ?: return
        val installer = ApkInstaller(context)
        when (val r = installer.verifyAndInstall(file, artifact.sha256)) {
            is ApkInstaller.Result.InstallStarted -> Unit
            is ApkInstaller.Result.Sha256Mismatch ->
                state.update { it.copy(error = "sha256_mismatch") }
            is ApkInstaller.Result.UnknownSourcesNotAllowed ->
                state.update { it.copy(notice = "unknown_sources_open") }
            is ApkInstaller.Result.Other ->
                state.update { it.copy(error = r.message) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactScreen(projectId: String, onBack: () -> Unit, vm: ArtifactViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(projectId) { vm.load(projectId) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.artifact_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let {
                ErrorText(if (it == "sha256_mismatch") stringResource(R.string.artifact_verify_fail) else it)
            }
            state.notice?.let {
                Text(if (it == "unknown_sources_open") stringResource(R.string.artifact_unknown_sources) else it)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.id }) { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(a.fileName, style = MaterialTheme.typography.titleSmall)
                            Text("buildId=${a.buildId}", style = MaterialTheme.typography.bodySmall)
                            Text("size=${a.sizeBytes} B", style = MaterialTheme.typography.bodySmall)
                            Text("sha=${a.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                            if (state.downloading == a.id) {
                                LinearProgressIndicator(progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.download(projectId, a) },
                                    enabled = state.downloading == null) {
                                    Text(stringResource(R.string.artifact_download))
                                }
                                Button(onClick = { vm.install(ctx, a) },
                                    enabled = state.downloaded.containsKey(a.id)) {
                                    Text(stringResource(R.string.artifact_install))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
