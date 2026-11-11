package com.remotelink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotelink.R
import com.remotelink.core.network.HostUiState
import com.remotelink.core.security.PairingManager
import kotlinx.coroutines.delay

@Composable
fun HostScreen(vm: HostViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val trusted by vm.trusted.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startHost() // الخدمة تعمل حتى لو رُفضت صلاحية الإشعار
    }

    ScreenScaffold(stringResource(R.string.host_title), onBack) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(if (st.running) R.string.host_running else R.string.host_stopped),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                        )
                        if (st.running) {
                            Text(stringResource(R.string.host_port, st.port))
                            if (st.ips.isNotEmpty()) Text(stringResource(R.string.host_ips, st.ips.joinToString("  ·  ")))
                        }
                        st.error?.let { Text(stringResource(R.string.host_error, it), color = MaterialTheme.colorScheme.error) }
                        st.connectedController?.let {
                            Text("${stringResource(R.string.host_connected_now)}: $it", fontWeight = FontWeight.Bold)
                        }
                        if (st.running) {
                            OutlinedButton(onClick = { vm.stopHost() }) { Text(stringResource(R.string.host_stop)) }
                        } else {
                            Button(onClick = {
                                val needs = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                                if (needs) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startHost()
                            }) { Text(stringResource(R.string.host_start)) }
                        }
                    }
                }
            }

            if (st.running) {
                item { PairingPanel(vm, st, session) }
            }

            item { Text(stringResource(R.string.host_trusted), style = MaterialTheme.typography.titleMedium) }
            if (trusted.isEmpty()) {
                item { Text(stringResource(R.string.host_none), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(trusted, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.Bold)
                                if (st.connectedController == c.name) {
                                    Text(stringResource(R.string.host_connected_now), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            TextButton(onClick = { vm.unpair(c.id) }) { Text(stringResource(R.string.unpair)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingPanel(vm: HostViewModel, st: HostUiState, session: PairingManager.Session?) {
    val now by produceState(System.currentTimeMillis(), session) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (session == null || session.expiresAt <= now) {
                if (session != null) Text(stringResource(R.string.host_pair_expired), color = MaterialTheme.colorScheme.error)
                if (st.ips.isEmpty()) Text(stringResource(R.string.host_no_ip), color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.beginPairing() }, enabled = st.ips.isNotEmpty()) {
                    Text(stringResource(R.string.host_pair_new))
                }
            } else {
                val bmp = remember(session.key) { qrBitmap(vm.qrPayload(session, st)).asImageBitmap() }
                Text(stringResource(R.string.host_pair_hint), style = MaterialTheme.typography.bodyMedium)
                Image(
                    bitmap = bmp, contentDescription = null,
                    modifier = Modifier.size(260.dp).background(Color.White).padding(6.dp)
                )
                Text(stringResource(R.string.host_pin_label), style = MaterialTheme.typography.labelLarge)
                Text(session.pin, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                val left = ((session.expiresAt - now) / 1000).coerceAtLeast(0)
                Text(stringResource(R.string.host_expires, "%d:%02d".format(left / 60, left % 60)))
                TextButton(onClick = { vm.cancelPairing() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
