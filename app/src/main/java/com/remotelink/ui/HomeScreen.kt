package com.remotelink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remotelink.R

@Composable
fun HomeScreen(onController: () -> Unit, onHost: () -> Unit) {
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            RoleCard(stringResource(R.string.role_controller), stringResource(R.string.role_controller_desc), onController)
            RoleCard(stringResource(R.string.role_host), stringResource(R.string.role_host_desc), onHost)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.own_devices_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoleCard(title: String, desc: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(desc, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
