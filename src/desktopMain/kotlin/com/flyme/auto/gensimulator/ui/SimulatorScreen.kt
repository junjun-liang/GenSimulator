package com.flyme.auto.gensimulator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flyme.auto.gensimulator.adb.DeviceInfo
import com.flyme.auto.gensimulator.presentation.SimulatorUiState
import com.flyme.auto.gensimulator.presentation.SimulatorViewModel
import com.flyme.auto.gensimulator.protocol.SimulatorErrorCode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SimulatorScreen(viewModel: SimulatorViewModel) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("GenCards Voice/Text Simulator", style = MaterialTheme.typography.headlineSmall)
            if (!state.bridgeConfigured) {
                Text(
                    "Bridge package 未配置：请设置 GENCARDS_BRIDGE_PACKAGE。应用可以启动，但发送前 Preflight 会阻止请求。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            DevicePanel(state, viewModel)
            Divider()
            QueryEditor(state, viewModel)
            Divider()
            ResultPanel(state)
            Divider()
            HistoryPanel(state, viewModel)
            Divider()
            DiagnosticsPanel(state, viewModel)
        }
    }
}

@Composable
private fun DevicePanel(state: SimulatorUiState, viewModel: SimulatorViewModel) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Button(onClick = viewModel::refreshDevices) { Text("Refresh") }
        }

        Box {
            Button(onClick = { expanded = true }, enabled = state.devices.isNotEmpty()) {
                Text(state.selectedDevice?.displayName() ?: if (state.devices.isEmpty()) "No device" else "Select device")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.displayName()) },
                        onClick = {
                            expanded = false
                            viewModel.selectDevice(device.serial)
                        },
                    )
                }
            }
        }

        Text("ADB: ${status(state.adbReady)}${state.adbPath?.let { "  $it" } ?: ""}")
        Text("GenCards: ${status(state.genCardsInstalled)}")
        Text("Bridge: ${status(state.bridgeReachable)}")
        Text("Protocol: ${state.protocolVersion?.let { "v$it" } ?: "—"}")
        Text("Signature: ${when (state.signatureMatched) { true -> "matched"; false -> "mismatch"; null -> "—" }}")
        Text("Binder: ${status(state.binderConnected)}${state.binderState?.let { "  $it" } ?: ""}")
        Text("Contract: ${status(state.contractMatched)}${state.contractVersion?.let { "  v$it" } ?: ""}")
    }
}

@Composable
private fun QueryEditor(state: SimulatorUiState, viewModel: SimulatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Text / Document", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            label = { Text("输入发送给 GenCards 的文本") },
            minLines = 6,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::selectTextFile) { Text("Open TXT / MD / JSON / YAML") }
            Spacer(Modifier.width(12.dp))
            Text("${state.queryLength} chars")
        }
        Button(
            onClick = viewModel::send,
            enabled = !state.sending && state.query.isNotBlank() && state.selectedDevice != null,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(if (state.sending) "Sending…" else "Send to GenCards")
        }
    }
}

@Composable
private fun ResultPanel(state: SimulatorUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Result", style = MaterialTheme.typography.titleMedium)
        val code = state.lastResultCode
        when {
            code == null -> Text("尚未发送请求")
            code == SimulatorErrorCode.INGRESS_ACCEPTED -> Text("✓ GenCards 已接收，请在车机上查看生成页")
            else -> Text("${code.name}: ${state.lastMessage.orEmpty()}")
        }
        state.lastWireCode?.let { Text("code: $it") }
        state.lastRequestId?.let { Text("requestId: $it") }
        state.lastLatencyMs?.let { Text("latency: ${it} ms") }
        if (code == SimulatorErrorCode.INGRESS_ACCEPTED) {
            Text("注意：accepted 仅表示 GenCards 已将请求入队；生成页由 GenCards 在账号准入后自行打开，不代表 Card / Widget 已生成成功。")
        }
    }
}

@Composable
private fun HistoryPanel(state: SimulatorUiState, viewModel: SimulatorViewModel) {
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request History", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = viewModel::clearHistory, enabled = state.history.isNotEmpty()) { Text("Clear") }
        }
        state.history.take(20).forEach { record ->
            Text(
                "${formatter.format(record.createdAt)}  ${record.resultCode}  ${record.latencyMs}ms  len=${record.queryLength}  ${record.queryPreview}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.history.isEmpty()) Text("暂无历史")
    }
}

@Composable
private fun DiagnosticsPanel(state: SimulatorUiState, viewModel: SimulatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = viewModel::openLogsDirectory) { Text("Open Logs") }
        }
        state.diagnostics.takeLast(12).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        if (state.diagnostics.isEmpty()) Text("暂无诊断日志")
    }
}

private fun status(value: Boolean): String = if (value) "✓" else "—"

private fun DeviceInfo.displayName(): String = buildString {
    append(serial)
    model?.let { append(" / ").append(it) }
    append(" [").append(state.name.lowercase()).append(']')
}
