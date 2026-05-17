package com.siamakerlab.vibecoder.console.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

data class ProjectListUi(val loading: Boolean = true, val projects: List<ProjectDto> = emptyList(), val error: String? = null)

@HiltViewModel
class ProjectListViewModel @Inject constructor(private val repo: ProjectRepository) : ViewModel() {
    val state = MutableStateFlow(ProjectListUi())
    init { refresh() }
    fun refresh() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.list() }
                .onSuccess { list -> state.update { it.copy(loading = false, projects = list) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onRegister: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    vm: ProjectListViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, null) } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onRegister) { Icon(Icons.Default.Add, null) }
        }
    ) { pad ->
        when {
            state.loading -> Loading(Modifier.padding(pad))
            state.error != null -> Column(Modifier.padding(pad)) { ErrorText(state.error!!) }
            state.projects.isEmpty() -> Column(Modifier.fillMaxSize().padding(pad).padding(24.dp)) {
                Text(stringResource(R.string.projects_empty))
            }
            else -> LazyColumn(Modifier.padding(pad).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.projects, key = { it.id }) { p ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(p.packageName, style = MaterialTheme.typography.bodySmall)
                            Text("module=${p.moduleName} • ${p.lastBuildStatus ?: "no build yet"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
