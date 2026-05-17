package com.siamakerlab.vibecoder.console.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.ProjectRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.console.ui.common.Loading
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProjectDetailUi(val loading: Boolean = true, val project: ProjectDto? = null, val error: String? = null)

@HiltViewModel
class ProjectDetailViewModel @Inject constructor(private val repo: ProjectRepository) : ViewModel() {
    val state = MutableStateFlow(ProjectDetailUi())
    fun load(id: String) {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.get(id) }
                .onSuccess { dto -> state.update { it.copy(loading = false, project = dto) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onClaude: () -> Unit,
    onBuilds: () -> Unit,
    onArtifacts: () -> Unit,
    onGit: () -> Unit,
    onFiles: () -> Unit,
    onBack: () -> Unit,
    vm: ProjectDetailViewModel,
) {
    LaunchedEffect(projectId) { vm.load(projectId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(state.project?.name ?: projectId) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        when {
            state.loading -> Loading(Modifier.padding(pad))
            state.error != null -> ErrorText(state.error!!, Modifier.padding(pad))
            else -> {
                val p = state.project!!
                Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.packageName, style = MaterialTheme.typography.bodyMedium)
                            Text(p.sourcePath, style = MaterialTheme.typography.bodySmall)
                            Text("module=${p.moduleName} task=${p.debugTask}",
                                style = MaterialTheme.typography.bodySmall)
                            p.lastBuildStatus?.let { Text("last build: $it") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onClaude, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_claude))
                    }
                    OutlinedButton(onClick = onBuilds, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_build))
                    }
                    OutlinedButton(onClick = onArtifacts, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_artifacts))
                    }
                    OutlinedButton(onClick = onGit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_git))
                    }
                    OutlinedButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.detail_files))
                    }
                }
            }
        }
    }
}
