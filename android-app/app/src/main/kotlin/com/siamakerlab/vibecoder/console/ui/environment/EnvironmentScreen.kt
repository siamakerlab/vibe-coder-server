package com.siamakerlab.vibecoder.console.ui.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.ServerRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.console.ui.common.Loading
import com.siamakerlab.vibecoder.console.ui.common.StatusChip
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EnvUi(val loading: Boolean = true, val data: EnvironmentCheckDto? = null, val error: String? = null)

@HiltViewModel
class EnvironmentViewModel @Inject constructor(private val repo: ServerRepository) : ViewModel() {
    val state = MutableStateFlow(EnvUi())
    init { refresh() }
    fun refresh() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.environment() }
                .onSuccess { dto -> state.update { it.copy(loading = false, data = dto) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentScreen(onBack: () -> Unit, vm: EnvironmentViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.env_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { pad ->
        when {
            state.loading -> Loading(Modifier.padding(pad))
            state.error != null -> Column(Modifier.padding(pad)) {
                ErrorText(state.error!!)
                OutlinedButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.common_retry)) }
            }
            else -> Column(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EnvCard(state.data!!.java)
                EnvCard(state.data!!.androidSdk)
                EnvCard(state.data!!.git)
                EnvCard(state.data!!.claude)
                EnvCard(state.data!!.workspace)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.env_refresh)) }
            }
        }
    }
}

@Composable
private fun EnvCard(item: CheckItemDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row {
                StatusChip(item.status)
                Text(item.name, style = MaterialTheme.typography.titleSmall)
            }
            Text(item.message)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
