package com.siamakerlab.vibecoder.console.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.siamakerlab.vibecoder.console.data.repository.AuthRepository
import com.siamakerlab.vibecoder.console.data.repository.ServerRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.console.ui.common.Loading
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val loading: Boolean = true,
    val status: ServerStatusDto? = null,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: ServerRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val state = MutableStateFlow(DashboardUiState())

    init { refresh() }

    fun refresh() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.status() }
                .onSuccess { dto -> state.update { it.copy(loading = false, status = dto) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch { auth.logout(); onDone() }
    }
}

@Composable
fun DashboardScreen(
    onOpenEnvironment: () -> Unit,
    onOpenProjects: () -> Unit,
    onLogout: () -> Unit,
    vm: DashboardViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.loading -> Loading()
        state.error != null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            ErrorText(state.error!!)
            OutlinedButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.common_retry)) }
        }
        else -> {
            val s = state.status!!
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))

                Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${s.serverName} v${s.serverVersion}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("OS: ${s.osName}")
                        Text("Java: ${s.javaVersion}")
                        Text("Workspace: ${s.workspaceRoot}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (s.claudeAvailable) stringResource(R.string.dashboard_claude_ok)
                             else stringResource(R.string.dashboard_claude_missing))
                        Text(if (s.androidSdkAvailable) stringResource(R.string.dashboard_sdk_ok)
                             else stringResource(R.string.dashboard_sdk_missing))
                        Text(if (s.gitAvailable) stringResource(R.string.dashboard_git_ok)
                             else stringResource(R.string.dashboard_git_missing))
                        Text("${stringResource(R.string.dashboard_projects)}: ${s.projectCount}")
                        Text("${stringResource(R.string.dashboard_running_tasks)}: ${s.runningTaskCount}")
                        Text("${stringResource(R.string.dashboard_free_disk)}: ${formatBytes(s.freeDiskSpaceBytes)}")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenEnvironment) {
                        Text(stringResource(R.string.dashboard_open_env))
                    }
                    OutlinedButton(onClick = onOpenProjects) {
                        Text(stringResource(R.string.dashboard_open_projects))
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { vm.logout(onLogout) }) {
                    Text(stringResource(R.string.dashboard_logout))
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String {
    if (b < 0) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
