package com.siamakerlab.vibecoder.console.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.ProjectRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUi(val loading: Boolean = false, val error: String? = null, val registeredId: String? = null)

@HiltViewModel
class ProjectRegisterViewModel @Inject constructor(private val repo: ProjectRepository) : ViewModel() {
    val state = MutableStateFlow(RegisterUi())
    fun submit(req: RegisterProjectRequestDto) {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.register(req) }
                .onSuccess { dto -> state.update { it.copy(loading = false, registeredId = dto.id) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectRegisterScreen(
    onRegistered: (String) -> Unit,
    onBack: () -> Unit,
    vm: ProjectRegisterViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var src by remember { mutableStateOf("") }
    var module by remember { mutableStateOf("app") }
    var debug by remember { mutableStateOf("assembleDebug") }

    LaunchedEffect(state.registeredId) {
        state.registeredId?.let(onRegistered)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.register_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            field(stringResource(R.string.register_id), id) { id = it }
            field(stringResource(R.string.register_name), name) { name = it }
            field(stringResource(R.string.register_package), pkg) { pkg = it }
            field(stringResource(R.string.register_source_path), src) { src = it }
            field(stringResource(R.string.register_module), module) { module = it }
            field(stringResource(R.string.register_debug_task), debug) { debug = it }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.submit(RegisterProjectRequestDto(
                        projectId = id.trim(), name = name.trim(), packageName = pkg.trim(),
                        sourcePath = src.trim(), moduleName = module.trim().ifBlank { "app" },
                        debugTask = debug.trim().ifBlank { "assembleDebug" },
                    ))
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.register_button)) }
            state.error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
}
