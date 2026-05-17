package com.siamakerlab.vibecoder.console.ui.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.GitRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GitUi(
    val status: GitStatusDto? = null,
    val diff: GitDiffDto? = null,
    val log: GitLogDto? = null,
    val error: String? = null,
)

@HiltViewModel
class GitViewModel @Inject constructor(private val repo: GitRepository) : ViewModel() {
    val state = MutableStateFlow(GitUi())
    fun load(projectId: String) {
        viewModelScope.launch {
            runCatching {
                val s = repo.status(projectId); val d = repo.diff(projectId); val l = repo.log(projectId)
                state.update { it.copy(status = s, diff = d, log = l) }
            }.onFailure { e -> state.update { it.copy(error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(projectId: String, onBack: () -> Unit, vm: GitViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(projectId) { vm.load(projectId) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.git_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) { Text(stringResource(R.string.git_status), modifier = Modifier.padding(12.dp)) }
                Tab(selected = tab == 1, onClick = { tab = 1 }) { Text(stringResource(R.string.git_diff), modifier = Modifier.padding(12.dp)) }
                Tab(selected = tab == 2, onClick = { tab = 2 }) { Text(stringResource(R.string.git_log), modifier = Modifier.padding(12.dp)) }
            }
            state.error?.let { ErrorText(it) }
            when (tab) {
                0 -> state.status?.let { s ->
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("branch: ${s.branch}", style = MaterialTheme.typography.titleSmall)
                        s.entries.forEach { e ->
                            Text("${e.status}  ${e.path}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                1 -> state.diff?.let { d ->
                    Card(Modifier.padding(8.dp)) {
                        Text(d.diff.ifBlank { "(no diff)" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState()))
                    }
                }
                2 -> state.log?.let { l ->
                    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                        l.entries.forEach { e ->
                            Text("${e.sha}  ${e.message}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
