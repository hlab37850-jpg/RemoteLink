package com.remotelink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotelink.R
import com.remotelink.core.network.ConnState
import com.remotelink.core.network.NetInfo
import com.remotelink.core.network.NetUtil
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(vm: ControllerViewModel, onExit: () -> Unit) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val net by produceState(NetInfo()) {
        while (true) {
            value = NetUtil.info(ctx)
            delay(2000)
        }
    }

    BackHandler { vm.disconnect() }
    LaunchedEffect(conn) { if (conn is ConnState.Idle) onExit() }

    ScreenScaffold(stringResource(R.string.sess_title), null) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val s = conn) {
                        is ConnState.Connecting -> {
                            CircularProgressIndicator()
                            Text(
                                if (s.attempt == 0) stringResource(R.string.sess_connecting, s.hostName)
                                else stringResource(R.string.sess_reconnect, s.attempt),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        is ConnState.Connected -> {
                            Text(stringResource(R.string.sess_connected, s.hostName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.sess_ip, s.ip))
                            if (s.rttMs >= 0) Text(stringResource(R.string.sess_ping, s.rttMs.toString()))
                            Text(stringResource(R.string.sess_net, stringResource(netLabel(net.type))))
                            if (net.signal >= 0) Text(stringResource(R.string.sess_signal, net.signal))
                        }
                        is ConnState.Failed -> Text(errorText(s.code), color = MaterialTheme.colorScheme.error)
                        ConnState.Idle -> {}
                    }
                }
            }
            Text(stringResource(R.string.sess_next), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sess_disconnect))
            }
        }
    }
}

private fun netLabel(type: Int): Int = when (type) {
    NetUtil.WIFI -> R.string.net_wifi
    NetUtil.ETHERNET -> R.string.net_eth
    NetUtil.CELLULAR -> R.string.net_cell
    NetUtil.NONE -> R.string.net_none
    else -> R.string.net_other
}
