package com.remotelink.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.remotelink.R
import com.remotelink.core.network.ConnState
import com.remotelink.core.storage.PairedHost

private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

@Composable
fun ControllerScreen(vm: ControllerViewModel, onBack: () -> Unit, onSession: () -> Unit) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val pending by vm.pendingQr.collectAsStateWithLifecycle()
    val pairUi by vm.pairUi.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var manualFor by remember { mutableStateOf<PairedHost?>(null) }

    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    val active = conn is ConnState.Connecting || conn is ConnState.Connected
    LaunchedEffect(active) { if (active) onSession() }

    val scan = rememberLauncherForActivityResult(ScanContract()) { res ->
        val text = res.contents
        if (text != null && !vm.onQr(text)) {
            Toast.makeText(ctx, ctx.getString(R.string.qr_invalid), Toast.LENGTH_SHORT).show()
        }
    }

    ScreenScaffold(stringResource(R.string.ctrl_title), onBack) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Button(
                    onClick = {
                        scan.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                                .setPrompt("")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.ctrl_scan)) }
            }

            item { Text(stringResource(R.string.ctrl_nearby), style = MaterialTheme.typography.titleMedium) }
            if (found.isEmpty()) {
                item { Text(stringResource(R.string.ctrl_nearby_empty), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(found, key = { it.serviceName }) { d ->
                    val paired = hosts.firstOrNull { it.id == d.hostId }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, fontWeight = FontWeight.Bold)
                                Text("${d.ip}:${d.port}", style = MaterialTheme.typography.bodySmall)
                                if (paired == null) {
                                    Text(stringResource(R.string.ctrl_not_paired), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (paired != null) {
                                Button(onClick = { vm.connect(paired) }) { Text(stringResource(R.string.ctrl_connect)) }
                            }
                        }
                    }
                }
            }

            item { Text(stringResource(R.string.ctrl_paired), style = MaterialTheme.typography.titleMedium) }
            items(hosts, key = { it.id }) { h ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(h.name, fontWeight = FontWeight.Bold)
                        Text(h.ips.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { vm.connect(h) }) { Text(stringResource(R.string.ctrl_connect)) }
                            TextButton(onClick = { manualFor = h }) { Text(stringResource(R.string.ctrl_manual_ip)) }
                            TextButton(onClick = { vm.removeHost(h) }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
        }
    }

    // ---- PIN dialog ----
    val q = pending
    if (q != null) {
        var pin by remember(q) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (pairUi !is PairUi.Busy) vm.cancelPair() },
            title = { Text(stringResource(R.string.ctrl_pin_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${q.name}\n${stringResource(R.string.ctrl_pin_msg)}")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    when (val u = pairUi) {
                        is PairUi.Error -> Text(errorText(u.code), color = MaterialTheme.colorScheme.error)
                        PairUi.Busy -> Text(stringResource(R.string.ctrl_pairing))
                        PairUi.None -> {}
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.submitPin(pin) },
                    enabled = pin.length == 6 && pairUi !is PairUi.Busy
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelPair() }, enabled = pairUi !is PairUi.Busy) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // ---- manual IP dialog ----
    val target = manualFor
    if (target != null) {
        var ip by remember(target) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualFor = null },
            title = { Text(stringResource(R.string.ctrl_manual_title)) },
            text = {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { v -> ip = v.filter { it.isDigit() || it == '.' }.take(15) },
                    label = { Text(stringResource(R.string.ctrl_ip_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.connect(target, ip)
                        manualFor = null
                    },
                    enabled = IPV4.matches(ip)
                ) { Text(stringResource(R.string.ctrl_connect)) }
            },
            dismissButton = {
                TextButton(onClick = { manualFor = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
