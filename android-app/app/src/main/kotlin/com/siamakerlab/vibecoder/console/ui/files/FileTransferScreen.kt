package com.siamakerlab.vibecoder.console.ui.files

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.data.repository.FileRepository
import com.siamakerlab.vibecoder.console.ui.common.ErrorText
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FilesUi(
    val files: List<FileEntryDto> = emptyList(),
    val uploading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val repo: FileRepository) : ViewModel() {
    val state = MutableStateFlow(FilesUi())
    fun load(projectId: String) {
        viewModelScope.launch {
            runCatching { repo.list(projectId) }
                .onSuccess { l -> state.update { it.copy(files = l) } }
                .onFailure { e -> state.update { it.copy(error = e.message) } }
        }
    }
    fun upload(projectId: String, fileName: String, mime: String, bytes: ByteArray) {
        state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.upload(projectId, fileName, mime, bytes); repo.list(projectId) }
                .onSuccess { l -> state.update { it.copy(uploading = false, files = l) } }
                .onFailure { e -> state.update { it.copy(uploading = false, error = e.message) } }
        }
    }
    fun delete(projectId: String, fileId: String) {
        viewModelScope.launch {
            runCatching { repo.delete(projectId, fileId); repo.list(projectId) }
                .onSuccess { l -> state.update { it.copy(files = l) } }
                .onFailure { e -> state.update { it.copy(error = e.message) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTransferScreen(projectId: String, onBack: () -> Unit, vm: FilesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(projectId) { vm.load(projectId) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        vm.viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { readBytes(ctx, uri) }
            val (name, mime) = readMeta(ctx, uri)
            vm.upload(projectId, name, mime, data)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.files_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch("*/*") }, enabled = !state.uploading,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.files_pick))
            }
            state.error?.let { ErrorText(it) }
            if (state.files.isEmpty()) Text(stringResource(R.string.files_empty))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.files, key = { it.id }) { f ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(f.originalName, style = MaterialTheme.typography.titleSmall)
                            Text("${f.sizeBytes} B • ${f.mimeType ?: "?"}",
                                style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { vm.delete(projectId, f.id) }) {
                                Icon(Icons.Default.Delete, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readBytes(ctx: Context, uri: Uri): ByteArray =
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

private fun readMeta(ctx: Context, uri: Uri): Pair<String, String> {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val cursor = ctx.contentResolver.query(uri, null, null, null, null)
    val name = cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) it.getString(idx) else "upload"
        } else "upload"
    } ?: "upload"
    return name to mime
}
