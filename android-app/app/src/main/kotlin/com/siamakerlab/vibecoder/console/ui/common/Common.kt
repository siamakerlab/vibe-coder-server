package com.siamakerlab.vibecoder.console.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siamakerlab.vibecoder.shared.dto.CheckStatus

@Composable
fun StatusChip(status: CheckStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        CheckStatus.OK -> "OK" to Color(0xFF16A34A)
        CheckStatus.WARNING -> "WARN" to Color(0xFFD97706)
        CheckStatus.ERROR -> "ERR" to Color(0xFFDC2626)
    }
    Surface(
        color = color, shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(end = 8.dp),
    ) {
        Text(label, color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message, color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
    )
}
