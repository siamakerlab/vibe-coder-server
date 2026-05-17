package com.siamakerlab.vibecoder.console.ui.claude

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.TaskRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClaudeUi(val sending: Boolean = false, val error: String? = null, val taskId: String? = null)

@HiltViewModel
class ClaudePromptViewModel @Inject constructor(private val repo: TaskRepository) : ViewModel() {
    val state = MutableStateFlow(ClaudeUi())
    fun submit(projectId: String, prompt: String, autoBuild: Boolean) {
        if (prompt.isBlank()) return
        state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.submitClaudeTask(projectId, prompt, autoBuild) }
                .onSuccess { t -> state.update { it.copy(sending = false, taskId = t.id) } }
                .onFailure { e -> state.update { it.copy(sending = false, error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudePromptScreen(
    projectId: String,
    onTaskStarted: (String) -> Unit,
    onBack: () -> Unit,
    vm: ClaudePromptViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }
    var autoBuild by remember { mutableStateOf(false) }

    LaunchedEffect(state.taskId) { state.taskId?.let(onTaskStarted) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.claude_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                placeholder = { Text(stringResource(R.string.claude_prompt_hint)) },
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoBuild, onCheckedChange = { autoBuild = it })
                Text(stringResource(R.string.claude_auto_build))
            }
            Button(onClick = { vm.submit(projectId, prompt, autoBuild) },
                enabled = !state.sending,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.claude_submit)) }
            state.error?.let { ErrorText(it) }
        }
    }
}
